package org.example.seatreservation.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiModelsTest {
    @Test
    void reservationResponseCarriesReservationFields() {
        UUID reservationId = UUID.randomUUID();
        UUID showId = UUID.randomUUID();
        var response = new ApiModels.ReservationResponse(
                reservationId, showId, "alice", List.of("A1"), 25000, "confirmed");

        assertEquals(reservationId, response.reservation_id());
        assertEquals(showId, response.show_id());
        assertEquals("alice", response.user_id());
        assertEquals(List.of("A1"), response.seats());
        assertEquals(25000, response.amount_paise());
        assertEquals("confirmed", response.status());
    }

    @Test
    void showResponseCarriesReconciledInventory() {
        UUID showId = UUID.randomUUID();
        var response = new ApiModels.ShowResponse(
                showId, "show", 25000, 4, 2, 1, 0, 1,
                List.of(new ApiModels.SeatState("A1", "available"),
                        new ApiModels.SeatState("A2", "confirmed")));

        assertEquals(showId, response.id());
        assertEquals(response.total_seats(),
                response.available() + response.held() + response.confirmed());
        assertEquals("A2", response.seats().get(1).seat());
    }
}
