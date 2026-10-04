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
- Each request emits a JSON structured log event with request ID, method, path, status, and duration; use or supply `X-Request-Id` for correlation. Hosting-platform logs are available through the provider dashboard.

## Concurrency burst

Run `python burst.py http://localhost:8080` (or pass the deployed base URL). It creates a fresh show, signs a distinct user token for each request, fires a configurable hot-seat stampede, prints status/reason distribution, then verifies and prints final seat-count reconciliation.

```sh
python burst.py https://YOUR-SERVICE.onrender.com
```

Options: `--requests 500` (default, maximum 20000), `--workers 100` (maximum simultaneous requests), `--timeout 600` (per-request seconds), `--admin-token ...`, and `--token-secret ...`. Use the corresponding configured secrets. Reservation requests may be retried after transport timeouts using the same idempotency key; the script reports exhausted transport failures separately from HTTP 5xx responses. It fails if it observes a 5xx, transport failure, anything other than one successful winner, or a broken reconciliation invariant. In addition to the hot-seat storm it verifies a successful same-key replay and same-key/different-body conflict.

## Deploy

`render.yaml` describes a Render Docker web service and PostgreSQL database. Create a Render Blueprint from the repository; the platform generates `ADMIN_TOKEN` and `USER_TOKEN_SECRET`. The repository does not contain live deployment credentials or a deployed public URL.

See [WRITEUP.md](WRITEUP.md) for design tradeoffs and AI-use disclosure.
