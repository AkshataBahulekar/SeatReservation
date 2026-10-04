# Seat Reservation Design Write-up

## Atomic reservation decision

PostgreSQL is the system of record. Every reservation is handled within a database transaction. Before checking inventory, the service locks the `(show_id, user_id)` row in `user_show_counts` using `SELECT ... FOR UPDATE`; concurrent requests from the same user therefore serialize while the per-show seat limit is checked and updated.

For a **single-seat** request, the atomic seat decision is a conditional update:

```sql
UPDATE seats
SET status = 'confirmed', reservation_id = :reservationId
WHERE show_id = :showId
  AND seat_label = :seatLabel
  AND status = 'available';
```

Exactly one affected row means the seat was claimed. Zero affected rows means it was not available (or did not exist); the transaction records and returns a domain decline (`seat-taken` or `seat-unavailable`, HTTP 409). The reservation row and seat update are in the same transaction, so they commit or roll back together. PostgreSQL serializes competing updates to that row; after waiting, a competing statement rechecks the `status = 'available'` predicate against the committed row and cannot claim it a second time.

For a **multi-seat** request, labels are canonicalized and sorted, then all requested seat rows are selected in that deterministic order with `SELECT ... FOR UPDATE`. The service verifies every seat exists and is available before writing the reservation and changing inventory. Any missing or taken seat declines the entire request; no partial reservation is made. The final conditional update is checked to affect every requested row. Seat labels are restricted to ASCII, so the `COLLATE "C"` ordering is stable.

Both paths update `reservation_seats`, the per-user count, and the idempotency outcome in the same transaction as inventory. Database primary keys enforce one inventory row per `(show_id, seat_label)`. There is no separate active hold state: a successful request confirms immediately.

## Idempotency and identity

The `idempotency_keys` table has a primary key on `(user_id, idempotency_key)`. It stores the canonical request (`show ID` plus sorted seat labels), `reservation_id`, and outcome. `INSERT ... ON CONFLICT DO NOTHING` followed by `SELECT ... FOR UPDATE` serializes concurrent submissions using the same user's key.

- A matching retry after successful confirmation returns the original reservation with HTTP 200 and does not change inventory again.
- Reusing the key with a different show or seat list returns HTTP 409 (`idempotency-key-reused`).
- A declined request retains its outcome, so retrying that same key returns the same decline.

The authenticated user ID is derived from an HMAC-SHA256-signed bearer token and checked using constant-time signature comparison. Reservation requests do not accept an identity from the body. Cancellation uses the same token-derived ID and allows only the reservation owner to cancel. This small token format is for the exercise; production should validate issuer-controlled, expiring tokens through an identity provider.

## Per-user limit

Each show has a configured `per_user_limit`, defaulting to four. The service creates or finds a `user_show_counts` row, locks it with `SELECT ... FOR UPDATE`, checks the requested seat count against the limit, and updates the count in the same transaction as reservation state. Concurrent booking requests by one user serialize on that row; over-limit attempts return HTTP 409 (`per-user-limit`).

## Cancellation, release, and consistency

The project chooses explicit cancellation rather than time-boxed holds. `POST /reservations/{id}/cancel` is owner-only and idempotent. In one transaction it locks the owner's show-count row and reservation, locks the reservation's seat rows, sets only those rows back to `available`, decrements the count, and marks the reservation `cancelled`. Reservation-seat history is retained. Since the seats are locked and tied to the reservation, cancellation cannot release seats already assigned to another reservation.

`GET /shows/{id}` reads show and inventory state from PostgreSQL and returns each seat's status and counts. `held` is currently always zero because there is no hold workflow. With a DB transaction as the state boundary, clients observe committed inventory and the invariant `available + held + confirmed == total_seats`.

Under a database partition or outage, the application fails requests rather than inventing availability or accepting writes without the database. This favors correctness over availability. `/readyz` includes the database health check and should fail when PostgreSQL is unreachable.

## Observability and operations

- `/livez` reports process liveness; `/readyz` checks readiness including PostgreSQL.
- `/actuator/prometheus` exposes confirmation and decline counters and a `seats_available` gauge. The gauge counts available seats across all shows, so compare it with a specific show's API state only in an isolated database or after accounting for other shows.
- A request filter emits structured JSON logs with request ID, method, path, response status, and duration. A valid `X-Request-Id` is preserved; otherwise one is generated and returned in the response header.
- The README documents `pg_stat_activity` and `pg_locks` queries for diagnosing blocked sessions and lock waits.
- `burst.py` creates a fresh show and runs a configurable hot-seat stampede (up to 20,000 requests), reports outcomes and request latency percentiles, checks same-key replay and same-key/different-body behavior, and verifies final inventory reconciliation. A clean run is evidence for that run and environment, not a guarantee of capacity in every deployment.
- `correctness_tests.py` separately exercises multi-hot-seat races, overlapping all-or-nothing multi-seat requests, reversed-input-order multi-seat contention for deadlock detection, concurrent idempotency retries and key/body conflicts, per-user limit races, token-derived identity, owner-only cancellation/rebooking, and reconciliation sampling during load.

At 2am, page on sustained readiness failures, elevated 5xx or transport errors, sustained reservation latency, DB connection-pool exhaustion/lock waits, and any API/metrics inventory reconciliation discrepancy. A production deployment should ensure its hosting provider exposes logs and metrics to operators; no public deployment URL is included in this repository's current documentation.

## Containerization and deployment

The `Dockerfile` builds the Spring Boot app using Java 21 and runs it in a Java 21 runtime image. `compose.yaml` starts PostgreSQL and the application together for local development. `render.yaml` describes a Render web service and PostgreSQL database with generated admin and user-token secrets. The repository includes deployment configuration, but the existence of configuration alone does not establish that a public service is currently deployed or healthy.

## AI use and next steps

AI tools were used to help scaffold and refine the Spring Boot/PostgreSQL implementation, reason about transaction boundaries and lock ordering, troubleshoot burst-client behavior, and draft/update documentation. I reviewed the changes against the code and made the final implementation and design choices; AI output is not treated as proof of correctness.

Before production, I would add PostgreSQL-backed automated concurrency tests for multi-seat races, same-key concurrent retries, per-user-limit races, cancellation races, and identity spoofing attempts. I would also test the 20,000-request workload against the target deployment while observing database connection-pool saturation and lock waits, add schema migration tooling, adopt an established identity provider, and exercise database failover and metrics cardinality.
