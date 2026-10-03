package org.example.seatreservation.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiModels.ErrorResponse> domain(DomainException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiModels.ErrorResponse(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiModels.ErrorResponse> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiModels.ErrorResponse("invalid-request", "Request validation failed"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiModels.ErrorResponse> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiModels.ErrorResponse("invalid-request", "Malformed JSON request"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiModels.ErrorResponse> unexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiModels.ErrorResponse("internal-error", "Request could not be completed"));
    }
}
