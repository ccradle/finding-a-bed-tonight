CREATE TABLE coordinator_assignment (
    user_id    UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    shelter_id UUID NOT NULL REFERENCES shelter(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, shelter_id)
);
