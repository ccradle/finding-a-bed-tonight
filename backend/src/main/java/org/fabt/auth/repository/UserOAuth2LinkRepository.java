package org.fabt.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.domain.UserOAuth2Link;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface UserOAuth2LinkRepository extends CrudRepository<UserOAuth2Link, UUID> {

    @Query("SELECT * FROM user_oauth2_link WHERE provider_name = :providerName AND external_subject_id = :externalSubjectId")
    Optional<UserOAuth2Link> findByProviderNameAndExternalSubjectId(
            @Param("providerName") String providerName,
            @Param("externalSubjectId") String externalSubjectId);

    @Query("SELECT * FROM user_oauth2_link WHERE user_id = :userId")
    List<UserOAuth2Link> findByUserId(@Param("userId") UUID userId);
}
