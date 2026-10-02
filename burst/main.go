// Burst load generator and invariant checker for the seat reservation service.
//
// It creates a fresh show, mints tokens for many users, then fires a shuffled mix of
// workloads through a pool of concurrent workers released by a single start gate:
//
//	hot      - thousands of distinct users storming a handful of hot seats
//	replay   - groups of identical requests (same user, same idempotency key, same body)
//	conflict - groups reusing one idempotency key with two different bodies
//	peruser  - single users firing many concurrent requests for distinct seats
//	multi    - overlapping 2-3 seat requests in random order (deadlock / atomicity pressure)
//
// Afterwards it verifies every invariant from the responses and from the server's state, and
// exits non-zero if any check fails.
package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type job struct {
	kind  string
	user  string
	seats []string
	key   string
}

type result struct {
	job
	status        int
	code          string
	replayed      bool
	reservationID string
	ownerID       string
	resSeats      []string
	err           error
	retries       int
	firstErr      error
	latency       time.Duration
}

var (
	base        = flag.String("base", "http://localhost:8080", "service base URL")
	total       = flag.Int("requests", 20000, "total reservation requests")
	concurrency = flag.Int("concurrency", 500, "concurrent in-flight requests")
	secret      = flag.String("secret", os.Getenv("AUTH_SECRET"), "HMAC secret to mint tokens locally (default: use /auth/dev-token)")
	hotSeats    = flag.Int("hot-seats", 5, "number of hot seats")
	hotUsers    = flag.Int("hot-users", 2000, "distinct users in the hot-seat storm")
	limit       = flag.Int("limit", 4, "per-user limit for the created show")
	seed        = flag.Int64("seed", time.Now().UnixNano(), "random seed")
	timeout     = flag.Duration("timeout", 60*time.Second, "per-request timeout")
	gaugeWait   = flag.Duration("gauge-wait", 4*time.Second, "wait after the burst before scraping /metrics again")
)

var client *http.Client

