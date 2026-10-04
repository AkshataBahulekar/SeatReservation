package org.example.seatreservation.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationMetricsTest {
    @Test
    void incrementsConfirmationCounterImmediatelyOutsideTransaction() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ReservationMetrics(registry, mock(JdbcTemplate.class));

        metrics.confirmed();

        assertEquals(1.0, registry.counter("reservations.confirmed").count());
    }

    @Test
    void incrementsDeclineCounterWithReasonTag() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ReservationMetrics(registry, mock(JdbcTemplate.class));

        metrics.declined("seat-taken");

        assertEquals(1.0, registry.get("reservations.declined")
                .tag("reason", "seat-taken").counter().count());
    }

    @Test
    void defersCountersUntilTransactionCommit() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ReservationMetrics(registry, mock(JdbcTemplate.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            metrics.confirmed();

            assertEquals(0.0, registry.counter("reservations.confirmed").count());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());
            assertEquals(1.0, registry.counter("reservations.confirmed").count());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void availableSeatGaugeReadsDatabaseCount() {
        var registry = new SimpleMeterRegistry();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(7L);
        new ReservationMetrics(registry, jdbc);

        assertEquals(7.0, registry.get("seats_available").gauge().value());
    }
}
