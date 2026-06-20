# Mini WSA — Mini Security Analytics Pipeline

A backend service that ingests WAF security events, classifies and enriches them with threat
scores, stores them in PostgreSQL, and exposes analytics over a REST API. Java 21 / Spring Boot 3.

## Prerequisites

- **Docker** (Docker Desktop running) — brings up the app + Postgres in one command.
- **JDK 21+** — to compile and run tests locally without Docker.

## Build

```bash
# compile + unit tests (no Docker needed)
./gradlew build

# integration tests (requires Docker for Testcontainers)
./gradlew integrationTest

# all tests
./gradlew check
```

## Run

```bash
docker compose up --build
```

The app starts on **http://localhost:8080**. Verify it's up:

```bash
curl localhost:8080/health
# {"status":"UP"}
```

Postgres 16 starts automatically. Connect with psql if needed:

```bash
docker compose exec postgres psql -U miniwsa -d miniwsa
```

## Architecture

**Synchronous pipeline** — enrichment happens inline within the HTTP request before the response
is returned. No message queue; the caller gets immediate confirmation that events were stored.

```
POST /v1/events/ingest
        │
        ▼
┌───────────────┐
│  Validation   │  all-or-nothing: one invalid event rejects the whole batch
└───────┬───────┘
        │  batch sorted by timestamp before enrichment
        ▼
┌─────────────────────────────────────────────────────┐
│                  EnrichmentService                  │
│                                                     │
│  for each event (in timestamp order):               │
│                                                     │
│  ┌──────────────────┐                               │
│  │   Classifier     │  category → attackType        │
│  └────────┬─────────┘                               │
│           │                                         │
│           ▼                                         │
│  ┌──────────────────────────┐                       │
│  │  RepeatOffenderWindow    │  in-memory sliding    │
│  │  (10-minute window)      │  window per clientIp  │
│  └────────┬─────────────────┘                       │
│           │  count > 5 → repeatOffender = true      │
│           ▼                                         │
│  ┌──────────────────────────┐                       │
│  │      ThreatScorer        │  severity.weight      │
│  │                          │  + action.points      │
│  │                          │  + sensitive path +15 │
│  │                          │  + repeat offender +15│
│  └────────┬─────────────────┘                       │
└───────────┼─────────────────────────────────────────┘
            │
            ▼
   ┌─────────────────┐
   │   EventStore    │  JdbcTemplate → PostgreSQL 16
   │   (Postgres 16) │  schema managed by Liquibase
   └────────┬────────┘
            │
     ┌──────┴──────┐
     ▼             ▼
GET /v1/events/stats    GET /v1/events/samples
```

## Quickest demo (no Docker needed)

Run the end-to-end integration test — it spins up a real Postgres via Testcontainers, runs the
full pipeline, and prints every raw API response to the console:

```bash
./gradlew integrationTest --tests "org.example.miniwsa.enrichment.RepeatOffenderIntegrationTest" --rerun
```

What you'll see in the output:
- **Ingest** — 10 wave events from the same IP + 1 background event → `201 Created`
- **Stats** — top attacker at `avgThreatScore=27.5` (repeat-offender +15 fired on events 6–10)
- **Samples** — individual events with `threatScore=35` for the wave and `threatScore=20` for background
- **Alerts** — a BOT rule with `threshold=5` firing because `currentCount=11`

To run all integration tests:

```bash
./gradlew integrationTest --rerun
```

## Quick demo (full stack)

**1. Start the stack** (requires Docker)
```bash
docker compose up --build
```

**2. Generate and send 10,000 events (30% attack waves)**
```bash
./gradlew generateData -Pcount=10000 -Psend=http://localhost:8080
```

Or generate to a file first, inspect/edit, then send:
```bash
./gradlew generateData -Pcount=10000
curl -X POST localhost:8080/v1/events/ingest \
  -H 'Content-Type: application/json' \
  -d @data/events-*.json
```

**3. Query statistics**
```bash
curl -s localhost:8080/v1/events/stats | python3 -m json.tool
```

**4. Browse individual events**
```bash
# first page, newest first
curl -s "localhost:8080/v1/events/samples?limit=5" | python3 -m json.tool

# filter by category and time range
curl -s "localhost:8080/v1/events/samples?category=INJECTION&from=2026-06-01T00:00:00Z&limit=5" \
  | python3 -m json.tool
```

## API

### `POST /v1/events/ingest`

Accepts a **single event or an array**. Validation is **all-or-nothing**: if any event is
invalid, the whole batch is rejected and nothing is stored.

```bash
curl -X POST localhost:8080/v1/events/ingest -H 'Content-Type: application/json' -d '{
  "eventId": "evt-1",
  "timestamp": "2026-05-20T14:32:10Z",
  "configId": 14227,
  "clientIp": "203.0.113.42",
  "path": "/api/v1/login",
  "rule": { "severity": "CRITICAL", "category": "INJECTION" },
  "action": "DENY"
}'
```

Required fields: `eventId`, `timestamp`, `configId`, `clientIp`, `path`, `rule.severity`,
`rule.category`, `action`. All other fields are optional.

**`201 Created`**
```json
{ "accepted": 1, "receivedAt": "2026-06-19T20:15:42.123Z" }
```

**`400 Bad Request`**
```json
{ "error": "validation failed",
  "invalidEvents": [ { "index": 0, "errors": ["eventId: must not be blank"] } ] }
```

