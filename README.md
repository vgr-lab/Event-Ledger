# Event Ledger

Two microservices for processing financial transaction events with idempotency,
out-of-order tolerance, observability, and resiliency.

---

## Architecture

```
                         ┌──────────────────────────────────────────┐
                         │           Event Gateway (8082)           │
                         │                                          │
  Client ── POST /events─┤  Validate → Idempotency → Persist (H2)  │
            GET  /events │                    │                     │
            GET  /balance│          ┌─────────▼──────────┐          │
                         │          │  Circuit Breaker    │          │
                         │          │  Retry (×3, exp.)   │          │
                         │          │  Timeout (2s/5s)    │          │
                         │          └─────────┬──────────┘          │
                         └────────────────────┼─────────────────────┘
                                              │ REST + W3C traceparent
                                              ▼
                         ┌──────────────────────────────────────────┐
                         │         Account Service (8081)           │
                         │                                          │
                         │  Apply transaction → Update balance (H2) │
                         │  Idempotent (transactionId dedup)        │
                         └──────────────────────────────────────────┘

  Traces ─────────────────────────────────▶  Jaeger UI (16686)
```

### Event Gateway API (port 8082)

Public-facing entry point. Receives financial events (credits/debits), validates
input, enforces idempotency via `eventId`, persists every event to its own H2
database, and forwards to the Account Service through a resilience-wrapped REST
client. If the Account Service is unreachable, the event is saved as `FAILED`
and the client receives a `503` with a clear explanation.

### Account Service (port 8081)

Internal service. Manages accounts, applies transactions, and computes balances.
Accounts are auto-created on first transaction. Balance is computed as
`SUM(CREDITs) − SUM(DEBITs)`, making it inherently order-independent.

### How they interact

1. Client submits an event to the Gateway.
2. Gateway validates, deduplicates, persists locally, then calls the Account
   Service synchronously over REST.
3. If the call succeeds → event status = `ACCEPTED`.
4. If the call fails (timeout, 5xx, circuit open) → event status = `FAILED`,
   client gets `503`.
5. All reads (`GET /events`, `GET /events/{id}`) are served from the Gateway's
   local database — they work even when the Account Service is down.
6. Balance queries (`GET /accounts/{id}/balance`) proxy through the Gateway to
   the Account Service; `503` is returned when unreachable.

---

## Prerequisites

| Tool   | Version | Check              |
|--------|---------|--------------------|
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

Three containers start:

| Service         | URL                        | Notes                          |
|-----------------|----------------------------|--------------------------------|
| Account Service | http://localhost:8081       | Starts first (health-gated)    |
| Event Gateway   | http://localhost:8082       | Waits for Account Service      |
| Jaeger UI       | http://localhost:16686      | Trace visualization            |

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

> **Note:** When running manually, traces are not exported (no Jaeger). The
> services log a clean startup with zero errors — OTLP export is only enabled
> via the Docker Compose environment variable.

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

That's it. Runs all 32 tests across both services from the project root.
No running services, Docker, or external dependencies required.

### Test breakdown

| Class                         | Tests | Strategy         | Covers                                               |
|-------------------------------|:-----:|------------------|------------------------------------------------------|
| **AccountServiceCoreTest**    |   9   | TestRestTemplate | Transactions, balance calc, idempotency, validation   |
| **EventGatewayCoreTest**      |  13   | @MockBean        | Core processing, idempotency, out-of-order, validation, graceful degradation |
| **EventGatewayResiliencyTest**|  10   | WireMock         | Full integration flow, circuit breaker, trace infra, degradation under failure |

#### What each category validates

- **Core functionality** — Submit event → 201, duplicate → 200 OK (idempotent),
  GET returns chronological order despite out-of-order submission, balance
  computed correctly across credits and debits.
- **Validation** — Missing fields → 400, invalid type → 400, zero amount → 400,
  malformed timestamp → 400, unknown event → 404, unknown account → 404.
- **Resiliency** — Account Service returning 500s → Gateway returns 503,
  circuit opens after repeated failures (sliding window = 5, threshold = 50%),
  open circuit rejects instantly (< 1s), circuit recovers after service heals.
- **Graceful degradation** — `GET /events` works while circuit is open,
  balance proxy returns 503 with clear error, event persisted as FAILED.
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
- `201 Created` — event accepted
- `200 OK` — duplicate (idempotent replay)
- `400 Bad Request` — validation error
- `503 Service Unavailable` — event persisted but Account Service unreachable

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

