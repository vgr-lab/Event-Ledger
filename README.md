# Event Ledger

Two microservices for processing financial transaction events with idempotency,
out-of-order tolerance, observability, and resiliency — including rate limiting,
async fallback queuing, contract testing, and full OpenTelemetry pipeline.

---

## Architecture

```
                         ┌─────────────────────────────────────────────┐
                         │           Event Gateway (8082)              │
                         │                                             │
  Client ── POST /events─┤ Rate Limiter (50/s) ──▶ Validate           │
            GET  /events │                          │                  │
            GET  /balance│             Idempotency ─▶ Persist (H2)    │
                         │                          │                  │
                         │              ┌───────────▼───────────┐      │
                         │              │  Circuit Breaker       │      │
                         │              │  Retry (×3, exp+jitter)│      │
                         │              │  Timeout (2s/5s)       │      │
                         │              └───────────┬───────────┘      │
                         │                          │                  │
                         │              UP? ────────┼────── DOWN?      │
                         │              │           │           │      │
                         │          ACCEPTED    ┌───▼───┐   QUEUED     │
                         │            (201)     │ async │    (202)     │
                         │                      │ replay│              │
                         │                      └───┬───┘              │
                         │                          │ @Scheduled       │
                         │                          ▼ retry ≤5×        │
                         └──────────────────────────┼──────────────────┘
                                                    │ REST + W3C traceparent
                                                    ▼
                         ┌─────────────────────────────────────────────┐
                         │         Account Service (8081)              │
                         │                                             │
                         │  Apply transaction → Update balance (H2)    │
                         │  Idempotent (transactionId dedup)           │
                         └─────────────────────────────────────────────┘

  Services ── OTLP ──▶ OTel Collector ──▶ Jaeger UI (16686)   traces
                                     └──▶ (pipeline metrics)

  Prometheus (9090) ──scrape──▶ Gateway /actuator/prometheus
                     ──scrape──▶ Account /actuator/prometheus
                     ──scrape──▶ OTel Collector :8889
```

### Event Gateway API (port 8082)

Public-facing entry point. Receives financial events (credits/debits), applies a
per-endpoint rate limiter, validates input, enforces idempotency via `eventId`,
persists every event to its own H2 database, and forwards to the Account Service
through a resilience-wrapped REST client.

- **Account Service reachable** → event status `ACCEPTED` (201)
- **Account Service unreachable** → event status `QUEUED` (202), picked up by a
  background replay scheduler that retries up to 5 times

### Account Service (port 8081)

Internal service. Manages accounts, applies transactions, and computes balances.
Accounts are auto-created on first transaction. Balance is computed as
`SUM(CREDITs) − SUM(DEBITs)`, making it inherently order-independent.

### How they interact

1. Client submits an event to the Gateway.
2. The **rate limiter** checks POST /events (50/s prod, configurable). If
   exceeded → `429 Too Many Requests` with a `Retry-After` header.
3. Gateway validates, deduplicates, and persists locally.
4. Gateway calls the Account Service synchronously over REST.
5. If the call **succeeds** → event status = `ACCEPTED`, client gets `201`.
6. If the call **fails** (timeout, 5xx, circuit open) → event status = `QUEUED`,
   client gets `202 Accepted`.
7. The **EventReplayService** (`@Scheduled`, every 30s) picks up `QUEUED` events
   in FIFO order and retries them. After 5 failed retries → `FAILED`.
8. All reads (`GET /events`, `GET /events/{id}`) are served from the Gateway's
   local database — they work even when the Account Service is down.
9. Balance queries (`GET /accounts/{id}/balance`) proxy through the Gateway to
   the Account Service; `503` is returned when unreachable.

---

## Prerequisites

| Tool   | Version | Check              |
|--------|---------|:------------------:|
| Java   | 21+     | `java --version`   |
| Maven  | 3.9+    | `mvn --version`    |
| Docker | 27+     | `docker --version` |

No other dependencies. Maven downloads everything else automatically.

---

## Setup

```bash
git clone <repo-url>
cd event-ledger
```

Dependencies are managed by Maven — no manual install step. The first build
downloads them:

```bash
mvn clean compile
```

---

## Starting the Services

### Option 1: Docker Compose (recommended)

Build the JARs first, then compose up:

```bash
mvn clean package -DskipTests
docker compose up --build -d
```

Five containers start:

