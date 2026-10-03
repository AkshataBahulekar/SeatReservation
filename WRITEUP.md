# Design notes

## Atomic reservation decision

PostgreSQL is the system of record. Reservation requests run in a database transaction. The service locks the `(show_id, user_id)` row in `user_show_counts` with `SELECT ... FOR UPDATE` before checking/updating the count, serializing requests for one user's limit. It then selects requested seat rows in sorted label order using `SELECT ... ORDER BY seat_label COLLATE "C" FOR UPDATE`, verifies all are available, and updates all of them in the same transaction. A seat has one primary-key row per show, and the conditional `UPDATE ... status = 'available'` is checked to affect every requested row. Competing buyers block on the same row and observe the committed confirmed state; only one can win.

Multi-seat behavior is all-or-nothing: an unknown or unavailable requested seat declines the entire request. Seat locks are acquired in deterministic ASCII label order (the accepted labels are restricted to ASCII). Across workflows, user/show count rows are locked before seat rows; cancellation locks the same user's count before touching seats, avoiding a reserve/cancel lock-order cycle.

## Idempotency and identity

The idempotency key is unique per authenticated user in `idempotency_keys`; the request's show ID and sorted seat list are stored as its canonical body. `INSERT ... ON CONFLICT DO NOTHING` followed by `SELECT ... FOR UPDATE` serializes concurrent retries on the key. A matching successful retry returns the original reservation with HTTP 200; a different show or seat list returns 409. The key outcome is committed with the reservation, so a retry cannot duplicate the reservation. A declined key retains its original decline outcome.

User IDs come only from an HMAC-SHA256 signed bearer token, verified with constant-time signature comparison. The sample uses a minimal token format to keep the service dependency-light; production should verify an issuer-controlled, expiring JWT or use an identity provider. Admin show creation uses a separate configured token. No user identity field is accepted from the request body.

## Release, consistency, and availability

Reservations are confirmed immediately (no intermediate held state). An owner-only cancel transaction returns the reservation's exact seats to available and decrements the user's count; canceled reservation-seat history is retained. Cancel is idempotent. State is strongly consistent from PostgreSQL, and show counts are read from a single SQL statement. Under a database partition, readiness fails and writes fail rather than guessing availability or risking oversell; correctness is preferred over availability.

## Observability

Prometheus counters track committed confirmations and declines by reason; the available-seat gauge queries PostgreSQL. `/livez` checks process liveness and `/readyz` includes the database health indicator. JSON request logs carry a generated or validated `X-Request-Id`. At 2am, alert on readiness failures, elevated 5xx/DB connection errors, sustained latency, and unexpected inventory/confirmation reconciliation differences. Metrics do not expose user IDs or reservation keys.

## AI use and next steps

AI tools were used to scaffold the Spring Boot/PostgreSQL implementation, reason through the transaction and lock order, and draft operational documentation. The implementation choices and correctness claims still require maintainer review; generated code is not proof of correctness. Before production, I would add PostgreSQL-backed concurrency tests and load tests, use an established identity provider, add schema migration/versioning, and test database failover and metric cardinality.
