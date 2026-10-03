package org.example.seatreservation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    public record CreateShowRequest(
            @NotBlank @Size(max = 200) String name,
            @NotEmpty List<@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,40}") String> seats,
            @PositiveOrZero long price_paise,
            @Positive Integer per_user_limit) {}

    public record ReserveRequest(
            @NotEmpty List<@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,40}") String> seats,
            @NotBlank @Size(max = 200) String idempotency_key) {}

    public record ReservationResponse(
            UUID reservation_id,
            UUID show_id,
            String user_id,
            List<String> seats,
            long amount_paise,
            String status) {}

    public record SeatState(String seat, String status) {}

    public record ShowResponse(
            UUID id,
            String name,
            long price_paise,
            int per_user_limit,
            int total_seats,
            int available,
            int held,
            int confirmed,
            List<SeatState> seats) {}

    public record ErrorResponse(String error, String message) {}
}
