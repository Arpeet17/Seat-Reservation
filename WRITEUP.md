# Engineering write-up

## Atomic decision

**Mechanism:** PostgreSQL row locks (`SELECT … FOR UPDATE`) taken in a fixed order, plus atomic
conditional statements (`INSERT … ON CONFLICT`, `UPDATE … WHERE`) and unique constraints, inside
one short transaction at **`READ COMMITTED`**. No advisory locks, no application-level locks, no
`SERIALIZABLE`.

The reservation transaction (`ReservationService.attemptReserve`), with its global lock order:

| Step | Statement | What it guarantees |
|---|---|---|
| B1 | `INSERT INTO idempotency_keys … ON CONFLICT (user_id, idem_key) DO NOTHING` | At most one in-flight execution per key. Duplicates block on the unique index entry. |
| B2 | `SELECT … FROM seats WHERE show_id=? AND label=ANY(?) ORDER BY id FOR UPDATE` | Exclusive ownership of every requested seat row, acquired in ascending id order |
| B3 | `INSERT INTO user_show_quota … ON CONFLICT DO UPDATE SET n=n+k WHERE n+k <= limit RETURNING` | Atomic check-and-increment of the user's seat count |
| B4–8 | insert reservation; `UPDATE seats … WHERE id=ANY(?) AND status='AVAILABLE'` (row count asserted); insert `reservation_seats`; insert payment; store response | Writes, all or nothing at `COMMIT` |

**Why this is race-free.** Under `READ COMMITTED`, a `FOR UPDATE` that had to wait re-reads the
*latest committed* version of the row before returning it (EvalPlanQual). So when 500 transactions
queue on seat A12, each one sees the previous winner's committed `CONFIRMED` status when its turn
comes, and rolls back with a 409. The status check runs in Java against the locked row; B2
deliberately has no `status='AVAILABLE'` filter, which could silently drop rows. If the lock holder
rolls back instead (for example, it fails the quota check), the next waiter sees `AVAILABLE` and
wins. That is why I use a blocking `FOR UPDATE` rather than `SKIP LOCKED`/`NOWAIT`, which could
decline every request and leave a free seat unsold.

**Independent backstops** catch logic bugs, not races:
- `CHECK ((status='AVAILABLE') = (reservation_id IS NULL))` on `seats`.
- `UNIQUE (seat_id) WHERE active` on `reservation_seats`: the database itself refuses a second
  active owner of a seat.
- The `UPDATE seats` row-count assertion.

**Why `READ COMMITTED` and not higher.** Every decision rests on a locked row, an atomic
conditional statement or a unique index. No decision rests on a predicate read that a concurrent
insert could invalidate; the racy `SELECT COUNT(*)` is replaced by a counter row. `REPEATABLE READ`
would be *worse* here: a `FOR UPDATE` on a row that another transaction committed after our
snapshot raises a serialization failure (40001) instead of returning the new version, so a hot
seat would turn into errors. `SERIALIZABLE` adds predicate-lock overhead and the same retry storms
without making anything more correct.

**Pre-check (Stage A).** Before the transaction, one lock-free statement reads seat availability,
the user's quota and the idempotency key from a single MVCC snapshot. It can only **reject**
(409) or **replay**, never grant. A rejection is correct as of the snapshot, so it is linearizable.
Because it is a single snapshot, any committed reservation visible in the seat data has its
idempotency row visible too, so a client retry is replayed rather than told "seat taken". This step
is purely a performance device: after the first winner commits, the other ~17,000 hot-seat
requests in the burst cost one indexed read each and never touch a lock.

## Multi-seat concurrency

- **All-or-nothing:** if any locked seat is not `AVAILABLE`, the transaction throws, rolls back
  and returns 409 `SEAT_UNAVAILABLE`. Nothing was written, so no partial reservation can exist.
- **Deterministic ordering:** seats are locked by `ORDER BY id`, and Postgres's LockRows node runs
  above the Sort, so rows are locked in sorted order regardless of the order the client sent.
- **Deadlock avoidance:** a deadlock needs a cycle (T1 holds X and wants Y; T2 holds Y and wants
  X). If every transaction acquires seats in ascending id, a transaction can only wait for a seat
  with a higher id than any it holds, so no cycle can form. The other lock classes follow one
  global order too:
  - Reserve: idempotency key → seats ↑ → quota row.
  - Cancel: reservation row → seats ↑ → quota row.
  - Reserve never locks an existing reservation row and cancel never locks an idempotency row,
    so the two paths cannot cycle with each other either.
