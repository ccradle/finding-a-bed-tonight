package org.fabt.auth.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.domain.User;
import org.fabt.auth.domain.UserOAuth2Link;
import org.fabt.auth.repository.UserOAuth2LinkRepository;
import org.fabt.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links OAuth2 identities to pre-created FABT users (Option C: no auto-provisioning).
 * After successful OAuth2 authentication, this service either:
 * 1. Finds an existing link and issues tokens, or
 * 2. Matches by email, creates a link, and issues tokens, or
 * 3. Rejects the login if no pre-created user matches.
 */
@Service
public class OAuth2AccountLinkService {

    private final UserOAuth2LinkRepository linkRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public OAuth2AccountLinkService(UserOAuth2LinkRepository linkRepository,
                                     UserRepository userRepository,
                                     JwtService jwtService) {
        this.linkRepository = linkRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    /**
     * Attempts to link an OAuth2 identity to a pre-created user and issue FABT tokens.
     *
     * @param providerName      the OAuth2 provider (e.g. "google", "microsoft")
     * @param externalSubjectId the "sub" claim from the OAuth2 provider's ID token
     * @param email             the email from the OAuth2 provider
     * @return LinkResult indicating success with tokens or rejection with error message
     */
    @Transactional
    public LinkResult linkOrReject(String providerName, String externalSubjectId,
                                    String email) {
        UUID tenantId = org.fabt.shared.web.TenantContext.getTenantId();
        // 1. Check if an OAuth2 link already exists for this provider + subject
        Optional<UserOAuth2Link> existingLink = linkRepository
                .findByProviderNameAndExternalSubjectId(providerName, externalSubjectId);

        if (existingLink.isPresent()) {
            Optional<User> user = userRepository.findById(existingLink.get().getUserId());
            if (user.isPresent()) {
                String accessToken = jwtService.generateAccessToken(user.get());
                String refreshToken = jwtService.generateRefreshToken(user.get());
                return new LinkResult(true, accessToken, refreshToken, null, user.get().getId());
            }
            // Link exists but user was deleted — fall through to email match
        }

        // 2. No existing link — try to match by tenant + email
        Optional<User> userByEmail = userRepository.findByTenantIdAndEmail(tenantId, email);
        if (userByEmail.isPresent()) {
            User user = userByEmail.get();

            // Create the OAuth2 link
            UserOAuth2Link link = new UserOAuth2Link();
            // ID left null — database generates via gen_random_uuid() (Lesson 64)
            link.setUserId(user.getId());
            link.setProviderName(providerName);
            link.setExternalSubjectId(externalSubjectId);
            link.setLinkedAt(Instant.now());
            linkRepository.save(link);

            String accessToken = jwtService.generateAccessToken(user);
            String refreshToken = jwtService.generateRefreshToken(user);
            return new LinkResult(true, accessToken, refreshToken, null, user.getId());
        }

        // 3. No user matches — reject
        return new LinkResult(false, null, null,
                "No account found for this email. Contact your CoC administrator to be added.",
                null);
    }

    public record LinkResult(boolean success, String accessToken, String refreshToken,
                              String error, UUID userId) {
    }
}
