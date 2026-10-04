package org.example.seatreservation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SeatReservationApplicationTest {
    @Test
    void applicationIsConfiguredAsSpringBootApplication() {
        assertNotNull(SeatReservationApplication.class.getAnnotation(SpringBootApplication.class));
    }
}
