package org.fabt.shelter.service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.fabt.availability.service.AvailabilityService;
import org.fabt.availability.service.AvailabilityService.AvailabilitySnapshot;
import org.fabt.auth.service.UserService;
import org.fabt.notification.service.NotificationPersistenceService;
import org.fabt.referral.service.ReferralTokenService;
import org.fabt.reservation.service.ReservationService;
import org.fabt.reservation.service.ReservationService.CancelledHoldSummary;
import org.fabt.shelter.domain.DeactivationReason;
import org.fabt.shelter.domain.DvAddressPolicy;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.domain.ShelterConstraints;
import org.fabt.shelter.domain.ShelterType;
import org.fabt.shelter.repository.ShelterConstraintsRepository;
import org.fabt.shelter.repository.ShelterRepository;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.shared.cache.CacheNames;
import org.fabt.shared.cache.TenantScopedCacheService;
import org.fabt.shelter.api.CreateShelterRequest;
import org.fabt.shelter.api.ShelterConstraintsDto;
import org.fabt.shelter.api.ShelterDetailResponse.AvailabilityDto;
import org.fabt.shelter.api.UpdateShelterRequest;
import org.fabt.shelter.domain.PopulationType;
import org.fabt.shared.web.TenantContext;
import org.fabt.tenant.service.TenantService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShelterService {

    private static final Logger log = LoggerFactory.getLogger(ShelterService.class);

    /**
     * Capacity data derived from latest bed_availability snapshots.
     * Replaces the former ShelterCapacity domain object (shelter_capacity table dropped in V20).
     */
    public record CapacityFromAvailability(String populationType, int bedsTotal) {
    }

    public record ShelterDetail(Shelter shelter, ShelterConstraints constraints,
                                List<CapacityFromAvailability> capacities, List<AvailabilityDto> availability) {
    }

    public record ShelterFilterCriteria(Boolean petsAllowed, Boolean wheelchairAccessible,
                                        Boolean sobrietyRequired, String populationType) {
    }

    public sealed interface DeactivationResult {
        record Success(Shelter shelter, int cancelledHolds) implements DeactivationResult {}
        record ConfirmationRequired(int pendingDvReferrals) implements DeactivationResult {}
    }

    private final ShelterRepository shelterRepository;
    private final ShelterConstraintsRepository constraintsRepository;
    private final AvailabilityService availabilityService;
    private final TenantService tenantService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantScopedCacheService cacheService;
    private final ReservationService reservationService;
    private final ReferralTokenService referralTokenService;
    private final NotificationPersistenceService notificationPersistenceService;
    private final UserService userService;

    public ShelterService(ShelterRepository shelterRepository,
                          ShelterConstraintsRepository constraintsRepository,
                          @Lazy AvailabilityService availabilityService,
                          TenantService tenantService,
                          ObjectMapper objectMapper,
                          JdbcTemplate jdbcTemplate,
                          ApplicationEventPublisher eventPublisher,
                          TenantScopedCacheService cacheService,
                          @Lazy ReservationService reservationService,
                          @Lazy ReferralTokenService referralTokenService,
                          NotificationPersistenceService notificationPersistenceService,
                          UserService userService) {
        this.shelterRepository = shelterRepository;
        this.constraintsRepository = constraintsRepository;
        this.availabilityService = availabilityService;
        this.tenantService = tenantService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.cacheService = cacheService;
        this.reservationService = reservationService;
        this.referralTokenService = referralTokenService;
        this.notificationPersistenceService = notificationPersistenceService;
        this.userService = userService;
    }

    /**
     * Read the per-tenant {@code active_counties} list from {@code tenant.config}
     * (transitional-reentry-support task 4.3 / design D3 H2 revision). Returns
     * an empty list when:
     * <ul>
     *   <li>Tenant config is null or empty (defensive fallback).</li>
     *   <li>The {@code active_counties} key is absent (means "use NC defaults"
     *       — caller decides how to apply that fallback; this method just
     *       returns the literal stored list).</li>
     *   <li>The {@code active_counties} key is explicitly {@code []} (means
     *       "validation disabled" — caller distinguishes this from "absent"
     *       via a separate signal if needed).</li>
     * </ul>
     *
     * <p>Validation policy: county is valid if (a) {@code active_counties} is
     * empty / absent (validation disabled), OR (b) the supplied county string
     * appears in the list. {@code null} county is always valid (no constraint
     * on null).
     */
    /**
     * Read the resolved {@code active_counties} list for a tenant — the list
     * a UI dropdown should populate from. Mirrors {@link #isValidCounty} on
     * the four-branch state machine but RETURNS the list rather than
     * answering yes/no for a single value:
     * <ul>
     *   <li>{@code active_counties} key absent → return
     *       {@link org.fabt.shelter.county.NcCountyDefaults#COUNTIES}
     *       (NC defaults — D3 fallback).</li>
     *   <li>{@code active_counties} explicitly {@code []} → return empty
     *       list (validation explicitly disabled — UI should render a
     *       free-text county input instead of a dropdown).</li>
     *   <li>{@code active_counties} non-empty → return that list verbatim.</li>
     *   <li>Tenant absent / parse failure → fall back to NC defaults
     *       (loud failure would block legitimate UI loads).</li>
     * </ul>
     *
     * <p>transitional-reentry-support slice 4 prereq, warroom H1: the
     * existing {@code GET /api/v1/tenants/{tenantId}/config} endpoint is
     * COC_ADMIN-only, so an OUTREACH_WORKER hitting that endpoint to
     * populate the bed-search county filter dropdown got 403. The new
     * {@code GET /api/v1/active-counties} endpoint reads via this method
     * and is authorized to any authenticated tenant member.
     */
    public List<String> getActiveCounties(UUID tenantId) {
        try {
            return tenantService.findById(tenantId)
                    .filter(t -> t.getConfig() != null && t.getConfig().value() != null)
                    .map(t -> {
                        try {
                            JsonNode node = objectMapper.readTree(t.getConfig().value());
                            JsonNode active = node.get("active_counties");
                            if (active == null || !active.isArray()) {
                                return org.fabt.shelter.county.NcCountyDefaults.COUNTIES;
                            }
                            if (active.isEmpty()) {
                                return java.util.Collections.<String>emptyList();
                            }
                            List<String> out = new ArrayList<>();
                            for (JsonNode entry : active) {
                                if (entry.isTextual()) out.add(entry.asText());
                            }
                            return out;
                        } catch (tools.jackson.core.JacksonException e) {
                            log.warn("Failed to parse active_counties; falling back to NC defaults: {}",
                                e.getMessage());
                            return org.fabt.shelter.county.NcCountyDefaults.COUNTIES;
                        }
                    })
                    .orElse(org.fabt.shelter.county.NcCountyDefaults.COUNTIES);
        } catch (Exception e) {
            log.warn("Failed to read active_counties; falling back to NC defaults: {}", e.getMessage());
            return org.fabt.shelter.county.NcCountyDefaults.COUNTIES;
        }
    }

    public boolean isValidCounty(UUID tenantId, String county) {
        if (county == null) return true;
        try {
            return tenantService.findById(tenantId)
                    .filter(t -> t.getConfig() != null && t.getConfig().value() != null)
                    .map(t -> {
                        try {
                            JsonNode node = objectMapper.readTree(t.getConfig().value());
                            JsonNode active = node.get("active_counties");
                            if (active == null || !active.isArray()) {
                                // Key absent — fall back to NC default list.
                                return org.fabt.shelter.county.NcCountyDefaults.COUNTIES.contains(county);
                            }
                            if (active.isEmpty()) {
                                // Explicit empty array — validation disabled, accept any.
                                return true;
                            }
                            for (JsonNode entry : active) {
                                if (entry.isTextual() && entry.asText().equals(county)) return true;
                            }
                            return false;
                        } catch (tools.jackson.core.JacksonException e) {
                            log.warn("Failed to parse active_counties from tenant config; "
                                + "falling back to NC defaults: {}", e.getMessage());
                            return org.fabt.shelter.county.NcCountyDefaults.COUNTIES.contains(county);
                        }
                    })
                    .orElse(org.fabt.shelter.county.NcCountyDefaults.COUNTIES.contains(county));
        } catch (Exception e) {
            log.warn("Failed to read active_counties from tenant config; falling back to NC defaults: {}",
                e.getMessage());
            return org.fabt.shelter.county.NcCountyDefaults.COUNTIES.contains(county);
        }
    }

    /**
     * Read the DV address visibility policy from tenant config.
     */
    public DvAddressPolicy getDvAddressPolicy(UUID tenantId) {
        try {
            return tenantService.findById(tenantId)
                    .filter(t -> t.getConfig() != null && t.getConfig().value() != null)
                    .map(t -> {
                        try {
                            JsonNode node = objectMapper.readTree(t.getConfig().value());
                            JsonNode policy = node.get("dv_address_visibility");
                            return policy != null ? DvAddressPolicy.fromString(policy.asText()) : DvAddressPolicy.ADMIN_AND_ASSIGNED;
                        } catch (tools.jackson.core.JacksonException e) {
                            log.warn("Failed to read DV address policy from tenant config, using default: {}", e.getMessage());
                            return DvAddressPolicy.ADMIN_AND_ASSIGNED;
                        }
                    })
                    .orElse(DvAddressPolicy.ADMIN_AND_ASSIGNED);
        } catch (Exception e) {
            log.warn("Failed to read DV address policy from tenant config, using default: {}", e.getMessage());
            return DvAddressPolicy.ADMIN_AND_ASSIGNED;
        }
    }

    @Transactional
    public Shelter create(CreateShelterRequest req) {
        UUID tenantId = TenantContext.getTenantId();

        // Validate population types in constraints
        if (req.constraints() != null && req.constraints().populationTypesServed() != null) {
            validatePopulationTypes(req.constraints().populationTypesServed());
        }

        // Validate population types in capacities
        if (req.capacities() != null) {
            for (var cap : req.capacities()) {
                validatePopulationType(cap.populationType());
            }
        }

        // Create shelter — ID left null for INSERT (Lesson 64)
        Shelter shelter = new Shelter();
        shelter.setTenantId(tenantId);
        shelter.setName(req.name());
        shelter.setAddressStreet(req.addressStreet());
        shelter.setAddressCity(req.addressCity());
        shelter.setAddressState(req.addressState());
        shelter.setAddressZip(req.addressZip());
        shelter.setPhone(req.phone());
        shelter.setLatitude(req.latitude());
        shelter.setLongitude(req.longitude());
        shelter.setDvShelter(req.dvShelter());
        // V91 lockstep + task 5.4 (slice 2D H2): dvShelter dominates the
        // shelter_type write so the DB CHECK (shelter_dv_implies_dv_type)
        // can never reject the INSERT. Three cases:
        //   - dvShelter=true → force shelter_type=DV (ignore an inconsistent
        //     explicit shelterType — clearer than 400-on-mismatch and the
        //     RLS-meaningful value is dvShelter).
        //   - dvShelter=false + caller supplied shelterType=DV → reject
        //     explicitly so the operator gets a meaningful 400 instead of
        //     a confusing CHECK-constraint failure at flush time.
        //   - dvShelter=false + null shelterType → entity default
        //     (EMERGENCY).
        //   - dvShelter=false + non-null non-DV shelterType → use it.
        if (req.dvShelter()) {
            shelter.setShelterType(ShelterType.DV);
        } else if (req.shelterType() == ShelterType.DV) {
            throw new IllegalArgumentException(
                "shelterType=DV requires dvShelter=true (V91 lockstep)");
        } else if (req.shelterType() != null) {
            shelter.setShelterType(req.shelterType());
        } else {
            shelter.setShelterType(ShelterType.EMERGENCY);
        }
        // transitional-reentry-support task 4.3 (slice 2B): persist county /
        // requiresVerificationCall / eligibilityCriteria from the request DTO.
        if (req.county() != null) {
            if (!isValidCounty(tenantId, req.county())) {
                throw new IllegalArgumentException(
                    "Invalid county '" + req.county() + "' for tenant; not in active_counties");
            }
            shelter.setCounty(req.county());
        }
        if (req.requiresVerificationCall() != null) {
            shelter.setRequiresVerificationCall(req.requiresVerificationCall());
        }
        shelter.setActive(true);
        shelter.setCreatedAt(Instant.now());
        shelter.setUpdatedAt(Instant.now());

        Shelter saved = shelterRepository.save(shelter);

        // Save constraints if provided
        if (req.constraints() != null) {
            ShelterConstraints constraints = mapConstraints(saved.getId(), req.constraints());
            constraintsRepository.save(constraints);
        }

        // Write initial capacity as bed_availability snapshots (single source of truth — D10)
        // Issue #65: use cap.bedsOccupied() instead of hardcoded 0 so CSV import
        // can set current occupancy at onboarding time.
        if (req.capacities() != null) {
            for (var cap : req.capacities()) {
                availabilityService.createSnapshot(
                        saved.getId(), cap.populationType(), cap.bedsTotal(),
                        cap.bedsOccupied(), 0, true, null, "shelter-create");
            }
        }

        evictTenantShelterCaches(tenantId);
        return saved;
    }

    /**
     * Bed search caches availability keyed by tenant. Shelter create/update/delete changes which
     * shelters appear (e.g. {@code active}) — evict so the next search is consistent (Elena Vasquez).
     */
    private void evictTenantShelterCaches(UUID tenantId) {
        // Per D-4.b-2 + D-4.b-3: caller-side tenantId stripped; the wrapper
        // sources the tenant from TenantContext. "latest" matches the
        // singleton-per-tenant logical key used by BedSearchService.doSearch +
        // AvailabilityService.createSnapshot. The tenantId parameter is retained
        // in the signature for call-site documentation; the wrapper validates
        // it matches TenantContext.getTenantId() implicitly via requireTenantContext.
        // D-4.b-3 rejects refactoring to invalidateTenant(tenantId): would amplify
        // evicts 5.5× + pollute TENANT_CACHE_INVALIDATED audit surface.
        cacheService.evict(CacheNames.SHELTER_AVAILABILITY, "latest");
        // Evict-only today — see D-4.b-3 orphan-cache posture (design-c-cache-isolation.md).
        // SHELTER_LIST has no production put-site; keep the evict as defensive posture.
        cacheService.evict(CacheNames.SHELTER_LIST, "latest");
    }

    /**
     * Backward-compatible overload for callers without actor context (e.g., import service).
     */
    @Transactional
    public Shelter update(UUID id, UpdateShelterRequest req) {
        return update(id, req, null);
    }

    @Transactional
    public Shelter update(UUID id, UpdateShelterRequest req, UUID actorUserId) {
        UUID tenantId = TenantContext.getTenantId();

        Shelter shelter = shelterRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + id));

        // Validate population types in constraints
        if (req.constraints() != null && req.constraints().populationTypesServed() != null) {
            validatePopulationTypes(req.constraints().populationTypesServed());
        }

        // Validate population types in capacities
        if (req.capacities() != null) {
            for (var cap : req.capacities()) {
                validatePopulationType(cap.populationType());
            }
        }

        // Track DV-sensitive changes for audit logging
        boolean dvFlagChanged = req.dvShelter() != null && req.dvShelter() != shelter.isDvShelter();
        boolean addressChanged = shelter.isDvShelter() && isAddressChanging(shelter, req);
        String oldDvFlag = String.valueOf(shelter.isDvShelter());
        String oldAddress = formatAddress(shelter);

        if (req.name() != null) shelter.setName(req.name());
        if (req.addressStreet() != null) shelter.setAddressStreet(req.addressStreet());
        if (req.addressCity() != null) shelter.setAddressCity(req.addressCity());
        if (req.addressState() != null) shelter.setAddressState(req.addressState());
        if (req.addressZip() != null) shelter.setAddressZip(req.addressZip());
        if (req.phone() != null) shelter.setPhone(req.phone());
        if (req.latitude() != null) shelter.setLatitude(req.latitude());
        if (req.longitude() != null) shelter.setLongitude(req.longitude());
        if (req.dvShelter() != null) {
            shelter.setDvShelter(req.dvShelter());
            // V91 lockstep — see create() comment. dvShelter dominates: when
            // it flips to true, shelter_type goes to DV; when it flips to
            // false, shelter_type resets to EMERGENCY (or the explicit
            // shelterType from this same PATCH if non-null and non-DV).
            // Without the same-transaction flip the CHECK rejects mid-update.
            if (req.dvShelter()) {
                shelter.setShelterType(ShelterType.DV);
            } else if (req.shelterType() == ShelterType.DV) {
                throw new IllegalArgumentException(
                    "shelterType=DV requires dvShelter=true (V91 lockstep)");
            } else if (req.shelterType() != null) {
                shelter.setShelterType(req.shelterType());
            } else {
                shelter.setShelterType(ShelterType.EMERGENCY);
            }
        } else if (req.shelterType() != null) {
            // dvShelter unchanged in this PATCH; honor an explicit shelterType
            // change subject to the same lockstep guard.
            if (req.shelterType() == ShelterType.DV && !shelter.isDvShelter()) {
                throw new IllegalArgumentException(
                    "shelterType=DV requires dvShelter=true (V91 lockstep)");
            }
            if (req.shelterType() != ShelterType.DV || shelter.isDvShelter()) {
                shelter.setShelterType(req.shelterType());
            }
        }
        // transitional-reentry-support task 4.3 (slice 2B): PATCH semantics —
        // null req fields leave existing entity values unchanged.
        if (req.county() != null) {
            if (!isValidCounty(tenantId, req.county())) {
                throw new IllegalArgumentException(
                    "Invalid county '" + req.county() + "' for tenant; not in active_counties");
            }
            shelter.setCounty(req.county());
        }
        if (req.requiresVerificationCall() != null) {
            shelter.setRequiresVerificationCall(req.requiresVerificationCall());
        }
        shelter.setUpdatedAt(Instant.now());

        Shelter saved = shelterRepository.save(shelter);

        // Update constraints if provided
        if (req.constraints() != null) {
            ShelterConstraints constraints = mapConstraints(saved.getId(), req.constraints());
            // Check if constraints already exist — if so, mark as not new (UPDATE vs INSERT)
            if (constraintsRepository.findById(saved.getId()).isPresent()) {
                constraints.markNotNew();
            }
            constraintsRepository.save(constraints);
        }

        // Update capacity via new availability snapshots — preserves current occupied/onHold (D10)
        if (req.capacities() != null) {
            // Get latest snapshots to preserve operational data (occupied, onHold)
            List<AvailabilitySnapshot> currentSnapshots = availabilityService.getLatestByShelterId(saved.getId());
            java.util.Map<String, AvailabilitySnapshot> snapshotByType = currentSnapshots.stream()
                    .collect(java.util.stream.Collectors.toMap(AvailabilitySnapshot::populationType, s -> s));

            for (var cap : req.capacities()) {
                AvailabilitySnapshot existing = snapshotByType.get(cap.populationType());
                // Issue #65: if the DTO provides bedsOccupied (from CSV import),
                // use it; otherwise preserve the existing operational value.
                int occupied = cap.bedsOccupied() > 0 ? cap.bedsOccupied()
                        : (existing != null ? existing.bedsOccupied() : 0);
                int onHold = existing != null ? existing.bedsOnHold() : 0;
                boolean accepting = existing != null ? existing.acceptingNewGuests() : true;
                availabilityService.createSnapshot(
                        saved.getId(), cap.populationType(), cap.bedsTotal(),
                        occupied, onHold, accepting, null, "shelter-update");
            }
        }

        // Audit: DV flag changes (T-3 — elevated visibility)
        if (dvFlagChanged && actorUserId != null) {
            eventPublisher.publishEvent(new AuditEventRecord(
                    actorUserId, null, AuditEventType.SHELTER_DV_FLAG_CHANGED,
                    java.util.Map.of("shelterId", saved.getId(), "shelterName", saved.getName(),
                            "oldValue", oldDvFlag, "newValue", String.valueOf(saved.isDvShelter())),
                    null));
        }

        // Audit: DV shelter address changes (T-2 — old/new values)
        if (addressChanged && actorUserId != null) {
            eventPublisher.publishEvent(new AuditEventRecord(
                    actorUserId, null, AuditEventType.DV_SHELTER_ADDRESS_CHANGED,
                    java.util.Map.of("shelterId", saved.getId(), "shelterName", saved.getName(),
                            "oldAddress", oldAddress, "newAddress", formatAddress(saved)),
                    null));
        }

        evictTenantShelterCaches(tenantId);
        return saved;
    }

    private boolean isAddressChanging(Shelter shelter, UpdateShelterRequest req) {
        return (req.addressStreet() != null && !req.addressStreet().equals(shelter.getAddressStreet()))
                || (req.addressCity() != null && !req.addressCity().equals(shelter.getAddressCity()))
                || (req.addressState() != null && !req.addressState().equals(shelter.getAddressState()))
                || (req.addressZip() != null && !req.addressZip().equals(shelter.getAddressZip()));
    }

    private String formatAddress(Shelter shelter) {
        return String.join(", ",
                shelter.getAddressStreet() != null ? shelter.getAddressStreet() : "",
                shelter.getAddressCity() != null ? shelter.getAddressCity() : "",
                shelter.getAddressState() != null ? shelter.getAddressState() : "",
                shelter.getAddressZip() != null ? shelter.getAddressZip() : "");
    }

    @Transactional(readOnly = true)
    public Optional<Shelter> findById(UUID id) {
        UUID tenantId = TenantContext.getTenantId();
        return shelterRepository.findByTenantIdAndId(tenantId, id);
    }

    /**
     * Batch-fetch shelters by ID, restricted to current tenant (Sam Okafor optimization).
     */
    @Transactional(readOnly = true)
    public List<Shelter> findAllById(Set<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        UUID tenantId = TenantContext.getTenantId();
        // Iterable cast for CrudRepository.findAllById
        List<Shelter> all = (List<Shelter>) shelterRepository.findAllById(ids);
        return all.stream()
                .filter(s -> s.getTenantId().equals(tenantId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Shelter> findByTenantId() {
        UUID tenantId = TenantContext.getTenantId();
        return shelterRepository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public List<Shelter> findFiltered(ShelterFilterCriteria criteria) {
        UUID tenantId = TenantContext.getTenantId();

        StringBuilder sql = new StringBuilder(
                "SELECT s.* FROM shelter s JOIN shelter_constraints sc ON s.id = sc.shelter_id WHERE s.tenant_id = ? AND s.active = TRUE");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (criteria.petsAllowed() != null) {
            sql.append(" AND sc.pets_allowed = ?");
            params.add(criteria.petsAllowed());
        }
        if (criteria.wheelchairAccessible() != null) {
            sql.append(" AND sc.wheelchair_accessible = ?");
            params.add(criteria.wheelchairAccessible());
        }
        if (criteria.sobrietyRequired() != null) {
            sql.append(" AND sc.sobriety_required = ?");
            params.add(criteria.sobrietyRequired());
        }
        if (criteria.populationType() != null) {
            validatePopulationType(criteria.populationType());
            sql.append(" AND ? = ANY(sc.population_types_served)");
            params.add(criteria.populationType());
        }

        sql.append(" ORDER BY s.name");

        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> {
                    Shelter shelter = new Shelter();
                    shelter.setId(rs.getObject("id", UUID.class));
                    shelter.setTenantId(rs.getObject("tenant_id", UUID.class));
                    shelter.setName(rs.getString("name"));
                    shelter.setAddressStreet(rs.getString("address_street"));
                    shelter.setAddressCity(rs.getString("address_city"));
                    shelter.setAddressState(rs.getString("address_state"));
                    shelter.setAddressZip(rs.getString("address_zip"));
                    shelter.setPhone(rs.getString("phone"));
                    shelter.setLatitude(rs.getObject("latitude", Double.class));
                    shelter.setLongitude(rs.getObject("longitude", Double.class));
                    shelter.setDvShelter(rs.getBoolean("dv_shelter"));
                    shelter.setActive(rs.getBoolean("active"));
                    shelter.setCreatedAt(rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant() : null);
                    shelter.setUpdatedAt(rs.getTimestamp("updated_at") != null
                            ? rs.getTimestamp("updated_at").toInstant() : null);
                    return shelter;
                },
                params.toArray()
        );
    }

    @Transactional
    public void delete(UUID id) {
        UUID tenantId = TenantContext.getTenantId();

        Shelter shelter = shelterRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + id));

        // bed_availability rows have ON DELETE CASCADE via shelter FK — no manual cleanup needed
        constraintsRepository.deleteById(shelter.getId());
        shelterRepository.delete(shelter);
        evictTenantShelterCaches(tenantId);
    }

    // NOT @Transactional — TenantContext.callWithContext MUST wrap the DB operations.
    // Same pattern as ShelterImportService.importShelters(): @Transactional acquires the
    // connection before callWithContext sets dvAccess=true, causing RLS failures on DV
    // shelters. The inner doDeactivate() is @Transactional for atomicity. Portfolio #60.
    public DeactivationResult deactivate(UUID shelterId, DeactivationReason reason,
                                          boolean confirmDv, UUID actorUserId) {
        UUID tenantId = TenantContext.getTenantId();
        return TenantContext.callWithContext(tenantId, true,
                () -> doDeactivate(tenantId, shelterId, reason, confirmDv, actorUserId));
    }

    @Transactional
    protected DeactivationResult doDeactivate(UUID tenantId, UUID shelterId,
                                               DeactivationReason reason, boolean confirmDv,
                                               UUID actorUserId) {
        Shelter shelter = shelterRepository.findByTenantIdAndId(tenantId, shelterId)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + shelterId));

        if (!shelter.isActive()) {
            throw new IllegalStateException("Shelter is already inactive");
        }

        // DV safety gate: check for pending referrals
        if (shelter.isDvShelter() && !confirmDv) {
            int pendingCount = referralTokenService.countPendingByShelterId(shelterId);
            if (pendingCount > 0) {
                return new DeactivationResult.ConfirmationRequired(pendingCount);
            }
        }

        // Set deactivation metadata
        shelter.setActive(false);
        shelter.setDeactivatedAt(Instant.now());
        shelter.setDeactivatedBy(actorUserId);
        shelter.setDeactivationReason(reason.name());
        shelterRepository.save(shelter);

        // Cancel all holds and notify outreach workers
        int cancelledHolds = cancelHoldsForShelter(shelter, tenantId);

        // Publish audit event
        eventPublisher.publishEvent(new AuditEventRecord(
                actorUserId, null, AuditEventType.SHELTER_DEACTIVATED,
                Map.of("shelterId", shelterId, "shelterName", shelter.getName(),
                        "deactivationReason", reason.name(),
                        "cancelledHolds", cancelledHolds),
                null));

        // DV event broadcast: notify dvAccess users that a DV shelter was deactivated.
        // Restricted to dvAccess=true users per VAWA address confidentiality.
        // Notification text intentionally omits shelter address.
        if (shelter.isDvShelter()) {
            List<UUID> dvUserIds = userService.findDvAccessUserIds(tenantId);
            try {
                // No address in payload — only name, reason, and shelterId (VAWA safety;
                // shelterId is an opaque UUID, not a leak per Marcus Webb war room 2026-04-13).
                // shelterId enables notification deep-linking (Issue #106 / notification-deep-linking).
                String broadcastPayload = objectMapper.writeValueAsString(
                        Map.of("shelterId", shelterId.toString(),
                                "shelterName", shelter.getName(),
                                "reason", reason.name()));
                notificationPersistenceService.sendToAll(dvUserIds,
                        "SHELTER_DEACTIVATED", "CRITICAL", broadcastPayload);
            } catch (tools.jackson.core.JacksonException e) {
                log.error("Failed to serialize DV shelter deactivation broadcast", e);
            }
        }

        evictTenantShelterCaches(tenantId);
        log.info("Shelter {} deactivated by {} with reason {} ({} holds cancelled)",
                shelterId, actorUserId, reason, cancelledHolds);
        return new DeactivationResult.Success(shelter, cancelledHolds);
    }

    /**
     * Cancel all HELD reservations for a shelter and notify outreach workers.
     * Delegates cancellation + snapshot recompute to ReservationService (modular boundary).
     * Notification is handled here because it's a shelter-module concern.
     */
    private int cancelHoldsForShelter(Shelter shelter, UUID tenantId) {
        List<CancelledHoldSummary> cancelled = reservationService.cancelHeldForShelterDeactivation(shelter.getId());

        // Notify each affected outreach worker (always — regardless of dvAccess, per spec W-3)
        for (CancelledHoldSummary hold : cancelled) {
            try {
                // reservationId enables deep-linking to /outreach/my-holds?reservationId=X
                // per notification-deep-linking OpenSpec (Issue #106). shelterId included so
                // the outreach worker can see which shelter's hold was cancelled.
                String payload = objectMapper.writeValueAsString(
                        Map.of("reservationId", hold.reservationId().toString(),
                                "shelterId", shelter.getId().toString(),
                                "shelterName", shelter.getName()));
                notificationPersistenceService.send(
                        hold.userId(),
                        "HOLD_CANCELLED_SHELTER_DEACTIVATED", "WARNING", payload);
            } catch (tools.jackson.core.JacksonException e) {
                log.error("Failed to serialize hold cancellation notification for reservation {}",
                        hold.reservationId(), e);
            }

            log.info("Cancelled hold {} for user {} due to shelter deactivation",
                    hold.reservationId(), hold.userId());
        }

        return cancelled.size();
    }

    // NOT @Transactional — same callWithContext ordering as deactivate(). DV shelter
    // reactivation needs dvAccess=true to find the shelter through RLS. Portfolio #60.
    public Shelter reactivate(UUID shelterId, UUID actorUserId) {
        UUID tenantId = TenantContext.getTenantId();
        return TenantContext.callWithContext(tenantId, true,
                () -> doReactivate(tenantId, shelterId, actorUserId));
    }

    @Transactional
    protected Shelter doReactivate(UUID tenantId, UUID shelterId, UUID actorUserId) {
        Shelter shelter = shelterRepository.findByTenantIdAndId(tenantId, shelterId)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + shelterId));

        if (shelter.isActive()) {
            throw new IllegalStateException("Shelter is already active");
        }

        String previousReason = shelter.getDeactivationReason();

        // Clear deactivation metadata and reactivate
        shelter.setActive(true);
        shelter.setDeactivatedAt(null);
        shelter.setDeactivatedBy(null);
        shelter.setDeactivationReason(null);
        shelterRepository.save(shelter);

        // Publish audit event (include previous reason for audit trail)
        eventPublisher.publishEvent(new AuditEventRecord(
                actorUserId, null, AuditEventType.SHELTER_REACTIVATED,
                Map.of("shelterId", shelterId, "shelterName", shelter.getName(),
                        "previousDeactivationReason", previousReason != null ? previousReason : "unknown"),
                null));

        evictTenantShelterCaches(tenantId);
        log.info("Shelter {} reactivated by {}", shelterId, actorUserId);
        return shelter;
    }

    @Transactional(readOnly = true)
    public ShelterDetail getDetail(UUID id) {
        UUID tenantId = TenantContext.getTenantId();

        Shelter shelter = shelterRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new NoSuchElementException("Shelter not found: " + id));

        ShelterConstraints constraints = constraintsRepository.findById(shelter.getId()).orElse(null);

        // Capacity is derived from latest bed_availability snapshots (single source of truth — D10)
        List<AvailabilitySnapshot> snapshots = availabilityService.getLatestByShelterId(shelter.getId());
        List<CapacityFromAvailability> capacities = snapshots.stream()
                .map(s -> new CapacityFromAvailability(s.populationType(), s.bedsTotal()))
                .toList();

        // Availability is further enriched by the controller with AvailabilityDto data
        return new ShelterDetail(shelter, constraints, capacities, null);
    }

    private ShelterConstraints mapConstraints(UUID shelterId, ShelterConstraintsDto dto) {
        ShelterConstraints constraints = new ShelterConstraints();
        constraints.setShelterId(shelterId);
        constraints.setSobrietyRequired(dto.sobrietyRequired());
        constraints.setIdRequired(dto.idRequired());
        constraints.setReferralRequired(dto.referralRequired());
        constraints.setPetsAllowed(dto.petsAllowed());
        constraints.setWheelchairAccessible(dto.wheelchairAccessible());
        if (dto.curfewTime() != null && !dto.curfewTime().isBlank()) {
            constraints.setCurfewTime(LocalTime.parse(dto.curfewTime()));
        }
        constraints.setMaxStayDays(dto.maxStayDays());
        // Null-safe: DB column is NOT NULL DEFAULT '{}'. Spring Data JDBC always
        // sends the Java field value, overriding the DB default. If the DTO has null
        // (e.g., import without populationTypesServed column), use empty array.
        // Alex Chen + Elena Vasquez: fix at the service layer to protect ALL callers.
        constraints.setPopulationTypesServed(
                dto.populationTypesServed() != null ? dto.populationTypesServed() : new String[0]);
        // transitional-reentry-support task 4.3 (slice 2B): persist
        // eligibilityCriteria JSONB. Null-safe: null DTO field → null entity
        // field → null DB column (V92 column is nullable).
        constraints.setEligibilityCriteria(dto.eligibilityCriteria());
        return constraints;
    }

    private void validatePopulationTypes(String[] types) {
        for (String type : types) {
            validatePopulationType(type);
        }
    }

    private void validatePopulationType(String type) {
        try {
            PopulationType.valueOf(type);
        } catch (IllegalArgumentException e) {
            String valid = Arrays.stream(PopulationType.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Invalid population type: '" + type + "'. Valid types are: " + valid);
        }
    }
}