| Service          | URL                           | Notes                          |
|------------------|-------------------------------|--------------------------------|
| Account Service  | http://localhost:8081          | Starts first (health-gated)    |
| Event Gateway    | http://localhost:8082          | Waits for Account Service      |
| OTel Collector   | `gRPC :4317` / `HTTP :4318`   | Receives OTLP traces from both |
| Jaeger UI        | http://localhost:16686         | Trace visualization            |
| Prometheus       | http://localhost:9090          | Metrics scraping + dashboards  |

The Gateway's `depends_on` uses `service_healthy` — it won't start until the
Account Service passes its health check. No boot race condition.

To stop:

```bash
docker compose down
```

### Option 2: Manual (two terminals)

```bash
# Terminal 1 — start Account Service first
cd account-service
mvn spring-boot:run

# Terminal 2 — then start Event Gateway
cd event-gateway
mvn spring-boot:run
```

> **Note:** When running manually, traces are not exported (no Jaeger/Collector).
> Prometheus and the OTel Collector are Docker-only. The services log a clean
> startup with zero errors — OTLP export is only enabled via the Docker Compose
> environment variable.

### Verify

```bash
curl http://localhost:8082/health   # Gateway
curl http://localhost:8081/health   # Account Service
```

Both should return `{"status":"UP", ...}`.

---

## Running the Tests

```bash
mvn test
```

That's it. Runs all **46 tests** across both services from the project root.
No running services, Docker, or external dependencies required.

The parent POM builds `event-gateway` first (generates the Pact contract JSON),
then `account-service` (verifies the contract). Order matters for Pact.

### Test breakdown

| Class                           | Tests | Strategy         | Covers                                                        |
|---------------------------------|:-----:|------------------|---------------------------------------------------------------|
| **AccountServiceCoreTest**      |   9   | TestRestTemplate | Transactions, balance calc, idempotency, validation           |
| **AccountServiceContractTest**  |   2   | Pact Provider    | Verifies POST transaction + GET balance contracts              |
| **EventGatewayCoreTest**        |  14   | @MockBean        | Core processing, idempotency, out-of-order, validation, degradation |
| **EventGatewayResiliencyTest**  |  10   | WireMock         | Full integration, circuit breaker, trace infra, degradation    |
| **EventGatewayContractTest**    |   2   | Pact Consumer    | Defines POST transaction + GET balance contracts               |
| **EventReplayServiceTest**      |   5   | @MockBean        | Async queue: replay, retry count, exhaustion, batch, empty     |
| **EventGatewayRateLimitTest**   |   4   | Registry replace | Within/exceeds/GETs-exempt/recovery                           |

#### What each category validates

- **Core functionality** — Submit event → 201, duplicate → 200 OK (idempotent),
  GET returns chronological order despite out-of-order submission, balance
  computed correctly across credits and debits.
- **Validation** — Missing fields → 400, invalid type → 400, zero amount → 400,
  malformed timestamp → 400, unknown event → 404, unknown account → 404.
- **Resiliency** — Account Service returning 500s → Gateway returns 202 (queued),
  circuit opens after repeated failures (sliding window = 5, threshold = 50%),
  open circuit rejects instantly (< 1s), circuit recovers after service heals.
- **Graceful degradation** — `GET /events` works while circuit is open,
  balance proxy returns 503 with clear error, event persisted as QUEUED.
- **Async replay** — QUEUED events are replayed and transition to ACCEPTED,
  retry count increments on failure, events transition to FAILED after max
  retries, batch processes FIFO, empty queue is a no-op.
- **Rate limiting** — Within limit → 201, exceeds limit → 429 with body and
  `Retry-After` header, GET endpoints are never rate-limited, permits recover.
- **Contract testing** — Consumer (Gateway) defines the expected HTTP contract
  (request shape + response matchers). Provider (Account Service) replays those
  interactions against the real running app and verifies responses match.
- **Trace propagation** — ObservationRegistry is not no-op, Tracer bean is
  wired, requests reach Account Service endpoint correctly.
- **Integration** — Full Gateway → WireMock (as Account Service) round-trip.

---

## API Reference

### Submit an event

```bash
curl -X POST http://localhost:8082/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-234",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z",
    "metadata": {"source": "MB", "batchId": "B-9042"}
  }'
```

**Responses:**
- `201 Created` — event accepted, forwarded to Account Service
- `202 Accepted` — event queued for async replay (Account Service unreachable)
- `200 OK` — duplicate (idempotent replay)
- `400 Bad Request` — validation error
- `429 Too Many Requests` — rate limit exceeded (includes `Retry-After` header)

### Get an event

```bash
curl http://localhost:8082/events/evt-001
```

