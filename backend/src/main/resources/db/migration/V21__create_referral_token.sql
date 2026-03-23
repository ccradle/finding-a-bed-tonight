-- V21: DV Opaque Referral Token
--
-- Privacy-preserving referral system for domestic violence shelters.
-- Tokens contain zero client PII (VAWA 34 U.S.C. 12291(b)(2) compliant).
-- Terminal-state tokens are hard-deleted by ReferralTokenPurgeService within 24 hours.

CREATE TABLE referral_token (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shelter_id          UUID NOT NULL REFERENCES shelter(id) ON DELETE CASCADE,
    tenant_id           UUID NOT NULL REFERENCES tenant(id),
    referring_user_id   UUID NOT NULL REFERENCES app_user(id),
    household_size      INTEGER NOT NULL DEFAULT 1,
    population_type     VARCHAR(50) NOT NULL,
    urgency             VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    special_needs       VARCHAR(500),
    callback_number     VARCHAR(50) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    responded_at        TIMESTAMPTZ,
    responded_by        UUID REFERENCES app_user(id),
    expires_at          TIMESTAMPTZ NOT NULL,
    rejection_reason    VARCHAR(500),

    CONSTRAINT chk_referral_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT chk_referral_urgency CHECK (urgency IN ('STANDARD', 'URGENT', 'EMERGENCY')),
    CONSTRAINT chk_household_size CHECK (household_size BETWEEN 1 AND 20)
);

-- Pending tokens for a shelter (coordinator screening view)
CREATE INDEX idx_referral_token_shelter_status ON referral_token(shelter_id, status);

-- Worker's "My Referrals" view
CREATE INDEX idx_referral_token_user ON referral_token(referring_user_id, status);

-- One PENDING token per worker per shelter (prevents duplicate requests)
CREATE UNIQUE INDEX uq_referral_token_pending ON referral_token(referring_user_id, shelter_id)
    WHERE status = 'PENDING';

-- Purge service: find terminal tokens older than 24 hours
CREATE INDEX idx_referral_token_purge ON referral_token(status, responded_at)
    WHERE status IN ('ACCEPTED', 'REJECTED');

CREATE INDEX idx_referral_token_expired ON referral_token(status, expires_at)
    WHERE status = 'EXPIRED';

-- Row Level Security: inherit DV shelter access via shelter FK join
-- Same pattern as V13 (bed_availability) and V15 (reservation)
ALTER TABLE referral_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE referral_token FORCE ROW LEVEL SECURITY;

CREATE POLICY dv_referral_token_access ON referral_token
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM shelter s
            WHERE s.id = referral_token.shelter_id
        )
    );