func main() {
	flag.Parse()
	*base = strings.TrimRight(*base, "/")
	rnd := rand.New(rand.NewSource(*seed))
	client = newClient(*concurrency)

	fmt.Printf("=== Seat Reservation Burst ===\nTarget: %s  requests=%d  concurrency=%d  seed=%d\n\n", *base, *total, *concurrency, *seed)
	waitReady()

	run := fmt.Sprintf("%x", rnd.Int63())[:6]
	admin := mustToken("burst-admin-"+run, "admin")

	// ---- seat layout --------------------------------------------------------------------
	const (
		replayGroups   = 20
		replayPerGroup = 50
		conflictGroups = 10
		conflictPerGrp = 20
		perUserUsers   = 40
		perUserReqs    = 10
		multiSeats     = 60
		multiReqs      = 1000
		multiUsers     = 400
	)
	next := 1
	alloc := func(n int) []string {
		s := make([]string, n)
		for i := range s {
			s[i] = fmt.Sprintf("S%d", next)
			next++
		}
		return s
	}
	hot := alloc(*hotSeats)
	replaySeats := alloc(replayGroups)
	conflictSeats := alloc(conflictGroups * 2)
	perUserSeats := alloc(perUserUsers * perUserReqs)
	multi := alloc(multiSeats)
	allSeats := append(append(append(append(append([]string{}, hot...), replaySeats...), conflictSeats...), perUserSeats...), multi...)

	showID := createShow(admin, "burst-"+run, allSeats, *limit)
	fmt.Printf("Created show %s with %d seats (per_user_limit=%d)\n", showID, len(allSeats), *limit)

	// ---- workload -----------------------------------------------------------------------
	var jobs []job
	for g := 0; g < replayGroups; g++ {
		u := fmt.Sprintf("replay-%s-%d", run, g)
		for i := 0; i < replayPerGroup; i++ {
			jobs = append(jobs, job{"replay", u, []string{replaySeats[g]}, "replay-key"})
		}
	}
	for g := 0; g < conflictGroups; g++ {
		u := fmt.Sprintf("conflict-%s-%d", run, g)
		for i := 0; i < conflictPerGrp; i++ {
			jobs = append(jobs, job{"conflict", u, []string{conflictSeats[2*g+i%2]}, "conflict-key"})
		}
	}
	for g := 0; g < perUserUsers; g++ {
		u := fmt.Sprintf("greedy-%s-%d", run, g)
		for i := 0; i < perUserReqs; i++ {
			jobs = append(jobs, job{"peruser", u, []string{perUserSeats[g*perUserReqs+i]}, fmt.Sprintf("g-%d", i)})
		}
	}
	for i := 0; i < multiReqs; i++ {
		set := map[string]bool{}
		for len(set) < 2+rnd.Intn(2) {
			set[multi[rnd.Intn(len(multi))]] = true
		}
		var seats []string
		for s := range set {
			seats = append(seats, s)
		}
		rnd.Shuffle(len(seats), func(a, b int) { seats[a], seats[b] = seats[b], seats[a] })
		jobs = append(jobs, job{"multi", fmt.Sprintf("multi-%s-%d", run, rnd.Intn(multiUsers)), seats, fmt.Sprintf("m-%d", i)})
	}
	hotCount := *total - len(jobs)
	if hotCount < 0 {
		hotCount = 0
	}
	for i := 0; i < hotCount; i++ {
		jobs = append(jobs, job{"hot", fmt.Sprintf("hot-%s-%d", run, rnd.Intn(*hotUsers)), []string{hot[rnd.Intn(len(hot))]}, fmt.Sprintf("h-%d", i)})
	}
	rnd.Shuffle(len(jobs), func(a, b int) { jobs[a], jobs[b] = jobs[b], jobs[a] })

	// ---- tokens -------------------------------------------------------------------------
	users := map[string]bool{}
	for _, j := range jobs {
		users[j.user] = true
	}
	fmt.Printf("Minting %d user tokens (%s)...\n", len(users), map[bool]string{true: "local HMAC", false: "via /auth/dev-token"}[*secret != ""])
	tokens := mintAll(users)

	// ---- fire ---------------------------------------------------------------------------
	fmt.Printf("Firing %d requests (%d hot-seat storm on %d seats)...\n\n", len(jobs), hotCount, len(hot))
	before, beforeErr := scrapeMetrics()
	results := fire(showID, jobs, tokens)

	// Let the per-show seat gauge refresh (every 2s on the server) before the second scrape.
	time.Sleep(*gaugeWait)
	after, afterErr := scrapeMetrics()
	if beforeErr != nil {
		afterErr = beforeErr
	}

	// ---- report -------------------------------------------------------------------------
	ok := report(results, showID, admin, before, after, afterErr)
	if !ok {
		os.Exit(1)
	}
}

// ------------------------------------------------------------------------------- execution

func fire(showID string, jobs []job, tokens map[string]string) []result {
	results := make([]result, len(jobs))
	work := make(chan int, len(jobs))
	for i := range jobs {
		work <- i
	}
	close(work)
	gate := make(chan struct{})
	var wg sync.WaitGroup
	var done int64
	for w := 0; w < *concurrency; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-gate
			for i := range work {
				results[i] = reserve(showID, jobs[i], tokens[jobs[i].user])
				atomic.AddInt64(&done, 1)
			}
		}()
	}
	start := time.Now()
	stop := make(chan struct{})
	go func() {
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				fmt.Printf("  ... %d/%d done (%.0f req/s)\n", atomic.LoadInt64(&done), len(jobs), float64(atomic.LoadInt64(&done))/time.Since(start).Seconds())
			case <-stop:
				return
			}
		}
	}()
	close(gate)
	wg.Wait()
	close(stop)
	el := time.Since(start)
	fmt.Printf("\nCompleted %d requests in %s (%.0f req/s)\n\n", len(jobs), el.Round(time.Millisecond), float64(len(jobs))/el.Seconds())
	return results
}

