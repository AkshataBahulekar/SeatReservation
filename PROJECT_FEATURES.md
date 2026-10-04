# Seat Reservation Project Features and Functionalities

## Overview
This project is a Java 21 / Spring Boot seat reservation system backed by PostgreSQL. It allows an administrator to create a show with a list of available seats, and authenticated users to reserve seats for that show while guaranteeing correctness under concurrent requests.

The application is designed around strong consistency, idempotent requests, and all-or-nothing seat reservations. It supports concurrent reservations with transactional correctness and exposes health, metrics, and structured logs.

---

## Core Business Features

### 1. Show creation by admin
An admin can create a show by sending a request to `POST /shows`.

Required inputs:
- `name`: show name
- `seats`: list of seat labels such as `A1`, `A2`, `A3`
- `price_paise`: price in paise (integer)
- `per_user_limit`: optional maximum seat count a single user may reserve for the show

Features:
- Seats must be unique
- Seat labels are validated to match a safe ASCII pattern
- Duplicate seats are rejected
- Maximum seat count is enforced
- Price must be within safe limits for multi-seat reservations

### 2. Seat inventory management
Each show stores a set of seats in a `seats` table with an individual status value:
- `available`
- `confirmed`

The service keeps exact seat counts and state for each show and exposes them through the show query endpoint.

### 3. Reservation of multiple seats
Users can reserve one or more seats in a single request using `POST /shows/{showId}/reserve`.

Request fields:
- `seats`: list of seat labels to reserve
- `idempotency_key`: unique request identifier for the authenticated user

Behavior:
- Reservations are all-or-nothing
- If any requested seat is unavailable, missing, or already taken, the entire request is rejected
- All selected seats are checked in one transaction, so there is no partial success
- The maximum reservation size is capped at 50 seats per request

### 4. Per-user seat limits
Each show may define a `per_user_limit`.

This limit prevents a single user from reserving more than the configured number of seats on the same show. The application enforces this with a database transaction that serializes counts per user-show pair.

### 5. Cancellation
Users may cancel their own reservations by calling `POST /reservations/{reservationId}/cancel`.

Features:
- Only the reservation owner can cancel
- Cancel is idempotent
- Cancelled seats are returned to the pool as `available`
- The user's per-show reservation count is decremented
- Reservation history is retained, but the current reservation status becomes `cancelled`

### 6. Show lookup and inventory summary
`GET /shows/{showId}` returns:
- show ID and name
- price in paise
- per-user limit
- total seats
- available seats count
- confirmed seats count
- detailed seat list with status per label

This allows clients to inspect the current reservation state of a show.

---

## API Functionalities

### Admin endpoints

#### `POST /shows`
Creates a new show.

Headers:
- `X-Admin-Token`: required admin secret

Request body example:
```json
{
  "name": "friday-night",
  "seats": ["A1", "A2", "A3"],
  "price_paise": 25000,
  "per_user_limit": 4
}
```

Response:
- `201 Created`
- Returns the created show data

### User endpoints

#### `POST /shows/{showId}/reserve`
Reserves seats for an authenticated user.

Headers:
- `Authorization: Bearer <signed-user-token>`

Request body example:
```json
{
  "seats": ["A1", "A2"],
  "idempotency_key": "order-123"
}
```

Response behavior:
- Success: `201 Created` with reservation payload
- Replay of successful request: `200 OK` and original reservation payload
- Conflict with different parameters for same idempotency key: `409 Conflict`
- Declined due to seat conflict or limit: `409 Conflict` with error code and message

#### `POST /reservations/{reservationId}/cancel`
Cancels a reservation that belongs to the authenticated user.

Headers:
- `Authorization: Bearer <signed-user-token>`

Response:
- `204 No Content` on successful cancellation

#### `GET /shows/{showId}`
Returns the show state and seat inventory.

Response includes full seat listing and counts.

---

## Authentication and Authorization

### Admin authentication
The admin token is checked using a constant-time comparison against the configured `X-Admin-Token` header.

If the token is missing or incorrect:
- `401 Unauthorized`
- error code: `unauthorized`

### User authentication
Users authenticate with a bearer token of the form:
```text
Bearer <payload>.<signature>
```

Key properties:
- The payload contains the user ID
- The signature is computed using HMAC-SHA256 with a configured secret
- The service verifies the signature before accepting the token
- Only trusted signed user IDs are accepted; the request body does not determine the user identity

This is a lightweight token mechanism intended for this project and not a full production identity-provider solution.

