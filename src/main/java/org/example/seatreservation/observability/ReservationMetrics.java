package org.example.seatreservation.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ReservationMetrics {
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final ConcurrentMap<String, Counter> declines = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        Gauge.builder("seats_available", this, metrics -> metrics.availableSeats())
                .description("Currently available seats across all shows")
                .register(registry);
    }

    public void confirmed() {
        afterCommit(() -> registry.counter("reservations.confirmed").increment());
    }

    public void declined(String reason) {
        afterCommit(() -> declines.computeIfAbsent(reason, value -> Counter.builder("reservations.declined")
                    .tag("reason", value)
                    .register(registry)).increment());
    }

    private double availableSeats() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE status = 'available'", Long.class);
        return count == null ? 0 : count;
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
