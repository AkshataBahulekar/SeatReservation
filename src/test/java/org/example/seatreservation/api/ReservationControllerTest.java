package org.example.seatreservation.api;

import org.example.seatreservation.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationControllerTest {
    private static final String SECRET = "test-secret";
    private final ReservationService service = mock(ReservationService.class);
    private final ReservationController controller =
            new ReservationController(service, "admin-secret", SECRET);

    @Test
    void rejectsShowCreationWithoutCorrectAdminToken() {
        var request = new ApiModels.CreateShowRequest("show", List.of("A1"), 100, null);

        var exception = assertThrows(DomainException.class, () -> controller.createShow("bad", request));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
        verify(service, never()).createShow(any());
    }

    @Test
    void createsShowWithAdminToken() {
        UUID showId = UUID.randomUUID();
        var expected = new ApiModels.ShowResponse(showId, "show", 100, 4, 1, 1, 0, 0,
                List.of(new ApiModels.SeatState("A1", "available")));
        var request = new ApiModels.CreateShowRequest("show", List.of("A1"), 100, null);
        when(service.createShow(request)).thenReturn(expected);

        ResponseEntity<ApiModels.ShowResponse> response = controller.createShow("admin-secret", request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(expected, response.getBody());
    }

    @Test
    void derivesReservationIdentityFromSignedBearerToken() {
        UUID showId = UUID.randomUUID();
        var expected = new ApiModels.ReservationResponse(
                UUID.randomUUID(), showId, "alice", List.of("A1"), 100, "confirmed");
        var request = new ApiModels.ReserveRequest(List.of("A1"), "key");
        when(service.reserve(eq(showId), eq("alice"), eq(request)))
                .thenReturn(new ReservationService.ReservationResult(expected, null, null, false));

        ResponseEntity<?> response = controller.reserve(
                showId, "Bearer " + signedToken("alice"), request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(service).reserve(showId, "alice", request);
    }

    @Test
    void rejectsInvalidUserToken() {
        var request = new ApiModels.ReserveRequest(List.of("A1"), "key");

        var exception = assertThrows(DomainException.class,
                () -> controller.reserve(UUID.randomUUID(), "Bearer invalid.token", request));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status());
    }

    private static String signedToken(String user) {
        try {
            String encodedUser = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(user.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(encodedUser.getBytes(StandardCharsets.US_ASCII));
            return encodedUser + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
