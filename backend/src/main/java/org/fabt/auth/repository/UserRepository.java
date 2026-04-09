package org.fabt.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.domain.User;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends CrudRepository<User, UUID> {

    @Query("SELECT * FROM app_user WHERE tenant_id = :tenantId AND email = :email")
    Optional<User> findByTenantIdAndEmail(@Param("tenantId") UUID tenantId, @Param("email") String email);

    List<User> findByTenantId(UUID tenantId);

    @Query("SELECT * FROM app_user WHERE tenant_id = :tenantId AND dv_access = true AND status = 'ACTIVE' AND roles @> ARRAY[:role]::text[]")
    List<User> findActiveByTenantIdAndDvAccessAndRole(@Param("tenantId") UUID tenantId, @Param("role") String role);

    @Query("SELECT * FROM app_user WHERE tenant_id = :tenantId AND status = 'ACTIVE' AND roles @> ARRAY[:role]::text[]")
    List<User> findActiveByTenantIdAndRole(@Param("tenantId") UUID tenantId, @Param("role") String role);
}