### `GET /v1/events/stats`

Returns aggregated analytics over stored events.

| Parameter  | Type        | Description                          |
|------------|-------------|--------------------------------------|
| `configId` | long        | Filter to a specific config          |
| `from`     | ISO-8601    | Start of time range                  |
| `to`       | ISO-8601    | End of time range                    |

```bash
curl -s "localhost:8080/v1/events/stats?configId=14227&from=2026-06-01T00:00:00Z" \
  | python3 -m json.tool
```

```json
{
  "totalEvents": 1500,
  "byCategory": { "INJECTION": { "count": 300, "avgThreatScore": 72.5 } },
  "byAction":   { "DENY": 900, "ALERT": 400, "MONITOR": 200 },
  "topAttackers":     [ { "clientIp": "1.2.3.4", "count": 120, "avgThreatScore": 85.0 } ],
  "topTargetedPaths": [ { "path": "/admin", "count": 250 } ]
}
```

### `GET /v1/events/samples`

Returns paginated individual event records.

| Parameter  | Type     | Default       | Description                        |
|------------|----------|---------------|------------------------------------|
| `configId` | long     | —             | Filter by config                   |
| `from`     | ISO-8601 | last 24 hours | Start of time range                |
| `to`       | ISO-8601 | —             | End of time range                  |
| `category` | string   | —             | `INJECTION`, `XSS`, `BOT`, …       |
| `action`   | string   | —             | `DENY`, `ALERT`, `MONITOR`         |
| `limit`    | int      | 20 (max 100)  | Page size                          |
| `offset`   | int      | 0             | Pagination offset                  |

```bash
curl -s "localhost:8080/v1/events/samples?category=INJECTION&limit=10" | python3 -m json.tool
```

### `POST /v1/alerts/define`

Defines an alert rule: fire when the count of events in a given category exceeds `threshold`
within the last `windowMinutes` minutes.

```bash
curl -X POST localhost:8080/v1/alerts/define -H 'Content-Type: application/json' -d '{
  "category": "BOT",
  "threshold": 5,
  "windowMinutes": 10
}'
```

Required fields: `category` (`INJECTION`, `XSS`, `BOT`, `BRUTE_FORCE`, `SCANNER`, `OTHER`),
`threshold` (≥ 1), `windowMinutes` (≥ 1).

**`201 Created`**
```json
{ "id": 1, "category": "BOT", "threshold": 5, "windowMinutes": 10, "createdAt": "2026-06-20T08:00:00Z" }
```

### `GET /v1/alerts/evaluate`

Evaluates all defined rules against current event counts and returns their firing status.

```bash
curl -s localhost:8080/v1/alerts/evaluate | python3 -m json.tool
```

```json
[
  {
    "ruleId": 1,
    "category": "BOT",
    "threshold": 5,
    "windowMinutes": 10,
    "currentCount": 11,
    "firing": true
  }
]
```

`firing` is `true` when `currentCount > threshold`. Implemented as a single LEFT JOIN across all
rules — one round-trip regardless of how many rules are defined.

### `GET /health`

```bash
curl localhost:8080/health
# {"status":"UP"}
```

### Data generator

Generates realistic WAF events including attack waves (bursts from the same IP within 10 minutes):

```bash
# generate to file
./gradlew generateData -Pcount=10000

# generate and send directly
./gradlew generateData -Pcount=10000 -Psend=http://localhost:8080

# options
-Pcount=N      number of events (default: 10000)
-Psend=URL     base URL to POST to /v1/events/ingest
-Pbatch=N      batch size when sending (default: 100)
-Poutput=FILE  write to specific file (default: data/events-{timestamp}.json)
```

## Storage choice

**PostgreSQL 16 + JdbcTemplate** — no ORM (Hibernate/JPA).

The hard queries here are analytical: `GROUP BY category`, top-N attackers, time-range
aggregates. ORMs are designed for object persistence, not aggregations — you end up dropping to
raw SQL anyway, so they add a layer without paying off. JdbcTemplate keeps the SQL explicit and
readable; every query is exactly what runs on the database.

The `EventStore` interface is the seam: swapping the implementation for a columnar engine
(ClickHouse, SingleStore) at large scale is a one-class change — the rest of the app is unchanged.

## Known limitations

- **`count(*)` at big-data scale.** `GET /v1/events/samples` runs two queries per request:
  a `count(*)` to get the total and a `SELECT … LIMIT/OFFSET` for the page. `count(*)` over
  millions of rows is a full index scan even when filters are applied. Two production mitigations:
  1. **Approximate count** — use Postgres's `pg_class.reltuples` for a fast estimate.
  2. **Keyset pagination** — replace `LIMIT/OFFSET` with `WHERE (timestamp, event_id) < (?, ?)`
     to eliminate `count(*)` entirely and keep pagination O(log n) at any depth.

  Current mitigation: `from` defaults to the last 24 hours, bounding the scan to recent data.

- **Repeat-offender bonus under out-of-order delivery.** The sliding window is in-memory. Events
  inside a single ingest request are sorted by timestamp, so order within a batch is handled. But
  an event arriving significantly out of order across separate requests may miss the +15 bonus.
  Closing this fully would require counting from the database or retaining a grace buffer —
  deliberately left as documented rather than built.
