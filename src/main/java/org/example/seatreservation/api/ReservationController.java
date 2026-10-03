package org.example.seatreservation.api;

import jakarta.validation.Valid;
import org.example.seatreservation.service.ReservationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@RestController
@RequestMapping
public class ReservationController {
    private final ReservationService reservations;
    private final String adminToken;
    private final byte[] userTokenSecret;

    public ReservationController(
            ReservationService reservations,
            @Value("${reservation.admin-token}") String adminToken,
            @Value("${reservation.user-token-secret}") String userTokenSecret) {
        this.reservations = reservations;
        this.adminToken = adminToken;
        this.userTokenSecret = userTokenSecret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/shows")
    public ResponseEntity<ApiModels.ShowResponse> createShow(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @Valid @RequestBody ApiModels.CreateShowRequest request) {
        requireAdmin(token);
        return ResponseEntity.status(HttpStatus.CREATED).body(reservations.createShow(request));
    }

    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<?> reserve(
            @PathVariable UUID showId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ApiModels.ReserveRequest request) {
        String userId = authenticatedUser(authorization);
        ReservationService.ReservationResult result = reservations.reserve(showId, userId, request);
        if (result.declined()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiModels.ErrorResponse(result.declineCode(), result.message()));
        }
        if (result.replay()) {
            return ResponseEntity.ok(result.response());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.response());
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID reservationId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        reservations.cancel(reservationId, authenticatedUser(authorization));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/shows/{showId}")
    public ApiModels.ShowResponse show(@PathVariable UUID showId) {
        return reservations.getShow(showId);
    }

    private void requireAdmin(String token) {
        if (token == null || !MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "unauthorized", "Admin authentication required");
        }
    }

    private String authenticatedUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid user token");
        }
        String[] pieces = authorization.substring("Bearer ".length()).split("\\.", -1);
        if (pieces.length != 2) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid user token");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(pieces[0]);
            byte[] suppliedSignature = Base64.getUrlDecoder().decode(pieces[1]);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(userTokenSecret, "HmacSHA256"));
            byte[] expectedSignature = mac.doFinal(pieces[0].getBytes(StandardCharsets.US_ASCII));
            String userId = new String(payload, StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)
                    || !userId.matches("[A-Za-z0-9._-]{1,100}")) {
                throw new DomainException(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid user token");
            }
            return userId;
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid user token");
        }
    }
}
