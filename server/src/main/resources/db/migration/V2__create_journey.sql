CREATE TABLE accounts (
    id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Only hashes of device bearer tokens are stored.
CREATE TABLE device_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    device VARCHAR(8) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT device_tokens_device CHECK (device IN ('CAR', 'PHONE'))
);

-- car_token is held only between a phone claim and the car's next poll.
CREATE TABLE link_codes (
    poll_hash VARCHAR(64) PRIMARY KEY,
    code VARCHAR(6) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    claimed BOOLEAN NOT NULL,
    car_token VARCHAR(64)
);
CREATE INDEX link_codes_code_idx ON link_codes (code);

CREATE TABLE goals (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    achieved_at TIMESTAMP WITH TIME ZONE,
    archived_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT goals_status CHECK (status IN ('ACTIVE', 'ACHIEVED', 'ARCHIVED'))
);
CREATE INDEX goals_account_idx ON goals (account_id, created_at);

CREATE TABLE places (
    id VARCHAR(36) PRIMARY KEY,
    goal_id VARCHAR(36) NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    position INT NOT NULL,
    name VARCHAR(80) NOT NULL,
    address VARCHAR(200) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    poi_id VARCHAR(80),
    poi_sub_id VARCHAR(40),
    poi_provider VARCHAR(40),
    visit_record_id VARCHAR(64),
    visited_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT places_position UNIQUE (goal_id, position)
);

CREATE TABLE drive_records (
    id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    goal_id VARCHAR(36) REFERENCES goals (id) ON DELETE CASCADE,
    place_id VARCHAR(36),
    place_name VARCHAR(80) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE NOT NULL,
    elapsed_seconds BIGINT NOT NULL,
    distance_km DOUBLE PRECISION,
    distance_source VARCHAR(16) NOT NULL,
    status VARCHAR(10) NOT NULL,
    simulated BOOLEAN NOT NULL,
    route_json TEXT NOT NULL,
    title VARCHAR(40),
    card_json TEXT,
    photo_file VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT drive_records_status CHECK (status IN ('COMPLETE', 'PARTIAL')),
    CONSTRAINT drive_records_source CHECK (distance_source IN ('GPS_ESTIMATED', 'VEHICLE', 'NONE')),
    CONSTRAINT drive_records_elapsed CHECK (elapsed_seconds >= 0)
);
CREATE INDEX drive_records_account_idx ON drive_records (account_id, started_at DESC);
