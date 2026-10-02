# Seat Reservation at Scale

A JSON HTTP API that sells assigned seats for an event and stays correct when thousands of users
hit the same seats at the same instant. PostgreSQL is the only source of truth; every invariant is
enforced by row locks, atomic conditional statements and constraints inside one short
`READ COMMITTED` transaction.

- **Never** sells a seat twice, exceeds the per-user limit, or executes an idempotency key twice.
- Expected contention is always a clean `409`, never a `500`.
- `available + held + confirmed == total` holds by construction and is continuously reconciled.

Design rationale and race analysis: **[WRITEUP.md](WRITEUP.md)**.

| | |
|---|---|
| **Live service** | https://seat-reservation-86o2.onrender.com |
| **One-command burst** | `./burst.sh https://seat-reservation-86o2.onrender.com` (details in [section 13](#13-burst-testing)) |
| **Metrics** | `GET /metrics` (Prometheus text, public) |
| **Logs** | `GET /admin/logs?request_id=…` with an admin token (see [Metrics & logs access](#metrics--logs-access)) |
| **Health** | `GET /health/live`, `GET /health/ready` |

---

## 1. Architecture

```
 client / burst tool
        │  HTTP + JSON, Authorization: Bearer <HS256 token>
        ▼
 ┌──────────────────────────── Spring Boot (stateless, horizontally scalable) ───────────────────┐
 │ RequestLoggingFilter (request_id, access log, HTTP metrics) → AuthFilter (verify token)       │
 │ ShowController · ReservationController · HealthController · AdminController                   │
 │ ReservationService                                                                            │
 │   Stage A  one lock-free snapshot query: may only REJECT or REPLAY, never grant               │
 │   Stage B  READ COMMITTED txn: claim idem key → lock seats (id order) → quota upsert → write  │
 │ SeatStateMonitor (seat gauges + reconciliation checks from authoritative tables)             │
 └───────────────────────────────────────┬───────────────────────────────────────────────────────┘
                                         │ HikariCP (bounded pool)
                                         ▼
                                PostgreSQL 16 (single primary)
     shows · seats · reservations · reservation_seats · user_show_quota · idempotency_keys · payments
```

No Redis, Kafka or distributed locks: every invariant is a row-level fact in one database, so the
application tier holds no state and can be scaled out without affecting correctness.

## 2. Technology stack

| Concern | Choice | Why |
|---|---|---|
| Language / framework | Java 17, Spring Boot 3.5 | Mature, explicit transaction control |
| Data access | Spring JDBC (`JdbcTemplate`) | The lock order *is* the correctness argument; explicit SQL keeps it reviewable (no ORM flush reordering) |
| Database | PostgreSQL 16 | Row locks, `ON CONFLICT`, partial unique indexes, deferrable FKs |
| Migrations | Flyway (`V1`–`V6`) | Hibernate never generates schema |
| Metrics | Micrometer → Prometheus at `/metrics` | |
| Logging | Logback + logstash encoder (JSON) | One structured line per request with `request_id` |
| Tests | JUnit 5 + Testcontainers (real Postgres, real HTTP) | |
| Load tool | Go (`burst/`), stdlib only | Real concurrent pressure, single binary |
| Packaging | Docker multi-stage, Docker Compose, Render blueprint | |

## 3. Local setup (without Docker for the app)

Requirements: JDK 17+, Maven 3.9+, Docker (for Postgres and tests).

```bash
docker run -d --name seatres-db -e POSTGRES_USER=seatres -e POSTGRES_PASSWORD=seatres \
  -e POSTGRES_DB=seatres -p 5432:5432 postgres:16-alpine
mvn spring-boot:run            # http://localhost:8080
mvn test                       # unit + Testcontainers concurrency suite (needs Docker)
```

## 4. Docker setup (recommended)

```bash
docker compose up --build      # Postgres + app on http://localhost:8080
```

The app waits for Postgres via a compose health check and Flyway connect-retries, applies
migrations, and reports ready on `/health/ready` only once the database answers.

| Env var | Default | Meaning |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local values | JDBC connection |
| `DATABASE_URL` | – | `postgres://user:pass@host:port/db` (Render/Railway style); used if `DB_URL` is unset |
| `AUTH_SECRET` | local dev value | HMAC key for tokens (≥ 32 chars). **Set your own outside local dev.** |
| `AUTH_DEV_TOKEN_ENDPOINT` | `true` | Enables `POST /auth/dev-token` |
| `DB_POOL_SIZE` | `20` | Hikari pool size |
| `DB_LOCK_TIMEOUT_MS` / `DB_STATEMENT_TIMEOUT_MS` | `5000` / `10000` | Upper bound on lock waits / statements |
| `PORT` | `8080` | HTTP port |

## 5. Database setup

Flyway runs automatically at startup (`src/main/resources/db/migration`):

| Migration | Table | Key constraints |
|---|---|---|
| V1 | `shows` | `price_paise bigint ≥ 0`, `per_user_limit > 0`, immutable after creation |
| V2 | `reservations` | status `CONFIRMED`/`CANCELLED`, `cancelled_at` iff cancelled |
| V3 | `seats`, `reservation_seats` | `UNIQUE(show_id,label)`; `CHECK (status='AVAILABLE') = (reservation_id IS NULL)`; **partial unique index: one active owner per seat** |
| V4 | `user_show_quota` | `PK(show_id,user_id)`, `seats_reserved ≥ 0` |
| V5 | `idempotency_keys` | `PK(user_id, idem_key)`, SHA-256 `request_hash`, deferred FK to reservation |
| V6 | `payments` | `UNIQUE(reservation_id)`, `UNIQUE(provider_idem_key)` |

Money is always integer paise (`bigint` / `long`, `Math.multiplyExact`); no floating point anywhere.

## 6. API

All bodies are JSON with `snake_case` fields. Every response carries `X-Request-Id`.

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| `POST` | `/auth/dev-token` | – | 200 `{token}` | 400, 404 if disabled |
| `POST` | `/shows` | admin | 201 show | 400, 401, 403 |
| `GET` | `/shows/{showId}` | – | 200 show + counts + per-seat status | 400, 404 |
| `POST` | `/shows/{showId}/reserve` | user | 201 reservation (replay: same body + `Idempotent-Replayed: true`) | 400, 401, 404, 409 |
| `GET` | `/reservations/{id}` | owner | 200 | 401, 404 |
| `POST` | `/reservations/{id}/cancel` | owner | 200 (idempotent) | 401, 404 |
| `GET` | `/admin/reconciliation` | admin | 200 `{ok, checks}` | 401, 403 |
| `GET` | `/admin/logs` | admin | 200 NDJSON of recent log lines (`request_id`, `level`, `q`, `limit` filters) | 401, 403 |
| `GET` | `/health/live` | – | 200 | – |
| `GET` | `/health/ready` | – | 200 / 503 when Postgres is unreachable | – |
| `GET` | `/metrics` | – | Prometheus text | – |

**Create show**: `{"name":"friday-night","seats":["A1","A2"],"price_paise":25000,"per_user_limit":4}`
(`per_user_limit` defaults to 4; labels `[A-Za-z0-9-]{1,16}`, unique per show).

**Reserve**: `{"seats":["A12","A13"],"idempotency_key":"client-generated-key"}` → all-or-nothing.

```json
{"reservation_id":"…","show_id":"…","user_id":"alice","seats":["A12","A13"],
 "amount_paise":50000,"status":"confirmed","created_at":"…"}
```

**Error shape** (every non-2xx):

```json
{"code":"SEAT_UNAVAILABLE","message":"one or more requested seats are unavailable","request_id":"…"}
```

| Status | Codes |
|---|---|
| 400 | `VALIDATION_FAILED`, `UNKNOWN_SEAT`, `DUPLICATE_SEAT` |
| 401 | `UNAUTHENTICATED` |
| 403 | `FORBIDDEN` (non-admin on admin endpoints) |
| 404 | `SHOW_NOT_FOUND`, `RESERVATION_NOT_FOUND` (also for other users' reservations), `NOT_FOUND` |
| 409 | `SEAT_UNAVAILABLE`, `PER_USER_LIMIT_EXCEEDED`, `IDEMPOTENCY_KEY_REUSED` |
| 503 | `DB_UNAVAILABLE`, `CONTENTION_TIMEOUT`, `OUTCOME_UNKNOWN` (all carry `Retry-After`; retry with the same idempotency key) |
| 500 | `INTERNAL_ERROR` (bugs only) |

## 7. Authentication

`Authorization: Bearer <token>` where the token is an HS256 JWT `{sub, role, iat, exp}` signed
with `AUTH_SECRET`. The verifier accepts only HS256 (no `alg:none`), checks signature in constant
time and enforces expiry.

- **Identity comes only from the token.** The reserve request DTO has no `user_id` field; a
  `"user_id": "victim"` in the body is an unknown property and is ignored. The reservation belongs
  to the token's subject (covered by `ConcurrencyIT.identityComesFromTokenNotBody`).
- A bare `Bearer user-123` is rejected: unsigned identities would let anyone be anyone.
- For demos, `POST /auth/dev-token {"user_id":"alice"}` (or `"role":"admin"`) mints a 24h token.
  Disable with `AUTH_DEV_TOKEN_ENDPOINT=false`; in production tokens come from an IdP.

## 8. Concurrency strategy (summary)

Reserve runs a lock-free **pre-check** (one statement) that can only reject or replay, then the
authoritative **transaction**, all under `READ COMMITTED`:

1. `INSERT INTO idempotency_keys … ON CONFLICT DO NOTHING`: concurrent duplicates block on the
   unique index and are answered with a replay.
2. `SELECT … FROM seats WHERE show_id=? AND label = ANY(?) ORDER BY id FOR UPDATE`: deterministic
   lock order (no deadlocks); any non-available seat → rollback → 409 (all-or-nothing).
3. `INSERT INTO user_show_quota … ON CONFLICT DO UPDATE SET n = n + k WHERE n + k <= limit`: atomic
   per-user limit, serialised on the user's row.
4. Insert reservation, flip seats (`… WHERE status='AVAILABLE'`, row count asserted), insert
   history (partial unique index backstop), payment, stored response. Commit.

Full interleaving analysis: [WRITEUP.md](WRITEUP.md).

## 9. Idempotency

Keys are scoped per user (`PK(user_id, idem_key)`). The request fingerprint is
`SHA-256("v1|<show_id>|<sorted seat labels>")`, so seat order does not matter. Same key + same
body → original response replayed (`201`, `Idempotent-Replayed: true`); same key + different body
→ `409 IDEMPOTENCY_KEY_REUSED`. The key row is written in the same transaction as the reservation,
so a failed attempt (e.g. seat taken) leaves no key and a retry is evaluated afresh; that is safe
because a failed attempt has no side effects. Replays return the stored original response verbatim,
even if the reservation was later cancelled (use `GET /reservations/{id}` for current state).

## 10. Cancellation

`CONFIRMED → CANCELLED` (terminal). In one transaction: lock reservation → lock its seats (id
order) → `UPDATE seats SET status='AVAILABLE', reservation_id=NULL WHERE reservation_id = :r`
(can only free seats this reservation still owns) → deactivate history → decrement quota → refund
payment → mark cancelled. Non-owners get 404. Re-cancelling returns 200 with the cancelled state.

## 11. Metrics (`GET /metrics`)

| Metric | Type | Notes |
|---|---|---|
| `reservation_requests_total` | counter | every reserve call |
| `reservations_confirmed_total` | counter | new reservations |
| `reservations_declined_total{reason}` | counter | `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_conflict`, `invalid_request` |
| `reservation_errors_total{type}` | counter | 5xx on the reserve path |
| `reservation_latency_seconds{outcome}` | histogram | `confirmed`/`replayed`/`declined`/`error` |
| `reservations_cancelled_total` | counter | |
| `seats_available` / `seats_held` / `seats_confirmed` | gauge | from `seats` table, every 10s |
| `reconciliation_mismatches` | gauge | sum of invariant-check violations, every 30s; **must be 0** |
| `db_transaction_failures_total{type}` | counter | `deadlock`, `lock_timeout`, `serialization`, `statement_timeout`, `connection`, `pool_timeout` |
| `db_transaction_retries_total{type}` | counter | transactions retried after a transient lock failure (rolled back, safe to rerun) |
| `http_requests_total{method,route,status}` | counter | `route` is the URI template |
| `http_request_duration_seconds{method,route,status_class}` | histogram | |
| `hikaricp_*`, `jvm_*`, `process_*` | | standard |

No label ever carries a user, show, reservation or idempotency-key id.

## 12. Logging

JSON, one object per line, to stdout. Each request produces one `access` line:

```json
{"timestamp":"…","level":"INFO","logger_name":"access","message":"request completed",
 "request_id":"5f2c…","user_id":"alice","show_id":"…","reservation_id":"…",
 "method":"POST","path":"/shows/…/reserve","status":409,"duration_ms":4,
 "error_code":"SEAT_UNAVAILABLE","service":"seat-reservation"}
```

`request_id` honours a well-formed inbound `X-Request-Id`, otherwise a UUID is generated, and it is
echoed in the response header and error body. Headers are never logged, so tokens cannot leak.
Health and metrics probes are logged only on failure.

### Metrics & logs access

Reviewers don't need platform access; everything is reachable over HTTP on the live URL.

```bash
B=https://seat-reservation-86o2.onrender.com
ADMIN=$(curl -s -XPOST $B/auth/dev-token -H 'content-type: application/json' \
  -d '{"user_id":"reviewer","role":"admin"}' | jq -r .token)

# Metrics (public): reservation outcomes, latency histogram, seat gauges, reconciliation
curl -s $B/metrics | grep -E '^(reservations_|reservation_|seats_|reconciliation_|db_transaction_)'

# Recent structured logs from this instance (NDJSON, newest last, last 2000 lines kept)
curl -s "$B/admin/logs?limit=50" -H "authorization: Bearer $ADMIN"
curl -s "$B/admin/logs?request_id=<id from any response's X-Request-Id or error body>" -H "authorization: Bearer $ADMIN"
curl -s "$B/admin/logs?level=WARN" -H "authorization: Bearer $ADMIN"
curl -s "$B/admin/logs?q=SEAT_UNAVAILABLE&limit=20" -H "authorization: Bearer $ADMIN"

# On-demand invariant checks against the database
curl -s $B/admin/reconciliation -H "authorization: Bearer $ADMIN"
```

The full log stream also goes to stdout as JSON, where the platform collects it (Render → service
→ Logs).

## 13. Burst testing

```bash
./burst.sh http://localhost:8080                        # uses local Go, else the golang image
./burst.sh https://seat-reservation-86o2.onrender.com -concurrency 200
docker compose --profile burst run --rm burst           # inside the compose network
```

It creates a fresh show (505 seats, limit 4), mints ~2,400 user tokens, then fires 20,000 shuffled
requests through a start gate: a hot-seat storm (~17,400 requests on 5 seats), 20 groups × 50
identical idempotent requests, 10 groups reusing one key with two bodies, 40 users × 10 concurrent
requests (limit test), and 1,000 overlapping multi-seat requests in random seat order. It then
checks the invariants from both the responses and server state and **exits non-zero on any failure**:
`INVARIANT`, `NO DOUBLE SELL`, `SEATS MATCH RESPONSES`, `PER USER LIMIT`, `IDENTITY FROM TOKEN`,
`IDEMPOTENCY`, `5XX`, `TRANSPORT`, `DB RECONCILIATION`.

Flags: `-requests`, `-concurrency`, `-hot-seats`, `-hot-users`, `-limit`, `-seed`, `-secret`
(mint tokens locally instead of calling `/auth/dev-token`).

## 14. Deployment (Render)

1. Push this repo to GitHub.
2. Render → **New → Blueprint** → select the repo. `render.yaml` provisions Postgres, builds the
   Dockerfile, injects `DATABASE_URL`, generates `AUTH_SECRET`, and uses `/health/ready` as the
   health check.
3. Run `./burst.sh https://seat-reservation-86o2.onrender.com -concurrency 200`.

Any Docker host works the same way: set `DATABASE_URL` (or `DB_URL`/`DB_USER`/`DB_PASSWORD`) and
`AUTH_SECRET`. Free-tier instances sleep when idle; the burst tool waits up to 3 minutes for
`/health/ready` before starting.

## 15. Example curl session

```bash
B=http://localhost:8080
ADMIN=$(curl -s -XPOST $B/auth/dev-token -H 'content-type: application/json' -d '{"user_id":"admin","role":"admin"}' | jq -r .token)
ALICE=$(curl -s -XPOST $B/auth/dev-token -H 'content-type: application/json' -d '{"user_id":"alice"}' | jq -r .token)

SHOW=$(curl -s -XPOST $B/shows -H "authorization: Bearer $ADMIN" -H 'content-type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}' | jq -r .show_id)

# reserve (201), replay (201 + Idempotent-Replayed), reuse key with other seats (409)
curl -i -XPOST $B/shows/$SHOW/reserve -H "authorization: Bearer $ALICE" -H 'content-type: application/json' \
  -d '{"seats":["A2","A1"],"idempotency_key":"order-1"}'
curl -i -XPOST $B/shows/$SHOW/reserve -H "authorization: Bearer $ALICE" -H 'content-type: application/json' \
  -d '{"seats":["A1","A2"],"idempotency_key":"order-1"}'
curl -i -XPOST $B/shows/$SHOW/reserve -H "authorization: Bearer $ALICE" -H 'content-type: application/json' \
  -d '{"seats":["A3"],"idempotency_key":"order-1"}'

curl -s $B/shows/$SHOW | jq '{total_seats, available, held, confirmed}'
curl -s -XPOST $B/reservations/<reservation_id>/cancel -H "authorization: Bearer $ALICE"
curl -s $B/metrics | grep reservations_
```

## 16. Known limitations

- **Single primary database.** Correctness relies on one PostgreSQL primary; if it is down the
  service refuses writes (503) rather than accept unsafe reservations. A replica would only serve
  `GET /shows`.
- **No holds / TTL.** Reserve confirms immediately; `HELD` exists in the schema for a
  hold-then-pay flow (see WRITEUP) but nothing writes it, so `held` is always 0.
- **Payments are logical.** A `payments` row is written atomically with the reservation; no
  external gateway is called.
- **Idempotency keys never expire.** A real system would TTL them (e.g. 24h) with a cleanup job.
- **Hot-seat throughput is bounded by one row lock**: losers queue briefly until the first winner
  commits; after that the pre-check rejects them without locks. `lock_timeout` (5s) caps any wait,
  surfacing as a retryable 503.
- **Dev token endpoint** lets anyone mint any identity, including admin, by design for the demo.
  Turn it off (`AUTH_DEV_TOKEN_ENDPOINT=false`) for anything real.
- **Show-level gauges are global** (sum over all shows) to avoid per-show label cardinality.
