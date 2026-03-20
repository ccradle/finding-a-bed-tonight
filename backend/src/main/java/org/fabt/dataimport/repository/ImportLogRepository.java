package org.fabt.dataimport.repository;

import java.util.List;
import java.util.UUID;

import org.fabt.dataimport.domain.ImportLog;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface ImportLogRepository extends CrudRepository<ImportLog, UUID> {

    @Query("SELECT * FROM import_log WHERE tenant_id = :tenantId ORDER BY created_at DESC")
    List<ImportLog> findByTenantId(@Param("tenantId") UUID tenantId);
}
