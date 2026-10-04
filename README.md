# Seat Reservation

Concurrency-safe Java 21 / Spring Boot JSON API backed by PostgreSQL. Money is stored as integer paise. Reservations are confirmed immediately; there are no time-boxed holds. Multi-seat requests are **all-or-nothing**.

## Run locally

Docker Compose starts PostgreSQL and the API:

```sh
docker compose up --build
```

The API listens at `http://localhost:8080`. Local-only defaults are `ADMIN_TOKEN=dev-admin-token` and `USER_TOKEN_SECRET=local-development-secret-change-me`; override both outside a local machine. The app creates its schema on startup.

### Authentication

Create a show with `X-Admin-Token`. User tokens are signed, opaque bearer tokens, so a caller cannot choose or spoof the user ID by adding a body field. This simple token scheme is for this take-home service; a production deployment would validate tokens from the identity provider.

For local requests, create a bearer token with Python's standard library:

```sh
python -c "import base64,hmac,hashlib; secret=b'local-development-secret-change-me'; user=b'alice'; p=base64.urlsafe_b64encode(user).rstrip(b'='); s=base64.urlsafe_b64encode(hmac.new(secret,p,hashlib.sha256).digest()).rstrip(b'='); print(p.decode()+'.'+s.decode())"
```

### API

Create a show:

```sh
curl -X POST http://localhost:8080/shows \
  -H 'Content-Type: application/json' -H 'X-Admin-Token: dev-admin-token' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}'
```

Reserve atomically with a unique idempotency key:

```sh
curl -X POST http://localhost:8080/shows/SHOW_ID/reserve \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer SIGNED_USER_TOKEN' \
  -d '{"seats":["A1"],"idempotency_key":"order-123"}'
```

Inspect a show with `GET /shows/{id}`; cancel the owner's reservation with `POST /reservations/{id}/cancel` and the same bearer authentication. Cancellation returns seats to inventory and is idempotent. Conflicting requests return HTTP 409 with a JSON `error` code. Reusing a key for a different show or seat list returns 409. Replaying a successful request returns its original reservation with HTTP 200. Seat labels are ASCII alphanumeric plus `.`, `_`, or `-`, and are canonicalized (sorted) before idempotency comparison.

## Health, metrics, logs

- `GET /livez`: process liveness.
- `GET /readyz`: readiness, including PostgreSQL connectivity.
- `GET /actuator/prometheus`: Prometheus metrics. Includes `reservations_confirmed_total`, `reservations_declined_total{reason=...}` (`seat-taken`, `per-user-limit`, `idempotent-replay`, and other domain declines), and `seats_available` across shows.
- `GET /actuator/info`: build information including the full Git commit SHA under `git.commit.id`. Maven generates `git.properties` during packaging; the Docker build includes the repository metadata so deployed builds expose the source revision.
- Each request emits a JSON structured log event with request ID, method, path, status, and duration; use or supply `X-Request-Id` for correlation. Hosting-platform logs are available through the provider dashboard.

### PostgreSQL lock-wait diagnostics

During a reservation burst, use these queries against the application database to inspect active sessions that are blocked on locks and identify their blockers:

```sql
SELECT pid, application_name, state, wait_event_type, wait_event,
       now() - query_start AS query_age,
       pg_blocking_pids(pid) AS blocking_pids,
       left(query, 300) AS query
FROM pg_stat_activity
WHERE datname = current_database()
  AND state <> 'idle'
ORDER BY query_start;
```

Inspect granted and waiting locks for a particular blocked PID:

```sql
SELECT l.pid, l.locktype, l.mode, l.granted,
       l.relation::regclass AS relation,
       l.transactionid, a.wait_event_type, a.wait_event,
       left(a.query, 300) AS query
FROM pg_locks l
LEFT JOIN pg_stat_activity a USING (pid)
WHERE l.pid = 12345 -- replace with the blocked backend PID
ORDER BY l.granted, l.locktype;
```

Single-seat requests use one status-guarded `UPDATE` as the atomic claim, then insert the reservation only if that update succeeds. The `seats.reservation_id` foreign key is deferred until transaction commit to preserve referential integrity with that write order. Multi-seat requests retain deterministic `SELECT ... FOR UPDATE` locking and all-or-nothing transaction handling. Keep the database work inside these short transactions; do not perform network calls or other slow work within them.

## Concurrency burst

Run `python burst.py http://localhost:8080` (or pass the deployed base URL). It creates a fresh show, signs a distinct user token for each request, fires a configurable hot-seat stampede, prints status/reason distribution, then verifies and prints final seat-count reconciliation.

```sh
python burst.py https://YOUR-SERVICE.onrender.com
```

Options: `--requests 500` (default, maximum 20000), `--workers 100` (maximum simultaneous requests), `--timeout 600` (per-request seconds), `--admin-token ...`, and `--token-secret ...`. Use the corresponding configured secrets. Reservation requests may be retried after timeouts or incomplete HTTP response bodies using the same idempotency key; the script reports exhausted transport failures separately from HTTP 5xx responses. Its JSON output includes elapsed time for show creation, the concurrent reservation burst, outcome aggregation, idempotency checks, final reconciliation, and overall runtime, plus p50/p95/maximum reservation-request latency. It fails if it observes a 5xx, transport failure, anything other than one successful winner, or a broken reconciliation invariant. In addition to the hot-seat storm it verifies a successful same-key replay and same-key/different-body conflict.

### Correctness integration scenarios

Run the broader correctness harness against a running API and database:

```sh
python correctness_tests.py http://localhost:8080
```

It creates isolated shows and tests simultaneous requests against multiple hot seats, overlapping all-or-nothing multi-seat requests (for example, `["A1","A2"]` racing `["A2","A3"]`), reversed-input-order multi-seat contention for deadlock detection, concurrent same-key/same-body retries, concurrent same-key/different-body requests, a per-user limit race, token-derived identity and owner-only cancellation/rebooking, and show-count reconciliation sampled continuously during a reservation burst. The deadlock case launches concurrent requests for the same pair of seats while alternating the submitted order (for example, `["A","B"]` and `["B","A"]`); it expects one confirmation and clean `409 seat-taken` responses for all other requests, with no server or transport errors. It prints a result for each test and exits nonzero on any failed assertion, unexpected status, malformed response, transport error, or reconciliation mismatch. Use the service's configured secrets with `--admin-token` and `--token-secret`. Request counts and limits can be adjusted; see `python correctness_tests.py --help`.

### Java unit tests

Run the unit tests with Maven:

```sh
mvn test
```

The tests cover the API models, controller authentication and response mapping, exception handling, reservation input and not-found validation, metrics behavior, request-ID logging behavior, and Spring Boot application configuration. The Python correctness harness above exercises database-backed concurrent reservation flows against a running service.

## Deploy

`render.yaml` describes a Render Docker web service and PostgreSQL database. Create a Render Blueprint from the repository; the platform generates `ADMIN_TOKEN` and `USER_TOKEN_SECRET`.

Public deployment: https://seat-reservation-w6dh.onrender.com/

Health, metrics, and build information are available at `/livez`, `/readyz`, `/actuator/prometheus`, and `/actuator/info` on the deployed base URL.

See [WRITEUP.md](WRITEUP.md) for design tradeoffs and AI-use disclosure.