func reserve(showID string, j job, token string) result {
	body, _ := json.Marshal(map[string]any{"seats": j.seats, "idempotency_key": j.key})
	start := time.Now()
	// A transport error means no HTTP response arrived. Retry with the SAME idempotency key, as a
	// real client must: if the first attempt did reach the server, the retry is answered with the
	// original outcome instead of a second reservation.
	var resp *http.Response
	var raw []byte
	var err, firstErr error
	retries := 0
	for attempt := 1; attempt <= 3; attempt++ {
		resp, raw, err = do("POST", "/shows/"+showID+"/reserve", token, body)
		if err == nil {
			break
		}
		if firstErr == nil {
			firstErr = err
		}
		if attempt < 3 {
			retries++
			time.Sleep(time.Duration(attempt) * 500 * time.Millisecond)
		}
	}
	r := result{job: j, latency: time.Since(start), err: err, retries: retries, firstErr: firstErr}
	if err != nil {
		return r
	}
	r.status = resp.StatusCode
	r.replayed = resp.Header.Get("Idempotent-Replayed") == "true"
	var parsed struct {
		Code          string   `json:"code"`
		ReservationID string   `json:"reservation_id"`
		UserID        string   `json:"user_id"`
		Seats         []string `json:"seats"`
	}
	_ = json.Unmarshal(raw, &parsed)
	r.code, r.reservationID, r.ownerID, r.resSeats = parsed.Code, parsed.ReservationID, parsed.UserID, parsed.Seats
	return r
}

// ------------------------------------------------------------------------------- verification

