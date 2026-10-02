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
| **Try it in 5 minutes** | [End-to-end walkthrough](#15-end-to-end-walkthrough-live): every endpoint in order, with real responses |
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

## 15. End-to-end walkthrough (live)

A complete tour of the API against the live deployment, in order. Every response below is real
output from the live service, trimmed for length. Each step gives the method, URL, headers and
body, so it works in curl or in any API client (Postman, Insomnia, Thunder Client). A
copy-paste script for the whole sequence is at the end of this section.

**Base URL:** `https://seat-reservation-86o2.onrender.com` (locally: `http://localhost:8080`)

> The service runs on Render's free tier. If it has been idle, the first request can take up to
> a minute while it wakes.

### Phase 1: Setup

**Step 1. Check the service is up**
```
GET https://seat-reservation-86o2.onrender.com/health/ready
```
```json
{"database":"UP","status":"UP"}
```

**Step 2. Get an admin token**
```
POST https://seat-reservation-86o2.onrender.com/auth/dev-token
Content-Type: application/json

{"user_id":"admin","role":"admin"}
```
```json
{"expires_in":86400,"user_id":"admin","role":"admin","token":"eyJhbGciOiJIUzI1NiIs..."}
```
Save `token` as **ADMIN_TOKEN**. Tokens are valid for 24 hours.

**Step 3. Get tokens for two users.** Same endpoint, two calls:
```
POST https://seat-reservation-86o2.onrender.com/auth/dev-token
{"user_id":"alice"}        → ALICE_TOKEN

POST https://seat-reservation-86o2.onrender.com/auth/dev-token
{"user_id":"bob"}          → BOB_TOKEN
```

**Step 4. Create a show (admin only)**
```
POST https://seat-reservation-86o2.onrender.com/shows
Content-Type: application/json
Authorization: Bearer ADMIN_TOKEN

{"name":"friday-night","seats":["A1","A2","A3","A4","A5","A6"],"price_paise":25000,"per_user_limit":4}
```
**201 Created**
```json
{"show_id":"de286b78-10aa-4b80-a17c-938d3278c815","name":"friday-night","price_paise":25000,
 "per_user_limit":4,"total_seats":6,"available":6,"held":0,"confirmed":0,
 "seats":[{"label":"A1","status":"available"}, ...]}
```
Save `show_id` as **SHOW_ID**.

### Phase 2: Booking scenarios

Every row below is
`POST https://seat-reservation-86o2.onrender.com/shows/SHOW_ID/reserve` with headers
`Content-Type: application/json` and `Authorization: Bearer <token>`.

| Step | Token | Body | Response | What it proves |
|---|---|---|---|---|
| **5** | ALICE | `{"seats":["A1","A2"],"idempotency_key":"alice-order-1"}` | **201** `{"reservation_id":"b0ad4611-…","user_id":"alice","seats":["A1","A2"],"amount_paise":50000,"status":"confirmed"}` | Booking works; the amount is 2 × 25000 paise. Save `reservation_id` as **RESERVATION_ID**. |
| **6** | ALICE | `{"seats":["A2","A1"],"idempotency_key":"alice-order-1"}` | **201**, header `Idempotent-Replayed: true`, **same** `reservation_id` | A retry never books or charges twice. Seat order doesn't matter. |
| **7** | ALICE | `{"seats":["A3"],"idempotency_key":"alice-order-1"}` | **409** `IDEMPOTENCY_KEY_REUSED` | Reusing a key with a different request is rejected |
| **8** | BOB | `{"seats":["A1"],"idempotency_key":"bob-order-1"}` | **409** `SEAT_UNAVAILABLE` | A seat is never sold twice |
| **9** | BOB | `{"seats":["A3","A1"],"idempotency_key":"bob-order-2"}` | **409** `SEAT_UNAVAILABLE`, and A3 stays `available` | All-or-nothing: no partial booking |
| **10** | ALICE | `{"seats":["A3","A4","A5"],"idempotency_key":"alice-order-2"}` | **409** `PER_USER_LIMIT_EXCEEDED` | Alice holds 2 seats; 2 + 3 exceeds the limit of 4 |

**Identity can't be spoofed.** Add `"user_id":"bob"` to Alice's request body and the reservation
still belongs to `alice`: identity comes only from the signed token, and body fields claiming
identity are ignored.

### Phase 3: Inspect state

**Step 11. Show state** (public, also opens in a browser)
```
GET https://seat-reservation-86o2.onrender.com/shows/SHOW_ID
```
```json
{"total_seats":6,"available":4,"held":0,"confirmed":2,
 "seats":[{"label":"A1","status":"confirmed"},{"label":"A2","status":"confirmed"},
          {"label":"A3","status":"available"}, ...]}
```
`available + held + confirmed == total_seats` (4 + 0 + 2 = 6), always.

**Step 12. View a reservation** (owner only)
```
GET https://seat-reservation-86o2.onrender.com/reservations/RESERVATION_ID
Authorization: Bearer ALICE_TOKEN
```
**200**: Alice's reservation with `"status":"confirmed"`.

### Phase 4: Cancellation

| Step | Request | Response | What it proves |
|---|---|---|---|
| **13** | `POST https://seat-reservation-86o2.onrender.com/reservations/RESERVATION_ID/cancel` with **BOB_TOKEN** | **404** `RESERVATION_NOT_FOUND` | Only the owner can cancel. Other users can't even confirm that the reservation exists. |
| **14** | Same URL with **ALICE_TOKEN** | **200** `"status":"cancelled","cancelled_at":"…"` | The owner can cancel. A1 and A2 are released and Alice's quota is freed. Repeat calls return 200 again. |
| **15** | `POST …/shows/SHOW_ID/reserve`, BOB_TOKEN, `{"seats":["A1"],"idempotency_key":"bob-order-3"}` | **201** `"user_id":"bob","seats":["A1"]` | Released seats can be sold again |

### Phase 5: Security

**Step 16. A request without a token**
```
POST https://seat-reservation-86o2.onrender.com/shows/SHOW_ID/reserve
Content-Type: application/json

{"seats":["A4"],"idempotency_key":"x"}
```
**401** `{"code":"UNAUTHENTICATED","message":"missing or invalid bearer token"}`. A forged or
expired token also gets 401. A non-admin token on `POST /shows` gets **403**.

### Phase 6: Observability

| Step | Request | What you see |
|---|---|---|
| **17** | `GET https://seat-reservation-86o2.onrender.com/metrics` (public, opens in a browser) | Prometheus metrics: `reservations_confirmed_total`, `reservations_declined_total{reason="seat_taken"}`, `seats_available`, `reconciliation_mismatches`, latency histograms |
| **18** | `GET https://seat-reservation-86o2.onrender.com/admin/logs?limit=20` with `Authorization: Bearer ADMIN_TOKEN` | Recent JSON log lines. Filter with `?request_id=<id from any response>`, `?level=WARN` or `?q=SEAT_UNAVAILABLE`. |
| **19** | `GET https://seat-reservation-86o2.onrender.com/admin/reconciliation` with `Authorization: Bearer ADMIN_TOKEN` | `{"ok":true,"checks":{…}}`: seven integrity checks run directly against the database, each of which must be 0 |

Every response, including errors, carries an `X-Request-Id` header. Error bodies repeat it as
`request_id`, so any failure can be traced in the logs (step 18).

### Phase 7: Concurrency at scale (one command)

```bash
./burst.sh https://seat-reservation-86o2.onrender.com -concurrency 100
```
This fires 20,000 concurrent requests (a hot-seat storm, duplicate idempotency keys, per-user
floods, overlapping multi-seat requests) and ends with PASS/FAIL for every invariant. The last
live run passed every check with zero 5xx responses. See [section 13](#13-burst-testing).

### The whole walkthrough as one script

Run this in Git Bash, macOS or Linux. It needs only `curl`.

```bash
B=https://seat-reservation-86o2.onrender.com      # or http://localhost:8080
R=$RANDOM                                         # unique user names per run (see note below)
tok() { curl -s -XPOST $B/auth/dev-token -H 'content-type: application/json' \
        -d "{\"user_id\":\"$1\",\"role\":\"${2:-user}\"}" | sed 's/.*"token":"\([^"]*\)".*/\1/'; }

curl -s $B/health/ready; echo                                                   # 1
ADMIN=$(tok admin admin); ALICE=$(tok alice$R); BOB=$(tok bob$R)                # 2-3

SHOW=$(curl -s -XPOST $B/shows -H "authorization: Bearer $ADMIN" -H 'content-type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3","A4","A5","A6"],"price_paise":25000,"per_user_limit":4}' \
  | sed 's/.*"show_id":"\([^"]*\)".*/\1/'); echo "SHOW=$SHOW"                   # 4

res() { curl -s -w "  [HTTP %{http_code}]\n" -XPOST $B/shows/$SHOW/reserve \
        -H "authorization: Bearer $1" -H 'content-type: application/json' -d "$2"; }

OUT=$(res $ALICE '{"seats":["A1","A2"],"idempotency_key":"order-1"}'); echo "$OUT"   # 5  201
RID=$(echo "$OUT" | sed 's/.*"reservation_id":"\([^"]*\)".*/\1/')
res $ALICE '{"seats":["A2","A1"],"idempotency_key":"order-1"}'                   # 6  201 replay
res $ALICE '{"seats":["A3"],"idempotency_key":"order-1"}'                        # 7  409 key reused
res $BOB   '{"seats":["A1"],"idempotency_key":"order-1"}'                        # 8  409 seat taken
res $BOB   '{"seats":["A3","A1"],"idempotency_key":"order-2"}'                   # 9  409 all-or-nothing
res $ALICE '{"seats":["A3","A4","A5"],"idempotency_key":"order-2"}'              # 10 409 per-user limit

curl -s $B/shows/$SHOW; echo                                                    # 11 show state
curl -s $B/reservations/$RID -H "authorization: Bearer $ALICE"; echo            # 12 owner view
curl -s -w "  [HTTP %{http_code}]\n" -XPOST $B/reservations/$RID/cancel -H "authorization: Bearer $BOB"    # 13 404
curl -s -w "  [HTTP %{http_code}]\n" -XPOST $B/reservations/$RID/cancel -H "authorization: Bearer $ALICE"  # 14 200
res $BOB '{"seats":["A1"],"idempotency_key":"order-3"}'                          # 15 201 resold
curl -s -w "  [HTTP %{http_code}]\n" -XPOST $B/shows/$SHOW/reserve -H 'content-type: application/json' \
  -d '{"seats":["A4"],"idempotency_key":"x"}'                                    # 16 401

curl -s $B/metrics | grep '^reservations_'                                      # 17 metrics
curl -s "$B/admin/logs?limit=5" -H "authorization: Bearer $ADMIN"               # 18 logs
curl -s $B/admin/reconciliation -H "authorization: Bearer $ADMIN"; echo         # 19 integrity
```

> **Repeating the walkthrough:** idempotency keys belong to a user and are remembered across
> shows. If `alice` reuses `alice-order-1` on a new show, she correctly gets
> `409 IDEMPOTENCY_KEY_REUSED`. Use fresh keys or fresh user names on each run (the script uses
> `$RANDOM` for this).

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
