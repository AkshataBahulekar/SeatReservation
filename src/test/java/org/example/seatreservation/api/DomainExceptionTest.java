package org.example.seatreservation.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DomainExceptionTest {
    @Test
    void exposesStatusCodeAndMessage() {
        var exception = new DomainException(HttpStatus.CONFLICT, "seat-taken", "Seat is taken");

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals("seat-taken", exception.code());
        assertEquals("Seat is taken", exception.getMessage());
    }
}
