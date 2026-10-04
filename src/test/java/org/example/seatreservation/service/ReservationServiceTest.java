package org.example.seatreservation.service;

import org.example.seatreservation.api.ApiModels;
import org.example.seatreservation.api.DomainException;
import org.example.seatreservation.observability.ReservationMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationServiceTest {
    private final NamedParameterJdbcTemplate namedJdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReservationMetrics metrics = mock(ReservationMetrics.class);
    private final ReservationService service = new ReservationService(namedJdbc, metrics);

    @Test
    void rejectsDuplicateSeatsWhenCreatingShowBeforeDatabaseWrites() {
        when(namedJdbc.getJdbcTemplate()).thenReturn(jdbc);

        var exception = assertThrows(DomainException.class, () -> service.createShow(
                new ApiModels.CreateShowRequest("show", List.of("A1", "A1"), 100, null)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("duplicate-seat", exception.code());
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void rejectsDuplicateSeatsInReservationBeforeDatabaseWrites() {
        when(namedJdbc.getJdbcTemplate()).thenReturn(jdbc);

        var exception = assertThrows(DomainException.class, () -> service.reserve(
                UUID.randomUUID(), "alice",
                new ApiModels.ReserveRequest(List.of("A1", "A1"), "key")));

        assertEquals("duplicate-seat", exception.code());
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void enforcesMaximumSeatsPerReservationBeforeDatabaseWrites() {
        when(namedJdbc.getJdbcTemplate()).thenReturn(jdbc);
        List<String> seats = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> "S" + index)
                .toList();

        var exception = assertThrows(DomainException.class, () -> service.reserve(
                UUID.randomUUID(), "alice", new ApiModels.ReserveRequest(seats, "key")));

        assertEquals("too-many-seats", exception.code());
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    void returnsNotFoundForUnknownShow() {
        when(namedJdbc.getJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(List.<Map<String, Object>>of());

        var exception = assertThrows(DomainException.class, () -> service.getShow(UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, exception.status());
        assertEquals("show-not-found", exception.code());
    }
}