func report(rs []result, showID, admin string, mBefore, mAfter map[string]float64, mErr error) bool {
	var confirmed, replay, seatTaken, perUser, idemConflict, other4xx, total4xx, total5xx, transport int
	retried := 0
	var netErrs []string
	var lat []time.Duration
	for _, r := range rs {
		if r.retries > 0 {
			retried++
			if len(netErrs) < 3 {
				netErrs = append(netErrs, r.firstErr.Error())
			}
		}
		switch {
		case r.err != nil:
			transport++
			continue
		case r.status == 201 && r.replayed:
			replay++
		case r.status == 201:
			confirmed++
		case r.status == 409 && r.code == "SEAT_UNAVAILABLE":
			seatTaken++
		case r.status == 409 && r.code == "PER_USER_LIMIT_EXCEEDED":
			perUser++
		case r.status == 409 && r.code == "IDEMPOTENCY_KEY_REUSED":
			idemConflict++
		case r.status >= 400 && r.status < 500:
			other4xx++
		}
		if r.status >= 400 && r.status < 500 {
			total4xx++
		}
		if r.status >= 500 {
			total5xx++
		}
		lat = append(lat, r.latency)
	}
	sort.Slice(lat, func(a, b int) bool { return lat[a] < lat[b] })
	pct := func(p float64) time.Duration {
		if len(lat) == 0 {
			return 0
		}
		return lat[int(float64(len(lat)-1)*p)].Round(time.Millisecond)
	}

	fmt.Println("=== Reservation Burst ===")
	fmt.Printf("Total requests:            %d\n\n", len(rs))
	fmt.Printf("201 confirmed:             %d\n", confirmed)
	fmt.Printf("201 idempotent_replay:     %d\n", replay)
	fmt.Printf("409 seat_taken:            %d\n", seatTaken)
	fmt.Printf("409 per_user_limit:        %d\n", perUser)
	fmt.Printf("409 idempotency_conflict:  %d\n", idemConflict)
	fmt.Printf("other 4xx:                 %d\n", other4xx)
	fmt.Printf("4xx_total:                 %d\n", total4xx)
	fmt.Printf("5xx_total:                 %d\n", total5xx)
	fmt.Printf("transport errors:          %d\n", transport)
	fmt.Printf("network retries (same key): %d\n", retried)
	for _, e := range netErrs {
		fmt.Printf("  first error: %s\n", e)
	}
	fmt.Printf("latency p50/p95/p99/max:   %s / %s / %s / %s\n\n", pct(0.50), pct(0.95), pct(0.99), pct(1))

	pass := true
	check := func(name string, ok bool, detail string) {
		status := "PASS"
		if !ok {
			status = "FAIL"
			pass = false
		}
		fmt.Printf("%-28s %s %s\n", name+":", status, detail)
	}

	// Reservations as reported by the server, de-duplicated by id (replays share an id).
	reservations := map[string]result{}
	for _, r := range rs {
		if r.err == nil && r.status == 201 && r.reservationID != "" {
			reservations[r.reservationID] = r
		}
	}

	// NO DOUBLE SELL: no seat appears in two different reservations.
	seatOwner := map[string]string{}
	doubleSold := 0
	for id, r := range reservations {
		for _, s := range r.resSeats {
			if prev, ok := seatOwner[s]; ok && prev != id {
				doubleSold++
			}
			seatOwner[s] = id
		}
	}

	// PER USER LIMIT: per user, total seats across distinct reservations <= limit.
	perUserSeats := map[string]int{}
	ownerMismatch := 0
	for _, r := range reservations {
		perUserSeats[r.ownerID] += len(r.resSeats)
		if r.ownerID != r.user {
			ownerMismatch++
		}
	}
	overLimit := 0
	for _, n := range perUserSeats {
		if n > *limit {
			overLimit++
		}
	}

	// IDEMPOTENCY: per (user, key), at most one reservation id; replay groups all 201.
	groupIDs := map[string]map[string]bool{}
	replayGroupNon201 := 0
	for _, r := range rs {
		if r.err != nil {
			continue
		}
		g := r.user + "|" + r.key
		if r.status == 201 {
			if groupIDs[g] == nil {
				groupIDs[g] = map[string]bool{}
			}
			groupIDs[g][r.reservationID] = true
		}
		if r.kind == "replay" && r.status != 201 {
			replayGroupNon201++
		}
	}
	multiIDGroups := 0
	for _, ids := range groupIDs {
		if len(ids) > 1 {
			multiIDGroups++
		}
	}

	// Server-side state.
	var show struct {
		Total     int `json:"total_seats"`
		Available int `json:"available"`
		Held      int `json:"held"`
		Confirmed int `json:"confirmed"`
	}
	_, raw, err := do("GET", "/shows/"+showID, "", nil)
	if err == nil {
		err = json.Unmarshal(raw, &show)
	}
	fmt.Println("=== Reconciliation ===")
	if err != nil {
		fmt.Printf("could not fetch show state: %v\n", err)
		return false
	}
	fmt.Printf("total:      %d\navailable:  %d\nheld:       %d\nconfirmed:  %d\n\n", show.Total, show.Available, show.Held, show.Confirmed)

	check("INVARIANT", show.Available+show.Held+show.Confirmed == show.Total,
		fmt.Sprintf("(%d + %d + %d == %d)", show.Available, show.Held, show.Confirmed, show.Total))
	check("NO DOUBLE SELL", doubleSold == 0, fmt.Sprintf("(%d seats in >1 reservation)", doubleSold))
	check("SEATS MATCH RESPONSES", show.Confirmed == len(seatOwner),
		fmt.Sprintf("(server confirmed=%d, seats in 201 responses=%d)", show.Confirmed, len(seatOwner)))
	check("PER USER LIMIT", overLimit == 0, fmt.Sprintf("(%d users over limit %d)", overLimit, *limit))
	check("IDENTITY FROM TOKEN", ownerMismatch == 0, fmt.Sprintf("(%d reservations owned by someone else)", ownerMismatch))
	check("IDEMPOTENCY", multiIDGroups == 0 && replayGroupNon201 == 0,
		fmt.Sprintf("(%d keys with >1 reservation, %d non-201 in replay groups)", multiIDGroups, replayGroupNon201))
	check("5XX", total5xx == 0, fmt.Sprintf("(%d)", total5xx))
	check("TRANSPORT", transport == 0, fmt.Sprintf("(%d requests got no HTTP response)", transport))

	var rec struct {
		OK     bool             `json:"ok"`
		Checks map[string]int64 `json:"checks"`
	}
	_, raw, err = do("GET", "/admin/reconciliation", admin, nil)
	if err == nil && json.Unmarshal(raw, &rec) == nil && rec.Checks != nil {
		check("DB RECONCILIATION", rec.OK, fmt.Sprintf("%v", rec.Checks))
	} else {
		check("DB RECONCILIATION", false, fmt.Sprintf("(could not run: %v)", err))
	}

	// METRICS: counter deltas across the burst must equal what this client observed, and the
	// per-show seat gauge must equal the API's view of the show.
	fmt.Println("\n=== Metrics reconciliation (/metrics vs this client vs API) ===")
	if mErr != nil {
		check("METRICS RECONCILE", false, fmt.Sprintf("(could not scrape /metrics: %v)", mErr))
		return pass
	}
	startBefore, startAfter := sumMetric(mBefore, "process_start_time_seconds"), sumMetric(mAfter, "process_start_time_seconds")
	check("NO SERVER RESTART", startBefore == startAfter,
		fmt.Sprintf("(process_start_time_seconds %.0f -> %.0f; a restart resets counters and drops in-flight requests)", startBefore, startAfter))
	delta := func(name string, labels ...string) int {
		return int(sumMetric(mAfter, name, labels...) - sumMetric(mBefore, name, labels...))
	}
	gauge := func(status string) int {
		return int(sumMetric(mAfter, "show_seats", `show_id="`+showID+`"`, `status="`+status+`"`))
	}
	type row struct {
		label       string
		expected    int
		fromMetrics int
	}
	rows := []row{
		{"reservations_confirmed_total            (delta vs 201 new)", confirmed, delta("reservations_confirmed_total")},
		{"reservations_declined_total{seat_taken}           (delta)", seatTaken, delta("reservations_declined_total", `reason="seat_taken"`)},
		{"reservations_declined_total{per_user_limit}       (delta)", perUser, delta("reservations_declined_total", `reason="per_user_limit"`)},
		{"reservations_declined_total{idempotent_replay}    (delta)", replay, delta("reservations_declined_total", `reason="idempotent_replay"`)},
		{"reservations_declined_total{idempotency_conflict} (delta)", idemConflict, delta("reservations_declined_total", `reason="idempotency_conflict"`)},
		{"reservation_errors_total                (delta vs 5xx)", total5xx, delta("reservation_errors_total")},
		{"show_seats{status=available}     (gauge vs GET /shows)", show.Available, gauge("available")},
		{"show_seats{status=held}          (gauge vs GET /shows)", show.Held, gauge("held")},
		{"show_seats{status=confirmed}     (gauge vs GET /shows)", show.Confirmed, gauge("confirmed")},
	}
	mismatches := 0
	fmt.Printf("%-58s %10s %10s\n", "metric", "expected", "/metrics")
	for _, r := range rows {
		mark := "ok"
		if r.expected != r.fromMetrics {
			mark = "MISMATCH"
			mismatches++
		}
		fmt.Printf("%-58s %10d %10d  %s\n", r.label, r.expected, r.fromMetrics, mark)
	}
	fmt.Println()
	check("METRICS RECONCILE", mismatches == 0,
		fmt.Sprintf("(%d mismatches; exact only if no other traffic hit the service during the run)", mismatches))
	return pass
}