---

## Resiliency Pattern Choice

### Why Circuit Breaker + Retry + Timeout?

The Gateway depends on the Account Service for every write. When that
dependency fails, we need three things:

1. **Don't hang** — Timeouts (connect: 2s, read: 5s) prevent threads from
   blocking indefinitely on a slow or dead downstream.

2. **Don't give up too easily** — Retry (3 attempts, 500ms exponential backoff)
   absorbs transient blips like a single dropped connection or a brief GC pause.

3. **Don't keep hammering a dead service** — Circuit Breaker (sliding window of
   10, 50% failure threshold) detects sustained failure and stops calling
   entirely. Requests fail fast (< 100ms) instead of waiting through timeouts
   and retries. After 30s, the circuit enters half-open and probes with a few
   calls to check if the service has recovered.

### Why Circuit Breaker as the primary pattern?

Retries and timeouts are reactive — they handle individual request failures.
Circuit Breaker is **adaptive** — it *learns* from failure history and changes
behavior. Once open, it:

- Protects the downstream service from being overwhelmed during recovery
- Frees Gateway threads to handle other requests
- Gives clear, fast feedback to clients (`503` in milliseconds)
- Self-heals automatically when the downstream recovers

### Decoration order

```
CircuitBreaker → Retry → Timeout → HTTP call
```

The circuit breaker wraps everything — if it's open, no retries or network calls
happen at all. Retries wrap the timeout so each attempt gets its own timeout
budget. This is the Resilience4j recommended ordering.

### Graceful degradation

The Gateway doesn't just fail — it degrades gracefully:

| Scenario                  | Behavior                                           |
|---------------------------|----------------------------------------------------|
| Account Service down      | POST → 503 (event persisted as FAILED for audit)   |
| Account Service down      | GET /events → 200 (reads from local DB)            |
| Account Service down      | GET /balance → 503 (clear error message)           |
| Circuit breaker open      | POST → 503 instantly (no retries, no network call) |
| Account Service recovers  | Circuit half-opens → probes → closes → normal flow |

---

## Design Decisions

| Concern            | Approach                                                        |
|--------------------|-----------------------------------------------------------------|
| Idempotency        | `eventId` / `transactionId` as primary key — duplicates return original with `200 OK` |
| Out-of-order       | Balance = `SUM(CREDITs) − SUM(DEBITs)` — order-independent     |
| Service isolation  | Each service owns its own H2 database, no shared state          |
| Resiliency         | Resilience4j circuit breaker + retry + timeout on Gateway→Account calls |
| Graceful degradation | Gateway reads from local DB when Account Service is down      |
| Tracing            | Micrometer + OpenTelemetry OTLP → Jaeger, W3C `traceparent` propagation |
| Observability      | JSON structured logging (logstash-logback-encoder), custom Micrometer metrics |
| Health checks      | Each service exposes `/health` with DB connectivity diagnostics |

---

## Project Structure

```
event-ledger/
├── docker-compose.yml          # 3 containers: gateway, account, jaeger
├── pom.xml                     # Aggregator POM (builds both services)
├── event-gateway/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/test/eventgateway/
│       │   ├── client/         # AccountServiceClient (resilience-wrapped)
│       │   ├── controller/     # EventController (REST endpoints)
│       │   ├── dto/            # EventRequest, EventResponse
│       │   ├── exception/      # AccountServiceUnavailableException, handlers
│       │   ├── model/          # Event, EventStatus, TransactionType
│       │   ├── repository/     # EventRepository (JPA)
│       │   └── service/        # EventService (business logic)
│       └── test/java/com/test/eventgateway/
│           ├── EventGatewayCoreTest.java       # 13 tests (@MockBean)
│           └── EventGatewayResiliencyTest.java  # 10 tests (WireMock)
└── account-service/
    ├── pom.xml
    ├── Dockerfile
    └── src/
        ├── main/java/com/test/accountservice/
        │   ├── controller/     # AccountController
        │   ├── dto/            # TransactionRequest, BalanceResponse, etc.
        │   ├── exception/      # AccountNotFoundException, handlers
        │   ├── model/          # Account, Transaction
        │   ├── repository/     # AccountRepository, TransactionRepository
        │   └── service/        # AccountManager
        └── test/java/com/test/accountservice/
            └── AccountServiceCoreTest.java      # 9 tests
```
