package org.fabt.shelter.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.shelter.domain.Shelter;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ShelterRepository extends CrudRepository<Shelter, UUID> {

    @Query("SELECT * FROM shelter WHERE tenant_id = :tenantId ORDER BY name")
    List<Shelter> findByTenantId(@Param("tenantId") UUID tenantId);

    @Query("SELECT * FROM shelter WHERE tenant_id = :tenantId AND id = :id")
    Optional<Shelter> findByTenantIdAndId(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Query("SELECT * FROM shelter WHERE tenant_id = :tenantId AND name = :name AND address_city = :addressCity")
    Optional<Shelter> findByTenantIdAndNameAndAddressCity(@Param("tenantId") UUID tenantId,
                                                          @Param("name") String name,
                                                          @Param("addressCity") String addressCity);
}