// scrapeMetrics fetches /metrics and returns series -> value, keyed by the full "name{labels}".
func scrapeMetrics() (map[string]float64, error) {
	resp, raw, err := do("GET", "/metrics", "", nil)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	out := map[string]float64{}
	for _, line := range strings.Split(string(raw), "\n") {
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		i := strings.LastIndex(line, " ")
		if i < 0 {
			continue
		}
		var v float64
		if _, err := fmt.Sscanf(line[i+1:], "%g", &v); err == nil {
			out[line[:i]] = v
		}
	}
	return out, nil
}

// sumMetric sums every series of the given metric name whose labels contain all the given fragments.
func sumMetric(m map[string]float64, name string, labelFragments ...string) float64 {
	total := 0.0
	for series, v := range m {
		if series != name && !strings.HasPrefix(series, name+"{") {
			continue
		}
		match := true
		for _, f := range labelFragments {
			if !strings.Contains(series, f) {
				match = false
				break
			}
		}
		if match {
			total += v
		}
	}
	return total
}

// ------------------------------------------------------------------------------- HTTP plumbing

func newClient(conc int) *http.Client {
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	tr := &http.Transport{
		// Retry failed TCP connects: the request has not been sent, so this is always safe.
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			var err error
			for attempt := 1; attempt <= 15; attempt++ {
				var c net.Conn
				if c, err = dialer.DialContext(ctx, network, addr); err == nil {
					return c, nil
				}
				time.Sleep(time.Duration(50+rand.Intn(100)) * time.Millisecond * time.Duration(attempt))
			}
			return nil, err
		},
		MaxIdleConns:        conc,
		MaxIdleConnsPerHost: conc,
		MaxConnsPerHost:     conc,
		IdleConnTimeout:     90 * time.Second,
	}
	return &http.Client{Transport: tr, Timeout: *timeout}
}