### List events for an account (chronological)

```bash
curl "http://localhost:8082/events?account=acct-234"
```

### Get account balance (proxied)

```bash
curl http://localhost:8082/accounts/acct-234/balance
```

### Health checks

```bash
curl http://localhost:8082/health   # Gateway
curl http://localhost:8081/health   # Account Service
```

### Prometheus metrics

```bash
curl http://localhost:8082/actuator/prometheus   # Gateway metrics
curl http://localhost:8081/actuator/prometheus   # Account Service metrics
```

---

## Resiliency Patterns

### Circuit Breaker + Retry + Timeout

The Gateway depends on the Account Service for every write. When that
dependency fails, we need three things:

1. **Don't hang** — Timeouts (connect: 2s, read: 5s) prevent threads from
   blocking indefinitely on a slow or dead downstream.

2. **Don't give up too easily** — Retry (3 attempts, 500ms exponential backoff
   with ±50% randomized jitter) absorbs transient blips like a single dropped
   connection or a brief GC pause. Jitter prevents synchronized retry storms
   from multiple Gateway instances hitting the Account Service simultaneously.

3. **Don't keep hammering a dead service** — Circuit Breaker (sliding window of
   10, 50% failure threshold) detects sustained failure and stops calling
   entirely. Requests fail fast (< 100ms) instead of waiting through timeouts
   and retries. After 30s, the circuit enters half-open and probes with a few
   calls to check if the service has recovered.

### Decoration order

```
CircuitBreaker → Retry (with jitter) → Timeout → HTTP call
```

The circuit breaker wraps everything — if it's open, no retries or network calls
happen at all. Retries wrap the timeout so each attempt gets its own timeout
budget. This is the Resilience4j recommended ordering.

### Rate Limiting

A `OncePerRequestFilter` guards `POST /events` with a Resilience4j rate limiter:

- **50 requests per second** (production default, configurable)
- Only POST /events is rate-limited; GET endpoints are unrestricted
- Exceeded → `429 Too Many Requests` with `Retry-After` header
- Zero-timeout (fail-fast) — requests never block waiting for permits

### Async Fallback Queue

When the Account Service is unreachable, events are not lost:

1. Event saved with status `QUEUED`, client receives `202 Accepted`
2. `EventReplayService` runs on a schedule (default: every 30s)
3. Processes QUEUED events in FIFO order (oldest first)
4. On success → status transitions to `ACCEPTED`
5. On failure → `retryCount` increments, stays `QUEUED`
6. After 5 retries → status transitions to `FAILED`

This decouples the client response from downstream availability. The client
always gets a quick acknowledgment, and the system self-heals when the Account
Service recovers.

### Graceful degradation

| Scenario                  | Behavior                                            |
|---------------------------|-----------------------------------------------------|
| Account Service down      | POST → 202 (event queued for async replay)          |
| Account Service down      | GET /events → 200 (reads from local DB)             |
| Account Service down      | GET /balance → 503 (clear error message)            |
| Circuit breaker open      | POST → 202 instantly (no retries, no network call)  |
| Account Service recovers  | Circuit half-opens → probes → closes → normal flow  |
| Replay picks up queued    | QUEUED → ACCEPTED (background, no client action)    |
| Rate limit exceeded       | POST → 429 with `Retry-After` header                |

---

## Observability Stack

### OpenTelemetry Pipeline

```
Services ── OTLP (gRPC/HTTP) ──▶ OTel Collector ──▶ Jaeger (traces)
```

Both services export traces via OTLP to the Collector, which batches and
forwards them to Jaeger. The Collector adds a centralized point for filtering,
sampling, and routing — services don't need to know about the trace backend.

### Prometheus Metrics

```
Prometheus ──scrape──▶ /actuator/prometheus (both services)
           ──scrape──▶ OTel Collector :8889
```

Both services expose a Prometheus-compatible metrics endpoint via Micrometer.
Custom metrics include:

| Metric                            | Type    | Labels              |
|-----------------------------------|---------|---------------------|
| `events_processed_total`          | Counter | status, type        |
| `events_processing_time_seconds`  | Timer   | outcome             |
| `transactions_processed_total`    | Counter | status, type        |
| `transactions_processing_time_*`  | Timer   | outcome             |
| `resilience4j_circuitbreaker_*`   | Gauge   | name, kind          |
| `resilience4j_ratelimiter_*`      | Gauge   | name                |

### Structured Logging

JSON-formatted logs (logstash-logback-encoder) with trace and span IDs
automatically injected. Example:

