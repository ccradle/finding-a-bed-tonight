package org.fabt.availability.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.fabt.analytics.service.BedSearchLogger;
import org.fabt.availability.domain.BedAvailability;
import org.fabt.availability.domain.BedSearchRequest;
import org.fabt.availability.domain.BedSearchResult;
import org.fabt.availability.domain.BedSearchResult.ConstraintsSummary;
import org.fabt.availability.domain.BedSearchResult.PopulationAvailability;
import org.fabt.availability.repository.BedAvailabilityRepository;
import io.micrometer.core.instrument.Timer;
import org.fabt.observability.DataFreshness;
import org.fabt.observability.ObservabilityMetrics;
import org.fabt.shared.cache.CacheNames;
import org.fabt.shared.cache.TenantScopedCacheService;
import org.fabt.shared.web.TenantContext;
import org.fabt.shelter.domain.Shelter;
import org.fabt.shelter.domain.ShelterConstraints;
import org.fabt.shelter.repository.ShelterConstraintsRepository;
import org.fabt.shelter.service.ShelterService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BedSearchService {

    public record BedSearchResponse(List<BedSearchResult> results, int totalCount) {}

    private final BedAvailabilityRepository availabilityRepository;
    private final ShelterService shelterService;
    private final ShelterConstraintsRepository constraintsRepository;
    private final TenantScopedCacheService cacheService;
    private final ObservabilityMetrics metrics;
    private final BedSearchLogger bedSearchLogger;

    public BedSearchService(BedAvailabilityRepository availabilityRepository,
                            ShelterService shelterService,
                            ShelterConstraintsRepository constraintsRepository,
                            TenantScopedCacheService cacheService,
                            ObservabilityMetrics metrics,
                            BedSearchLogger bedSearchLogger) {
        this.availabilityRepository = availabilityRepository;
        this.shelterService = shelterService;
        this.constraintsRepository = constraintsRepository;
        this.cacheService = cacheService;
        this.metrics = metrics;
        this.bedSearchLogger = bedSearchLogger;
    }

    @Transactional(readOnly = true)
    public BedSearchResponse search(BedSearchRequest request) {
        return search(request, false);
    }

    @Transactional(readOnly = true)
    public BedSearchResponse search(BedSearchRequest request, boolean surgeActive) {
        String populationType = request.populationType() != null ? request.populationType() : "all";
        Timer.Sample timerSample = Timer.start();
        try {
            BedSearchResponse response = doSearch(request, surgeActive);
            metrics.bedSearchCounter(populationType).increment();
            // Log search for demand analytics (Design D2)
            bedSearchLogger.logSearch(
                    TenantContext.getTenantId(),
                    request.populationType(),
                    response.totalCount());
            return response;
        } finally {
            timerSample.stop(metrics.bedSearchTimer(populationType));
        }
    }

    private BedSearchResponse doSearch(BedSearchRequest request, boolean surgeActive) {
        UUID tenantId = TenantContext.getTenantId();

        // Cache-aside: check cache first, populate on miss from PostgreSQL.
        // Per D-4.b-2: caller-side tenantId prefix stripped — wrapper re-prefixes
        // via its PREFIX_SEPARATOR. The singleton-per-tenant payload uses the
        // "latest" constant per the empty-post-strip convention.
        String cacheKey = "latest";
        @SuppressWarnings("unchecked")
        Optional<List<BedAvailability>> cached = cacheService.get(
                CacheNames.SHELTER_AVAILABILITY, cacheKey, (Class<List<BedAvailability>>) (Class<?>) List.class);
        List<BedAvailability> allAvailability;
        if (cached.isPresent()) {
            allAvailability = cached.get();
        } else {
            allAvailability = availabilityRepository.findLatestByTenantId(tenantId);
            cacheService.put(CacheNames.SHELTER_AVAILABILITY, cacheKey, allAvailability, Duration.ofSeconds(60));
        }

        // Group by shelter
        Map<UUID, List<BedAvailability>> byShelter = new HashMap<>();
        for (BedAvailability ba : allAvailability) {
            byShelter.computeIfAbsent(ba.getShelterId(), k -> new ArrayList<>()).add(ba);
        }

        // Get all shelters for the tenant
        List<Shelter> shelters = shelterService.findByTenantId();

        List<BedSearchResult> results = new ArrayList<>();

        for (Shelter shelter : shelters) {
            if (!shelter.isActive()) {
                continue;
            }
            // Get constraints
            ShelterConstraints constraints = constraintsRepository.findById(shelter.getId()).orElse(null);

            // Apply constraint filters — match the caller's needs against shelter constraints.
            //
            // Amenity fields (petsAllowed, wheelchairAccessible):
            //   true  = "I need this" → exclude shelters that don't have it
            //   null  = no preference
            //
            // Barrier fields (sobrietyRequired, idRequired, referralRequired):
            //   false = "I cannot meet this requirement" → exclude shelters that require it
            //   true  = "I can meet this" or "only show shelters with this requirement"
            //   null  = no preference
            if (request.constraints() != null && constraints != null) {
                if (Boolean.TRUE.equals(request.constraints().petsAllowed())
                        && !constraints.isPetsAllowed()) continue;
                if (Boolean.TRUE.equals(request.constraints().wheelchairAccessible())
                        && !constraints.isWheelchairAccessible()) continue;
                if (Boolean.FALSE.equals(request.constraints().sobrietyRequired())
                        && constraints.isSobrietyRequired()) continue;
                if (Boolean.FALSE.equals(request.constraints().idRequired())
                        && constraints.isIdRequired()) continue;
                if (Boolean.FALSE.equals(request.constraints().referralRequired())
                        && constraints.isReferralRequired()) continue;
            }

            // Get availability for this shelter
            List<BedAvailability> shelterAvail = byShelter.getOrDefault(shelter.getId(), List.of());

            // Filter by population type if specified
            if (request.populationType() != null && !request.populationType().isBlank()) {
                shelterAvail = shelterAvail.stream()
                        .filter(ba -> ba.getPopulationType().equals(request.populationType()))
                        .toList();

                // Also check if shelter serves this population type
                if (constraints != null && constraints.getPopulationTypesServed() != null) {
                    boolean serves = false;
                    for (String pt : constraints.getPopulationTypesServed()) {
                        if (pt.equals(request.populationType())) {
                            serves = true;
                            break;
                        }
                    }
                    if (!serves) continue;
                }
            }

            // Build availability list
            List<PopulationAvailability> popAvail = shelterAvail.stream()
                    .map(ba -> new PopulationAvailability(
                            ba.getPopulationType(),
                            ba.getBedsTotal(),
                            ba.getBedsOccupied(),
                            ba.getBedsOnHold(),
                            ba.getBedsAvailable(),
                            ba.isAcceptingNewGuests(),
                            ba.getOverflowBeds() != null ? ba.getOverflowBeds() : 0
                    ))
                    .toList();

            // Compute data age from most recent snapshot
            Instant latestSnapshot = shelterAvail.stream()
                    .map(BedAvailability::getSnapshotTs)
                    .filter(ts -> ts != null)
                    .max(Instant::compareTo)
                    .orElse(null);

            Long dataAgeSeconds;
            if (latestSnapshot != null) {
                dataAgeSeconds = Duration.between(latestSnapshot, Instant.now()).getSeconds();
            } else if (shelter.getUpdatedAt() != null) {
                dataAgeSeconds = Duration.between(shelter.getUpdatedAt(), Instant.now()).getSeconds();
            } else {
                dataAgeSeconds = null;
            }
            String freshness = DataFreshness.fromAgeSeconds(dataAgeSeconds).name();

            // Build constraints summary
            ConstraintsSummary constraintsSummary = constraints != null
                    ? new ConstraintsSummary(
                    constraints.isPetsAllowed(),
                    constraints.isWheelchairAccessible(),
                    constraints.isSobrietyRequired(),
                    constraints.isIdRequired(),
                    constraints.isReferralRequired()
            )
                    : new ConstraintsSummary(false, false, false, false, false);

            // Redact location data for DV shelters — FVPSA compliance.
            // Address shared verbally during warm handoff only.
            String address = shelter.isDvShelter() ? null : formatAddress(shelter);
            Double lat = shelter.isDvShelter() ? null : shelter.getLatitude();
            Double lng = shelter.isDvShelter() ? null : shelter.getLongitude();

            results.add(new BedSearchResult(
                    shelter.getId(),
                    shelter.getName(),
                    address,
                    shelter.getPhone(),
                    lat,
                    lng,
                    popAvail,
                    dataAgeSeconds,
                    freshness,
                    null, // distanceMiles — placeholder until geo-search change
                    constraintsSummary,
                    surgeActive,
                    shelter.isDvShelter()
            ));
        }

        // Rank: (1) effective_available > 0 first, (2) fewer barriers, (3) effective_available DESC
        // During active surge, effective = bedsAvailable + overflowBeds (temporary capacity counts)
        results.sort(Comparator
                .<BedSearchResult, Integer>comparing(r -> {
                    int totalAvail = r.availability().stream()
                            .mapToInt(a -> a.bedsAvailable() + (surgeActive ? a.overflowBeds() : 0)).sum();
                    return totalAvail > 0 ? 0 : 1; // available first
                })
                .thenComparing(r -> r.constraints().barrierCount())
                .thenComparing(r -> {
                    int totalAvail = r.availability().stream()
                            .mapToInt(a -> a.bedsAvailable() + (surgeActive ? a.overflowBeds() : 0)).sum();
                    return -totalAvail; // descending
                }));

        // Apply limit
        int limit = request.limitOrDefault();
        List<BedSearchResult> limited = results.size() > limit
                ? results.subList(0, limit)
                : results;

        return new BedSearchResponse(limited, results.size());
    }

    private String formatAddress(Shelter shelter) {
        List<String> parts = new ArrayList<>();
        if (shelter.getAddressStreet() != null) parts.add(shelter.getAddressStreet());
        if (shelter.getAddressCity() != null) parts.add(shelter.getAddressCity());
        if (shelter.getAddressState() != null) parts.add(shelter.getAddressState());
        if (shelter.getAddressZip() != null) parts.add(shelter.getAddressZip());
        return String.join(", ", parts);
    }
}
