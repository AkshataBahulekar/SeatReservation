package org.example.seatreservation.service;

import org.example.seatreservation.api.ApiModels;
import org.example.seatreservation.api.DomainException;
import org.example.seatreservation.observability.ReservationMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReservationService {
    private static final int MAX_SEATS_PER_REQUEST = 50;
    private final NamedParameterJdbcTemplate jdbc;
    private final ReservationMetrics metrics;

    public ReservationService(NamedParameterJdbcTemplate jdbc, ReservationMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Transactional
    public ApiModels.ShowResponse createShow(ApiModels.CreateShowRequest request) {
        List<String> seats = request.seats().stream().distinct().sorted().toList();
        if (seats.size() != request.seats().size()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "duplicate-seat", "Seat labels must be unique");
        }
        if (seats.size() > 100_000) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "too-many-seats", "A show may contain at most 100000 seats");
        }
        if (request.price_paise() > Long.MAX_VALUE / MAX_SEATS_PER_REQUEST) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "price-too-large", "Price is too large for a multi-seat reservation");
        }
        UUID showId = UUID.randomUUID();
        int limit = request.per_user_limit() == null ? 4 : request.per_user_limit();
        jdbc.getJdbcTemplate().update(
                "INSERT INTO shows(id, name, price_paise, per_user_limit) VALUES (?, ?, ?, ?)",
                showId, request.name(), request.price_paise(), limit);
        SqlParameterSource[] rows = seats.stream()
                .map(seat -> new MapSqlParameterSource().addValue("showId", showId).addValue("seat", seat))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO seats(show_id, seat_label, status) VALUES (:showId, :seat, 'available')",
                rows);
        return getShow(showId);
    }

    @Transactional(readOnly = true)
    public ApiModels.ShowResponse getShow(UUID showId) {
        List<Map<String, Object>> rows = jdbc.getJdbcTemplate().queryForList(
                """
                SELECT s.id, s.name, s.price_paise, s.per_user_limit,
                       t.seat_label, t.status
                FROM shows s LEFT JOIN seats t ON t.show_id = s.id
                WHERE s.id = ? ORDER BY t.seat_label COLLATE "C"
                """, showId);
        if (rows.isEmpty()) {
            throw notFoundShow();
        }
        var seats = new ArrayList<ApiModels.SeatState>();
        int available = 0;
        int confirmed = 0;
        for (Map<String, Object> row : rows) {
            String label = (String) row.get("seat_label");
            if (label == null) continue;
            String state = (String) row.get("status");
            seats.add(new ApiModels.SeatState(label, state));
            if ("available".equals(state)) available++;
            else if ("confirmed".equals(state)) confirmed++;
        }
        return new ApiModels.ShowResponse(
                (UUID) rows.get(0).get("id"),
                (String) rows.get(0).get("name"),
                ((Number) rows.get(0).get("price_paise")).longValue(),
                ((Number) rows.get(0).get("per_user_limit")).intValue(),
                seats.size(), available, 0, confirmed, seats);
    }

    @Transactional
    public ReservationResult reserve(UUID showId, String userId, ApiModels.ReserveRequest request) {
        List<String> seats = request.seats().stream().distinct().sorted().toList();
        if (seats.size() != request.seats().size()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "duplicate-seat", "Seat labels must be unique");
        }
        if (seats.size() > MAX_SEATS_PER_REQUEST) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "too-many-seats", "At most 50 seats may be reserved at once");
        }
        String canonicalSeats = showId + "\n" + String.join("\n", seats);
        jdbc.getJdbcTemplate().update(
                """
                INSERT INTO idempotency_keys(user_id, idempotency_key, request_seats)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                """, userId, request.idempotency_key(), canonicalSeats);
        IdempotencyRecord idempotency = jdbc.getJdbcTemplate().queryForObject(
                """
                SELECT request_seats, reservation_id, outcome
                FROM idempotency_keys
                WHERE user_id = ? AND idempotency_key = ?
                FOR UPDATE
                """,
                (row, index) -> new IdempotencyRecord(
                        row.getString("request_seats"),
                        row.getObject("reservation_id", UUID.class),
                        row.getString("outcome")),
                userId, request.idempotency_key());
        if (!idempotency.requestSeats().equals(canonicalSeats)) {
            metrics.declined("idempotency-key-reused");
            return ReservationResult.declined("idempotency-key-reused", "Idempotency key was used with different seats");
        }
        if (idempotency.outcome() != null) {
            metrics.declined("idempotent-replay");
            if (idempotency.reservationId() != null) {
                return new ReservationResult(getReservation(idempotency.reservationId()), null, null, true);
            }
            return ReservationResult.declined(idempotency.outcome(), messageFor(idempotency.outcome()));
        }

        ShowDetails show = findShow(showId);
        if (show == null) {
            throw notFoundShow();
        }
        jdbc.getJdbcTemplate().update(
                """
                INSERT INTO user_show_counts(show_id, user_id, seat_count) VALUES (?, ?, 0)
                ON CONFLICT (show_id, user_id) DO NOTHING
                """, showId, userId);
        int currentCount = jdbc.getJdbcTemplate().queryForObject(
                "SELECT seat_count FROM user_show_counts WHERE show_id = ? AND user_id = ? FOR UPDATE",
                Integer.class, showId, userId);
        if (currentCount + seats.size() > show.perUserLimit()) {
            recordDecline(userId, request.idempotency_key(), "per-user-limit");
            metrics.declined("per-user-limit");
            return ReservationResult.declined("per-user-limit", "Per-user seat limit exceeded");
        }

        UUID reservationId = UUID.randomUUID();
        long amount = Math.multiplyExact(show.pricePaise(), seats.size());

        if (seats.size() == 1) {
            jdbc.getJdbcTemplate().update(
                    "INSERT INTO reservations(id, show_id, user_id, amount_paise, status) VALUES (?, ?, ?, ?, 'confirmed')",
                    reservationId, showId, userId, amount);
            int changed = jdbc.getJdbcTemplate().update(
                    """
                    UPDATE seats SET status = 'confirmed', reservation_id = ?
                    WHERE show_id = ? AND seat_label = ? AND status = 'available'
                    """,
                    reservationId, showId, seats.get(0));
            if (changed != 1) {
                jdbc.getJdbcTemplate().update("DELETE FROM reservations WHERE id = ?", reservationId);
                boolean seatExists = Boolean.TRUE.equals(jdbc.getJdbcTemplate().queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM seats WHERE show_id = ? AND seat_label = ?)",
                        Boolean.class, showId, seats.get(0)));
                String reason = seatExists ? "seat-taken" : "seat-unavailable";
                recordDecline(userId, request.idempotency_key(), reason);
                metrics.declined(reason);
                return seatExists
                        ? ReservationResult.declined(reason, "One or more seats are already taken")
                        : ReservationResult.declined(reason, "One or more seats do not exist or are unavailable");
            }
        } else {
            List<SeatRecord> foundSeats = jdbc.getJdbcTemplate().query(
                    """
                    SELECT seat_label, status FROM seats
                    WHERE show_id = ? AND seat_label IN (%s)
                    ORDER BY seat_label COLLATE "C" FOR UPDATE
                    """.formatted(placeholders(seats.size())),
                    (row, index) -> new SeatRecord(row.getString("seat_label"), row.getString("status")),
                    parameters(showId, seats));
            if (foundSeats.size() != seats.size()) {
                recordDecline(userId, request.idempotency_key(), "seat-unavailable");
                metrics.declined("seat-unavailable");
                return ReservationResult.declined("seat-unavailable", "One or more seats do not exist or are unavailable");
            }
            if (foundSeats.stream().anyMatch(seat -> !"available".equals(seat.status()))) {
                recordDecline(userId, request.idempotency_key(), "seat-taken");
                metrics.declined("seat-taken");
                return ReservationResult.declined("seat-taken", "One or more seats are already taken");
            }
            jdbc.getJdbcTemplate().update(
                    "INSERT INTO reservations(id, show_id, user_id, amount_paise, status) VALUES (?, ?, ?, ?, 'confirmed')",
                    reservationId, showId, userId, amount);
        }

        var reservationSeats = seats.stream()
                .map(seat -> new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("showId", showId)
                        .addValue("seat", seat))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(
                "INSERT INTO reservation_seats(reservation_id, show_id, seat_label) VALUES (:reservationId, :showId, :seat)",
                reservationSeats);
        if (seats.size() > 1) {
            int changed = jdbc.getJdbcTemplate().update(
                    """
                    UPDATE seats SET status = 'confirmed', reservation_id = ?
                    WHERE show_id = ? AND seat_label IN (%s) AND status = 'available'
                    """.formatted(placeholders(seats.size())),
                    parameters(reservationId, showId, seats));
            if (changed != seats.size()) {
                throw new IllegalStateException("Locked seat rows changed unexpectedly");
            }
        }
        jdbc.getJdbcTemplate().update(
                "UPDATE user_show_counts SET seat_count = seat_count + ? WHERE show_id = ? AND user_id = ?",
                seats.size(), showId, userId);
        jdbc.getJdbcTemplate().update(
                "UPDATE idempotency_keys SET reservation_id = ?, outcome = 'confirmed' WHERE user_id = ? AND idempotency_key = ?",
                reservationId, userId, request.idempotency_key());
        metrics.confirmed();
        return new ReservationResult(
                new ApiModels.ReservationResponse(reservationId, showId, userId, seats, amount, "confirmed"),
                null, null, false);
    }

    @Transactional
    public void cancel(UUID reservationId, String userId) {
        ReservationRecord owner = jdbc.getJdbcTemplate().query(
                "SELECT show_id, user_id FROM reservations WHERE id = ?",
                (row, index) -> new ReservationRecord(
                        reservationId,
                        row.getObject("show_id", UUID.class),
                        row.getString("user_id"),
                        null),
                reservationId).stream().findFirst().orElseThrow(ReservationService::notFoundReservation);
        if (!owner.userId().equals(userId)) {
            throw new DomainException(HttpStatus.FORBIDDEN, "not-reservation-owner", "Only the reservation owner may cancel it");
        }
        jdbc.getJdbcTemplate().queryForObject(
                "SELECT seat_count FROM user_show_counts WHERE show_id = ? AND user_id = ? FOR UPDATE",
                Integer.class, owner.showId(), userId);
        ReservationRecord reservation = jdbc.getJdbcTemplate().query(
                "SELECT id, show_id, user_id, status FROM reservations WHERE id = ? FOR UPDATE",
                (row, index) -> new ReservationRecord(
                        row.getObject("id", UUID.class),
                        row.getObject("show_id", UUID.class),
                        row.getString("user_id"),
                        row.getString("status")),
                reservationId).stream().findFirst().orElseThrow(ReservationService::notFoundReservation);
        if ("cancelled".equals(reservation.status())) return;

        List<String> seats = jdbc.getJdbcTemplate().queryForList(
                "SELECT seat_label FROM seats WHERE reservation_id = ? ORDER BY seat_label COLLATE \"C\" FOR UPDATE",
                String.class, reservationId);
        jdbc.getJdbcTemplate().update(
                "UPDATE seats SET status = 'available', reservation_id = NULL WHERE reservation_id = ?",
                reservationId);
        jdbc.getJdbcTemplate().update(
                "UPDATE user_show_counts SET seat_count = seat_count - ? WHERE show_id = ? AND user_id = ?",
                seats.size(), reservation.showId(), userId);
        jdbc.getJdbcTemplate().update("UPDATE reservations SET status = 'cancelled' WHERE id = ?", reservationId);
    }

    private void recordDecline(String userId, String key, String reason) {
        jdbc.getJdbcTemplate().update(
                "UPDATE idempotency_keys SET outcome = ? WHERE user_id = ? AND idempotency_key = ?",
                reason, userId, key);
    }

    private ApiModels.ReservationResponse getReservation(UUID reservationId) {
        return jdbc.getJdbcTemplate().queryForObject(
                """
                SELECT r.show_id, r.user_id, r.amount_paise, r.status,
                       array_agg(rs.seat_label ORDER BY rs.seat_label COLLATE "C") AS seats
                FROM reservations r JOIN reservation_seats rs ON rs.reservation_id = r.id
                WHERE r.id = ?
                GROUP BY r.id
                """,
                (row, index) -> new ApiModels.ReservationResponse(
                        reservationId,
                        row.getObject("show_id", UUID.class),
                        row.getString("user_id"),
                        List.of((String[]) row.getArray("seats").getArray()),
                        row.getLong("amount_paise"),
                        row.getString("status")),
                reservationId);
    }

    private ShowDetails findShow(UUID showId) {
        return jdbc.getJdbcTemplate().query(
                "SELECT price_paise, per_user_limit FROM shows WHERE id = ?",
                (row, index) -> new ShowDetails(row.getLong("price_paise"), row.getInt("per_user_limit")),
                showId).stream().findFirst().orElse(null);
    }

    private static Object[] parameters(UUID showId, List<String> seats) {
        List<Object> values = new ArrayList<>();
        values.add(showId);
        values.addAll(seats);
        return values.toArray();
    }

    private static Object[] parameters(UUID reservationId, UUID showId, List<String> seats) {
        List<Object> values = new ArrayList<>();
        values.add(reservationId);
        values.add(showId);
        values.addAll(seats);
        return values.toArray();
    }

    private static String placeholders(int count) {
        return java.util.Collections.nCopies(count, "?").stream().collect(Collectors.joining(","));
    }

    private static String messageFor(String outcome) {
        return switch (outcome) {
            case "per-user-limit" -> "Per-user seat limit exceeded";
            case "seat-taken" -> "One or more seats are already taken";
            default -> "One or more seats do not exist or are unavailable";
        };
    }

    private static DomainException notFoundShow() {
        return new DomainException(HttpStatus.NOT_FOUND, "show-not-found", "Show was not found");
    }

    private static DomainException notFoundReservation() {
        return new DomainException(HttpStatus.NOT_FOUND, "reservation-not-found", "Reservation was not found");
    }

    public record ReservationResult(
            ApiModels.ReservationResponse response, String declineCode, String message, boolean replay) {
        public static ReservationResult declined(String code, String message) {
            return new ReservationResult(null, code, message, false);
        }

        public boolean declined() {
            return declineCode != null;
        }
    }

    private record IdempotencyRecord(String requestSeats, UUID reservationId, String outcome) {}
    private record ShowDetails(long pricePaise, int perUserLimit) {}
    private record SeatRecord(String seat, String status) {}
    private record ReservationRecord(UUID id, UUID showId, String userId, String status) {}
}
