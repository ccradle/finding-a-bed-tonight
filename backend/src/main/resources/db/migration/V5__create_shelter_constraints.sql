CREATE TABLE shelter_constraints (
    shelter_id             UUID PRIMARY KEY REFERENCES shelter(id) ON DELETE CASCADE,
    sobriety_required      BOOLEAN NOT NULL DEFAULT FALSE,
    id_required            BOOLEAN NOT NULL DEFAULT FALSE,
    referral_required      BOOLEAN NOT NULL DEFAULT FALSE,
    pets_allowed           BOOLEAN NOT NULL DEFAULT FALSE,
    wheelchair_accessible  BOOLEAN NOT NULL DEFAULT FALSE,
    curfew_time            TIME,
    max_stay_days          INTEGER,
    population_types_served TEXT[] NOT NULL DEFAULT '{}'
);
