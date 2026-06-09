# Financial Event System

Two microservices for processing financial transaction events with idempotency, out-of-order tolerance, and observability.

## Architecture

```
Browser / Client ──→ Event Gateway (8082) ──→ Account Service (8081)
```

- **Event Gateway API** — Public-facing. Receives events, validates input, enforces idempotency, stores records, and forwards to Account Service.
- **Account Service** — Internal. Manages accounts, balances, and transaction history.

## Tech Stack

- Java 21 + Spring Boot 3.3
- H2 in-memory database (one per service)
- OpenTelemetry tracing via Micrometer
- Docker Compose for orchestration
- Jaeger for trace visualization

## Quick Start

### With Docker Compose (recommended)

```bash
docker compose up --build
```

Services will be available at:
- Event Gateway: http://localhost:8082
- Account Service: http://localhost:8081
- Jaeger UI: http://localhost:16686

### Without Docker

```bash
# Terminal 1 — Account Service
cd account-service
mvn spring-boot:run

# Terminal 2 — Event Gateway
cd event-gateway
mvn spring-boot:run
```

## API Usage

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

### Get an event
```bash
curl http://localhost:8082/events/evt-001
```

### List events for an account
```bash
curl http://localhost:8082/events?account=acct-234
```

### Get account balance
```bash
curl http://localhost:8081/accounts/acct-234/balance
```

### Get account details
```bash
curl http://localhost:8081/accounts/acct-234
```

### Health checks
```bash
curl http://localhost:8082/health
curl http://localhost:8081/health
```

## Key Design Decisions

| Concern | Approach |
|---|---|
| Idempotency | `eventId` is the primary key — duplicates return the original with 200 OK |
| Out-of-order | Balance = SUM(CREDITs) − SUM(DEBITs) — order-independent |
| Service isolation | Each service has its own H2 database, no shared state |
| Resiliency | Gateway stores events even if Account Service is down (status=FAILED) |
| Tracing | Micrometer + OpenTelemetry OTLP → Jaeger |
