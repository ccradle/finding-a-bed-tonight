package org.fabt.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.domain.ApiKey;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends CrudRepository<ApiKey, UUID> {

    @Query("SELECT * FROM api_key WHERE key_hash = :keyHash AND active = true")
    Optional<ApiKey> findByKeyHashAndActiveTrue(@Param("keyHash") String keyHash);

    List<ApiKey> findByTenantId(UUID tenantId);
}