---

## Concurrency and Correctness Guarantees

### Atomic reservation decision
Reservations run inside a PostgreSQL transaction.

The service:
- locks the show/user count row for the user
- checks whether the reservation would exceed the per-user limit
- locks the requested seat rows
- verifies that every requested seat is available
- updates the seats and reservation records together in one transaction

This ensures that concurrent requests cannot oversell seats or exceed the configured user cap.

### Idempotency
The system supports duplicate request protection through a per-user idempotency key.

The same user + same idempotency key behaves as follows:
- same request body and same show/seat list: returns the original successful reservation response
- same key but different seat list or different show: rejected with `409 Conflict`
- previously declined request: returns the original decline outcome

This prevents accidental duplicate reservations when a client retries a request.

### Lock ordering
The implementation uses deterministic lock ordering to avoid deadlock:
- user/show count locks are acquired before seat locks
- seat rows are processed in sorted ASCII seat-label order
- cancellation follows the same overall locking discipline

This helps maintain safety under concurrent workloads.

---

## Validation and Error Handling

The project validates API inputs using Jakarta Bean Validation.

Examples of invalid inputs:
- blank show name
- empty seat list
- invalid seat label format
- duplicate seat labels
- too many seats
- invalid or malformed JSON payloads

Error responses use a consistent format:
```json
{
  "error": "code",
  "message": "human-readable description"
}
```

Examples of error codes:
- `duplicate-seat`
- `too-many-seats`
- `seat-unavailable`
- `seat-taken`
- `per-user-limit`
- `invalid-request`
- `unauthorized`

---

## Observability and Monitoring

### Health endpoints
The app exposes Spring Boot actuator health endpoints:
- `GET /livez` — process liveness
- `GET /readyz` — readiness including database connectivity

### Prometheus metrics
Metrics are exposed at:
- `GET /actuator/prometheus`

Included metrics:
- `reservations.confirmed`
- `reservations.declined{reason="..."}`
- `seats_available`

This gives operational visibility into successful confirmations, declines by reason, and current inventory health.

### Structured logging
Each request emits a JSON log event with:
- request ID
- HTTP method
- path
- status code
- duration in milliseconds

The service also supports a correlation header:
- `X-Request-Id`

If no valid request ID is supplied, the service generates one automatically.

---

## Local Development and Deployment

### Run locally with Docker Compose
```bash
docker compose up --build
```

The application listens on:
- `http://localhost:8080`

The default configuration uses:
- admin token: `dev-admin-token`
- user token secret: `local-development-secret-change-me`

### Deployment support
The repository includes deployment configuration for Render:
- `render.yaml`
- `Dockerfile`
- `compose.yaml`

This makes the service deployable to container-based hosting environments and a PostgreSQL-backed cloud service.

---

## Concurrency Burst Testing
The project includes a Python-based validation script:
- `burst.py`

This script:
- creates a fresh show
- signs unique user tokens for each request
- launches a high-concurrency stampede on a hot seat
- verifies that only one reservation wins
- checks idempotency and conflict behavior
- validates final reconciliation counts

This is designed to prove strong correctness under concurrent access patterns.

---

## Key Design Choices

### PostgreSQL as source of truth
The database is the system of record, ensuring all seat states and reservation counts are consistent.

### Immediate confirmation model
Unlike hold-based systems, this project confirms reservations immediately, without temporary seat holds.

### Strong consistency over availability
The project prefers correctness under heavy load and partition conditions rather than speculative or optimistic inventory behavior.

### Minimal identity model
User identity is verified from signed bearer tokens instead of trusting client-supplied request data.

---

## Typical End-to-End Workflow

1. Admin creates a show with seat labels and price.
2. Client fetches the show information.
3. Client signs a user token for the authenticated user.
4. Client sends a reservation request with a unique idempotency key.
5. The service verifies the token and applies transactional validation.
6. If valid, the reservation is inserted and seat statuses are updated.
7. The user may later cancel the reservation if needed.
8. Health, metrics, and logs provide operational insight into the running service.

---

## Summary
This project is a robust seat-reservation API focused on correctness, concurrency safety, and operational visibility. Its most important features include:
- admin-driven show creation
- authenticated user reservations
- per-user seat limits
- all-or-nothing multi-seat transactions
- idempotent retry handling
- cancellation logic
- PostgreSQL-backed strong consistency
- observability through health checks, metrics, and structured logs

This makes it suitable for learning concurrency-safe API design and for deployment as a real-world reservation service foundation.
