CREATE TABLE reservation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shelter_id      UUID NOT NULL REFERENCES shelter(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    population_type VARCHAR(50) NOT NULL,
    user_id         UUID NOT NULL REFERENCES app_user(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'HELD',
    expires_at      TIMESTAMPTZ NOT NULL,
    confirmed_at    TIMESTAMPTZ,
    cancelled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    notes           VARCHAR(500),
    CONSTRAINT chk_reservation_status CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED'))
);

-- Active reservations by user (list my holds)
CREATE INDEX idx_reservation_user_status ON reservation(tenant_id, user_id, status);

-- Active holds per shelter/population (for availability calculations)
CREATE INDEX idx_reservation_shelter_status ON reservation(shelter_id, population_type, status);

-- Expiry polling: find HELD reservations past their expires_at
CREATE INDEX idx_reservation_expiry ON reservation(status, expires_at)
    WHERE status = 'HELD';