- **Evidence:** `ConcurrencyIT.overlappingMultiSeatRequestsInReverseOrder_noDeadlocksNoDoubleSell`
  (200 concurrent shuffled 1–3 seat requests over 10 seats). The burst adds 1,000 overlapping
  multi-seat requests in random order. Zero deadlocks (`db_transaction_failures_total{type="deadlock"}`
  stays 0) and zero double-sells.

## Idempotency

- **Storage:** `idempotency_keys(user_id, idem_key)` primary key, `request_hash bytea(32)`,
  `show_id`, `reservation_id`, `response_status`, `response_body jsonb`. Keys are scoped per user,
  so two users choosing the same key never collide or see each other's data.
- **Request hash:** `SHA-256("v1|" + show_id + "|" + sorted(seat_labels).join(","))`. Seat order is
  irrelevant (`[A13,A12]` ≡ `[A12,A13]`) because the resulting reservation is identical. Duplicate
  labels in one request are rejected with 400 rather than silently de-duplicated. The `v1` prefix
  lets the canonical form change later. Hashes are compared with `MessageDigest.isEqual`.
- **Same key, same body:** returns the stored original response verbatim, with
  `Idempotent-Replayed: true`.
- **Same key, different body:** 409 `IDEMPOTENCY_KEY_REUSED`.
- **Concurrent duplicates:** T1's B1 inserts the key (uncommitted). T2's B1 hits the same unique
  index entry and **blocks** until T1 finishes.
  - If T1 commits, T2 gets the conflict and `DO NOTHING` affects 0 rows. T2 rolls back, re-reads
    the key in a new statement (which sees T1's commit) and replays.
  - If T1 rolls back, T2's insert succeeds and T2 executes.
  - So at most one reservation per key, with no application lock.
- **Why the key comes first:** if seats were locked first, a duplicate would queue on the seat
  lock, then see the seat `CONFIRMED` by its own original and answer "seat taken" instead of
  replaying.
- **Failed attempts are not recorded.** The key row lives in the same transaction as the
  reservation, so a 409 rolls it back and a retry is evaluated afresh. That is safe because a
  failed attempt has no side effects. This is a deliberate choice; the stricter Stripe-style
  alternative (persisting failures via a savepoint) is noted under limitations.
- **Lost response:** suppose the commit succeeds but the connection drops before the client gets
  the 201. The client retries with the same key and gets the replay. If the *application* sees an
  error during `COMMIT` itself, it cannot know the outcome, so it returns 503 `OUTCOME_UNKNOWN`
  ("retry with the same key") and never retries internally with a new key.
- **No double charge:** `payments.reservation_id` is `UNIQUE` and written in the same transaction.
  One key gives at most one reservation, which gives exactly one payment.
  `ConcurrencyIT.sameIdempotencyKeyConcurrently_executesOnce` asserts one payment after 100
  concurrent identical requests. With a real payment provider:
  1. Transaction 1: reservation `HELD`, payment `PENDING`.
  2. Call the provider with `Idempotency-Key = reservation_id`, outside any DB transaction; the
     provider de-duplicates retries on its side.
  3. Transaction 2: payment `CAPTURED`, seats `CONFIRMED`.
  4. A sweeper resolves stuck `PENDING` payments by querying the provider and releases expired
     holds.

## Per-user limit

A naive `SELECT COUNT(*)` then `INSERT` lets ten concurrent transactions all read "3" and all
insert. Instead, `user_show_quota(show_id, user_id)` holds the user's current seat count and B3
updates it with one statement:

```sql
INSERT INTO user_show_quota (show_id, user_id, seats_reserved) VALUES (:s, :u, :k)
ON CONFLICT (show_id, user_id) DO UPDATE
   SET seats_reserved = user_show_quota.seats_reserved + EXCLUDED.seats_reserved
 WHERE user_show_quota.seats_reserved + EXCLUDED.seats_reserved <= :limit
RETURNING seats_reserved;          -- no row returned ⇒ 409 PER_USER_LIMIT_EXCEEDED
```

Why ten or more concurrent requests from one user cannot exceed the limit:
- `ON CONFLICT DO UPDATE` takes the row lock. Only one transaction holds it at a time, and when a
  waiter gets it, the `WHERE` clause is evaluated against the **latest committed**
  `seats_reserved`, which includes every previous winner's increment.
- If the row doesn't exist yet, concurrent inserters serialize on the primary-key index entry. One
  inserts; the others wait for it, then take the update path with its committed value.
- The insert path has no `WHERE`, so the application first checks `k ≤ limit`. That check is
  stable because shows are immutable.
- A rollback (for example, a seat turns out to be taken after the quota step) undoes the increment
  with everything else. Cancellation decrements it in the same transaction that frees the seats.
- **Evidence:** 20 concurrent single-seat requests from one user with limit 4 produce exactly 4×201
  and 16×409, in both the test and the burst. The reconciliation check `quota_vs_reservations`
  recomputes the counter from `reservations` and must be 0.

## Holds & expiry

**What ships today: no holds, so nothing can expire or be stranded.** Payment is modelled as an
internal record written in the same transaction as the reservation, so a reserve goes straight to
`CONFIRMED` atomically. There is no window in which a seat is taken but unpaid, and no timer whose
failure could strand a seat. `HELD` exists in the `seats.status` check constraint, and `held` is
reported and reconciled, but nothing writes it, so `held = 0`.

**How holds with expiry would work** once payment goes to an external provider. This is the
design, not shipped code:

- **Schema:**
  - `seats.hold_expires_at timestamptz`.
  - `reservations.status` gains `HELD` and `EXPIRED`.
  - `CHECK ((status = 'HELD') = (hold_expires_at IS NOT NULL))`.
- **Reserve:** the same transaction as today, but it writes `HELD` with
  `hold_expires_at = now() + interval '10 minutes'`. Only the database clock is ever used, never
  an application clock.
- **Expiry is enforced lazily, inside the lock, without depending on a timer.** In step B2 the
  "is free" test on the locked row becomes
  `status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at < now())`. A reserver that finds an
  expired hold takes it over in the same transaction: it marks the old reservation `EXPIRED` and
  decrements that owner's quota. Quota rows are then locked in `(show_id, user_id)` order after
  the seats, so the global lock order still holds. Correctness therefore never depends on a
  sweeper having run.
- **Sweeper (housekeeping only):** every few seconds, release expired holds in batches with
  `SELECT … WHERE status = 'HELD' AND hold_expires_at < now() ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 500`.
  `SKIP LOCKED` is fine here because the sweeper makes no decision a user depends on; it just
  keeps the `available` counts and gauges fresh.
- **Confirm (payment webhook):** lock the reservation and require `HELD` and not expired, then
  set `CONFIRMED` and clear `hold_expires_at`.
  - Confirm racing expiry is decided by the row lock: whichever commits first wins.
  - A payment that lands after expiry is refunded through the provider using the same
    idempotency key (`reservation_id`), so it can never become a double charge.
- **New alerts:** a growing backlog of expired-but-unreleased holds (sweeper stuck), and the
  `held` count rising faster than the confirm rate (payment provider degraded).

## Cancellation

- **Reservation lifecycle:** `CONFIRMED → CANCELLED`. Cancelled is terminal.
- **Seat lifecycle:** `AVAILABLE → CONFIRMED → AVAILABLE`.

Cancel runs in one transaction:
1. `SELECT … FROM reservations WHERE id=? FOR UPDATE`. A non-owner gets **404**, so reservation
   ids can't be probed for existence.
2. Lock the reservation's seats in id order.
3. `UPDATE seats SET status='AVAILABLE', reservation_id=NULL WHERE reservation_id=:r AND status='CONFIRMED'`.
   This frees only seats this reservation still owns; it can never release a seat another
   reservation holds. The row count must equal `seat_count`.
4. Deactivate the `reservation_seats` rows, decrement the quota, refund the payment, mark the
   reservation cancelled.

Repeat cancels return 200 with the cancelled state. They are answered from a lock-free read,
because `CANCELLED` is terminal, so duplicates never queue on the row lock.

**Cancel racing reserve:** both need the seat lock. If the cancel commits first, the reserver
(after its lock wait) re-reads the row, sees `AVAILABLE`, and wins. If the reserver locks first, it
sees `CONFIRMED` and returns 409. `ConcurrencyIT.cancellationRacingReservations_neverTwoOwners`
runs 10 rounds of 1 cancel vs 50 snipers. 20 concurrent cancels of one reservation release the
seats and quota exactly once.

## Consistency vs availability

PostgreSQL is the single source of truth, and the service chooses **consistency**: it never
accepts a reservation it cannot durably record. If PostgreSQL is unreachable, or partitioned away
from the app:

- `/health/ready` returns 503, using its own unpooled connection with a 2s timeout, so the load
  balancer stops routing new traffic. `/health/live` stays 200 so the orchestrator does not
  restart-loop healthy processes.
- Reservations fail with **503 `DB_UNAVAILABLE`** and `Retry-After`, never a 500 and never an
  optimistic "probably reserved". Clients retry with the same idempotency key, which is always
  safe.
- A transaction cut off mid-flight is rolled back by Postgres and its locks released. A cut during
  `COMMIT` is reported as `OUTCOME_UNKNOWN`; the retry is resolved by the idempotency key.
- Verified by stopping the Postgres container under the running app: readiness 503, reserve 503
  `DB_UNAVAILABLE`, automatic recovery when Postgres returned.

**Under a partition specifically:**
- **App instances cut off from the database** cannot sell anything; they return 503 and fail
  readiness. There is no local cache or queue of "pending" reservations that could later conflict.
- **Instances on the database side** keep selling normally. Since all decisions happen inside the
  one primary, the two sides can never disagree about a seat.
- **There is no split brain to resolve,** because there is exactly one writable primary.
- **With managed failover** (synchronous replica promoted by the provider), the cost is a short
  write outage during promotion, not divergence. The trade-off is explicit: availability is given
  up during the partition or failover, and correctness never is.

Every lock wait and statement is bounded (`lock_timeout` 5s, `statement_timeout` 10s).
Transactions rolled back by a transient lock failure (`55P03`, `40P01`, `40001`) are retried up to
3 times with jitter. That is safe because the rollback also discarded the idempotency claim.

## Observability

- **Business metrics:**
  - Counters: `reservations_confirmed_total`, `reservations_declined_total{reason}`,
    `reservation_requests_total`, `reservation_errors_total{type}`.
  - Latency: `reservation_latency_seconds{outcome}` histogram.
- **DB health:** `db_transaction_failures_total{type}` (deadlock, lock_timeout, …),
  `db_transaction_retries_total{type}`, `hikaricp_connections_pending`.
- **Truth from the database:** `seats_{available,held,confirmed}` and `reconciliation_mismatches`
  are computed from the tables (seven single-statement invariant checks every 30s), never from
  in-process counters, so they cannot drift across instances or restarts.
- **Logs:** one JSON line per request with `request_id` (honouring `X-Request-Id`), `user_id`,
  `show_id`, `reservation_id`, `method`, `path`, `status`, `duration_ms`, `error_code`. Headers are
  never logged, so tokens can't leak. No high-cardinality ids appear as metric labels.

**What would page me at 2 AM:**

| Alert | Condition | Why |
|---|---|---|
| Reconciliation mismatch | `reconciliation_mismatches > 0` for 1 check | A correctness invariant is broken; stop and investigate before selling more |
| Sustained 5xx | 5xx rate > 1% of requests for 5 min | Contention never produces 5xx, so this is an infrastructure fault or a bug |
| Not ready | `/health/ready` failing on all instances for 2 min | The database is unreachable; nothing can be sold |
| Deadlocks | `increase(db_transaction_failures_total{type="deadlock"}[10m]) > 0` | The lock-order design rules them out, so any occurrence means a code-path regression |
| Lock pressure | `rate(db_transaction_retries_total[5m])` climbing, or pending pool connections > 0 for 5 min | Lock holders are starved: under-provisioned CPU or a slow DB |
| Latency | p99 `reservation_latency_seconds` > 2s for 10 min | Queueing; users time out and retry, which amplifies load |
| Success with no seat movement | `reservations_confirmed_total` rising while `seats_confirmed` is flat | Metrics or state disagree; investigate |

## Evidence

- **Test suite** (`mvn test`): 21 tests against real Postgres over real HTTP, every one followed
  by the reconciliation checks. Coverage: a 500-way single seat, 100× the same key, same key with
  different bodies (sequential and concurrent), per-user storms, all-or-nothing multi-seat,
  shuffled overlapping multi-seat (deadlock freedom), cancel vs reserve, concurrent cancels,
  non-owner cancel, identity spoofing, forged tokens, a lock held past `lock_timeout` (must be 201,
  not 503), log retrieval by request id, and readiness with the DB down.
- **Burst** (`docker compose --profile burst run --rm burst`, 20,000 requests, 500 concurrent,
  ~17,400 on 5 hot seats): every invariant passes with **0 5xx**. Warm runs take 11–22s
  (~900–1,750 req/s, p99 0.6–2.4s) on a laptop sharing 8 vCPUs between the app, Postgres and the
  load generator. At 2,000 concurrent connections: 0 5xx, with 2 lock timeouts absorbed by the
  internal retry.

**A finding from burst testing.** The first container burst produced three 503s. Sampling
`pg_stat_activity` during the run showed Postgres mostly idle while transactions sat
*idle in transaction* for up to 3.5s, and the JVM was CPU-saturated: lock *holders* were being
descheduled between statements, and waiters hit `lock_timeout`. I made three fixes:
1. Collapse the pre-check into one statement.
2. Stop rebuilding Micrometer meter ids on every request.
3. Add the bounded transient-lock retry.

Throughput roughly quadrupled and the 503s disappeared. The general lesson: lock hold time depends
on application scheduling as well as SQL. The next lever, if needed, is executing Stage B as a
single round trip (a PL/pgSQL function), so locks are held only while the database itself is
working.

## AI usage

I built this with an AI coding assistant (Claude, running in my repository) and used it heavily.
The split below is what actually happened.

**What I directed the AI to do**
- Turn my requirements brief (stack, invariants, error model, test cases, burst script, docs) into
  a design proposal *before* any code: schema, lock order, idempotency race analysis, isolation
  level. I reviewed that proposal and answered its open questions before implementation started.
- Write the implementation, Flyway migrations, the Testcontainers concurrency suite, the Go burst
  tool, the Docker/Compose/Render configuration, and drafts of the README and this write-up.
- Commit in logical, incremental steps. The history shows the real order of work, including the
  fixes that came out of burst testing.
- Debug the 503s the first container burst produced. It sampled `pg_stat_activity`, took thread
  dumps and measured per-container CPU, which showed lock holders being starved of CPU mid-transaction.

**What I decided**
- **Scope and constraints:** correctness over throughput; PostgreSQL as the single source of truth;
  no Redis, Kafka or distributed locks; money in integer paise.
- **Choices from the AI's options:**
  - HMAC-signed bearer tokens instead of a plain `Bearer user-123`.
  - 404 rather than 403 for someone else's reservation.
  - Storing only successful outcomes under an idempotency key.
  - Plain JDBC instead of JPA on the reservation path.
  - Render as the deployment target.
  - Keeping Java/Spring rather than switching stacks.
- **Accepted after review:** ordered `SELECT … FOR UPDATE` with atomic conditional statements at
  `READ COMMITTED` (over optimistic locking or `SERIALIZABLE`); the idempotency claim as the first
  statement of the transaction; a counter row for the per-user limit; all-or-nothing multi-seat;
  order-insensitive request hashing.
- **What the write-up must cover,** and that this AI-usage section stays honest.

**How I checked it.** I didn't take the AI's word that the code was correct. The claims in this
document are backed by tests that run against real PostgreSQL over real HTTP, and by the burst
tool, which checks every invariant against server state and exits non-zero on failure. I can walk
through, and extend, any part of the locking and idempotency logic.

## What I'd do next

In priority order:

1. **Holds with expiry and a real payment provider**, exactly as designed above: lazy expiry in
   the lock, a `SKIP LOCKED` sweeper, and the provider idempotency key = `reservation_id`.
2. **Run Stage B in one round trip** (a PL/pgSQL function). Then lock hold time depends only on
   the database, not on application CPU scheduling. That was the root cause of the only 503s the
   burst ever produced.
3. **Load shedding before the database:** cap in-flight reservation requests per instance and
   return a fast 503 with `Retry-After` when full, instead of letting requests queue for seconds
   on the pool.
4. **A TTL on idempotency keys** (e.g. 24h) with a cleanup job, and optionally persist failed
   outcomes via a savepoint so retries get the identical 409.
5. **Real identity:** replace the dev-token endpoint with OIDC/JWKS verification from an identity
   provider, and add per-user rate limiting.
6. **Scale reads:** serve `GET /shows/{id}` from a read replica or a short cache. Writes stay on
   the primary.
7. **Tracing** (OpenTelemetry) spanning HTTP → transaction → each SQL statement, to make lock
   waits visible per request.

## Known limitations

- Single primary database; see "Consistency vs availability".
- Holds and expiry are designed but not implemented; `held` is always 0.
- Payments are logical records; no gateway is called.
- `/admin/logs` holds the last 2,000 lines per instance in memory. It is a convenience view, not
  a log store.
- The dev token endpoint mints any identity, including admin. It is demo-only and must be
  disabled in real deployments.
