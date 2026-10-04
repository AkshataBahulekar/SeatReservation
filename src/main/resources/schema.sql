CREATE TABLE IF NOT EXISTS shows (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INTEGER NOT NULL CHECK (per_user_limit > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(100) NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, show_id)
);
CREATE INDEX IF NOT EXISTS reservations_user_show_idx
    ON reservations(show_id, user_id, status);

CREATE TABLE IF NOT EXISTS seats (
    show_id UUID NOT NULL REFERENCES shows(id),
    seat_label VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('available', 'confirmed')),
    reservation_id UUID CONSTRAINT seats_reservation_id_fkey
        REFERENCES reservations(id) DEFERRABLE INITIALLY DEFERRED,
    PRIMARY KEY (show_id, seat_label),
    CHECK ((status = 'available' AND reservation_id IS NULL)
        OR (status = 'confirmed' AND reservation_id IS NOT NULL))
);
ALTER TABLE seats ALTER CONSTRAINT seats_reservation_id_fkey DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX IF NOT EXISTS seats_reservation_idx ON seats(reservation_id);

CREATE TABLE IF NOT EXISTS reservation_seats (
    reservation_id UUID NOT NULL REFERENCES reservations(id),
    show_id UUID NOT NULL,
    seat_label VARCHAR(40) NOT NULL,
    PRIMARY KEY (reservation_id, seat_label),
    FOREIGN KEY (reservation_id, show_id) REFERENCES reservations(id, show_id),
    FOREIGN KEY (show_id, seat_label) REFERENCES seats(show_id, seat_label)
);

CREATE TABLE IF NOT EXISTS user_show_counts (
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(100) NOT NULL,
    seat_count INTEGER NOT NULL CHECK (seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    user_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_seats TEXT NOT NULL,
    reservation_id UUID REFERENCES reservations(id),
    outcome VARCHAR(40),
    PRIMARY KEY (user_id, idempotency_key)
);