func do(method, path, token string, body []byte) (*http.Response, []byte, error) {
	req, err := http.NewRequest(method, *base+path, bytes.NewReader(body))
	if err != nil {
		return nil, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	return resp, raw, err
}

func waitReady() {
	deadline := time.Now().Add(3 * time.Minute)
	for {
		resp, _, err := do("GET", "/health/ready", "", nil)
		if err == nil && resp.StatusCode == 200 {
			return
		}
		if time.Now().After(deadline) {
			fmt.Println("service never became ready")
			os.Exit(2)
		}
		fmt.Println("waiting for /health/ready (cold start?) ...")
		time.Sleep(3 * time.Second)
	}
}

func createShow(admin, name string, seats []string, limit int) string {
	body, _ := json.Marshal(map[string]any{"name": name, "seats": seats, "price_paise": 25000, "per_user_limit": limit})
	resp, raw, err := do("POST", "/shows", admin, body)
	if err != nil || resp.StatusCode != 201 {
		fmt.Printf("create show failed: %v %s\n", err, raw)
		os.Exit(2)
	}
	var out struct {
		ShowID string `json:"show_id"`
	}
	_ = json.Unmarshal(raw, &out)
	return out.ShowID
}

func mintAll(users map[string]bool) map[string]string {
	tokens := make(map[string]string, len(users))
	var mu sync.Mutex
	names := make(chan string, len(users))
	for u := range users {
		names <- u
	}
	close(names)
	var wg sync.WaitGroup
	for w := 0; w < 32; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for u := range names {
				t := mustToken(u, "user")
				mu.Lock()
				tokens[u] = t
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	return tokens
}

func mustToken(user, role string) string {
	if *secret != "" {
		return localToken(user, role)
	}
	body, _ := json.Marshal(map[string]string{"user_id": user, "role": role})
	for attempt := 0; attempt < 5; attempt++ {
		resp, raw, err := do("POST", "/auth/dev-token", "", body)
		if err == nil && resp.StatusCode == 200 {
			var out struct {
				Token string `json:"token"`
			}
			if json.Unmarshal(raw, &out) == nil && out.Token != "" {
				return out.Token
			}
		}
		if err == nil && resp.StatusCode == 404 {
			fmt.Println("dev-token endpoint disabled on target; pass -secret / AUTH_SECRET to mint locally")
			os.Exit(2)
		}
		time.Sleep(time.Second)
	}
	fmt.Println(errors.New("could not mint token for " + user))
	os.Exit(2)
	return ""
}

func localToken(user, role string) string {
	enc := base64.RawURLEncoding
	header := enc.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	now := time.Now().Unix()
	payload, _ := json.Marshal(map[string]any{"sub": user, "role": role, "iat": now, "exp": now + 86400})
	input := header + "." + enc.EncodeToString(payload)
	mac := hmac.New(sha256.New, []byte(*secret))
	mac.Write([]byte(input))
	return input + "." + enc.EncodeToString(mac.Sum(nil))
}