```json
{"@timestamp":"...","message":"Event accepted","eventId":"evt-001","traceId":"abc123","spanId":"def456"}
```

---

## Contract Testing (Pact)

Consumer-driven contract tests ensure the Gateway and Account Service stay
compatible without requiring both to run simultaneously.

### Flow

```
event-gateway (consumer)              account-service (provider)
        │                                      │
   mvn test                               mvn test
        │                                      │
   Generates pact JSON ──────────▶  Loads & replays interactions
   target/pacts/                    against running app context
```

### What the contract covers

| Interaction                | Method | Path                               | Provider State                                   |
|----------------------------|--------|------------------------------------|-------------------------------------------------|
| CREDIT transaction request | POST   | `/accounts/{id}/transactions`      | Account may or may not exist (auto-creates)      |
| Balance query              | GET    | `/accounts/{id}/balance`           | Account exists with balance (seeded in test)     |

The consumer test defines the expected request/response shapes using type
matchers (not exact values), so the contract is flexible enough to survive
refactors while still catching breaking changes.

---

## Design Decisions

| Concern             | Approach                                                                    |
|---------------------|-----------------------------------------------------------------------------|
| Idempotency         | `eventId` / `transactionId` as primary key — duplicates return original with `200 OK` |
| Out-of-order        | Balance = `SUM(CREDITs) − SUM(DEBITs)` — order-independent                 |
| Service isolation   | Each service owns its own H2 database, no shared state                      |
| Resiliency          | Resilience4j circuit breaker + retry (with jitter) + timeout                |
| Rate limiting       | Resilience4j rate limiter on POST /events, fail-fast with 429               |
| Async fallback      | QUEUED events replayed by background scheduler (≤5 retries)                 |
| Graceful degradation| Gateway reads from local DB; queues writes when downstream is down          |
| Tracing             | OTLP → OTel Collector → Jaeger, W3C `traceparent` propagation              |
| Metrics             | Micrometer → Prometheus scraping via `/actuator/prometheus`                 |
| Observability       | JSON structured logging (logstash-logback-encoder), custom Micrometer metrics |
| Health checks       | Each service exposes `/health` with DB connectivity diagnostics             |
| Contract testing    | Pact consumer-driven contracts between Gateway and Account Service          |

---

## Project Structure

```
event-ledger/
├── docker-compose.yml              # 5 containers: gateway, account, collector, jaeger, prometheus
├── prometheus.yml                  # Prometheus scrape configuration
├── otel-collector-config.yml       # OTel Collector pipeline (receivers → processors → exporters)
├── pom.xml                         # Aggregator POM (gateway first, then account-service)
│
├── event-gateway/
│   ├── pom.xml                     # + pact-consumer, micrometer-prometheus
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/test/eventgateway/
│       │   ├── client/             # AccountServiceClient (resilience-wrapped)
│       │   ├── controller/         # EventController (REST endpoints)
│       │   ├── dto/                # EventRequest, EventResponse
│       │   ├── exception/          # AccountServiceUnavailableException, handlers
│       │   ├── filter/             # RateLimitingFilter (429 on POST /events)
│       │   ├── model/              # Event, EventStatus (ACCEPTED/QUEUED/FAILED), TransactionType
│       │   ├── repository/         # EventRepository (JPA + findByStatus)
│       │   └── service/            # EventService, EventReplayService (@Scheduled)
│       └── test/java/com/test/eventgateway/
│           ├── EventGatewayCoreTest.java        # 14 tests (@MockBean)
│           ├── EventGatewayResiliencyTest.java   # 10 tests (WireMock)
│           ├── EventGatewayContractTest.java     #  2 tests (Pact consumer)
│           ├── EventReplayServiceTest.java       #  5 tests (async queue)
│           └── EventGatewayRateLimitTest.java    #  4 tests (rate limiting)
│
└── account-service/
    ├── pom.xml                     # + pact-provider, micrometer-prometheus
    ├── Dockerfile
    └── src/
        ├── main/java/com/test/accountservice/
        │   ├── controller/         # AccountController
        │   ├── dto/                # TransactionRequest, BalanceResponse, etc.
        │   ├── exception/          # AccountNotFoundException, handlers
        │   ├── model/              # Account, Transaction
        │   ├── repository/         # AccountRepository, TransactionRepository
        │   └── service/            # AccountManager
        └── test/java/com/test/accountservice/
            ├── AccountServiceCoreTest.java      #  9 tests
            └── AccountServiceContractTest.java  #  2 tests (Pact provider)
```
