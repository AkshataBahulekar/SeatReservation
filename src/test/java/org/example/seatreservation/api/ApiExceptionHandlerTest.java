package org.example.seatreservation.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsDomainExceptionToItsHttpStatusAndError() {
        var response = handler.domain(
                new DomainException(HttpStatus.CONFLICT, "seat-taken", "Seat already taken"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("seat-taken", response.getBody().error());
    }

    @Test
    void mapsValidationErrorsToBadRequest() {
        var response = handler.validation(mock(MethodArgumentNotValidException.class));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid-request", response.getBody().error());
    }

    @Test
    void mapsMalformedJsonToBadRequest() {
        var exception = mock(HttpMessageNotReadableException.class);

        var response = handler.unreadable(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Malformed JSON request", response.getBody().message());
    }

    @Test
    void hidesUnexpectedExceptionDetails() {
        var response = handler.unexpected(new IllegalStateException("sensitive detail"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("internal-error", response.getBody().error());
        assertEquals("Request could not be completed", response.getBody().message());
    }
}
