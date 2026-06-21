# Mini WSA — Mini Security Analytics Pipeline

A backend service that ingests WAF security events, classifies and enriches them with threat
scores, stores them in PostgreSQL, and exposes analytics over a REST API. Java 21 / Spring Boot 3.

There are two flows. In the **sync flow** events are enriched and stored within the HTTP request —
the caller gets `201 Created` once the event is durably in the database. In the **async flow** the
ingest endpoint publishes each event to Kafka and returns `202 Accepted` immediately; a consumer
runs the same enrichment pipeline in the background. All read APIs (`stats`, `samples`, `alerts`)
are synchronous in both flows.

## Prerequisites

- **Docker** (Docker Desktop running) — brings up the app + Postgres in one command.
- **JDK 21+** — to compile and run tests locally without Docker.

## Build & Tests

```bash
# compile + unit tests (no Docker needed)
./gradlew build

# integration tests — Testcontainers handles all infrastructure automatically
./gradlew integrationTest              # all flows
./gradlew integrationTest -Ptag=sync   # sync flow only  (ApiScenarioSyncIT)
./gradlew integrationTest -Ptag=async  # async flow only (ApiScenarioAsyncIT + KafkaIngestionIntegrationTest)

# all tests
./gradlew check
```

## Run

The flow is controlled by the `APP_MODE` environment variable. Both modes start the app on
**http://localhost:8080** with Postgres included.

**Sync** (default — `APP_MODE=sync`):
```bash
docker compose up --build -d
```

**Async** (`APP_MODE=async` — also starts Kafka):
```bash
APP_MODE=async docker compose --profile streaming up --build -d
```

Check status:
```bash
docker compose ps                  # container status + health
```

Follow logs (open a separate terminal):
```bash
docker compose logs -f app         # app logs
docker compose logs -f kafka       # kafka logs (async only)
```

To switch between modes, stop with the **same profile** used to start, then restart:
```bash
# stop sync
docker compose down

# stop async (must include --profile streaming — otherwise Kafka is left running)
docker compose --profile streaming down

# then start the other mode
APP_MODE=async docker compose --profile streaming up --build -d
```

Verify: `curl localhost:8080/health` → `{"status":"UP"}`

## Architecture

### Sync flow

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
│  ┌──────────────────┐                               │
│  │   Classifier     │  category → attackType        │
│  └────────┬─────────┘                               │
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
            ▼
   ┌─────────────────┐
   │   EventStore    │  JdbcTemplate → PostgreSQL 16
   └─────────────────┘
         201 Created
```

### Async flow

```
POST /v2/events/ingest
        │
        ▼
┌───────────────┐
│  Validation   │  all-or-nothing: one invalid event rejects the whole batch
└───────┬───────┘
        │  202 Accepted (returned immediately)
        ▼
┌─────────────────┐
│  KafkaProducer  │  event published as JSON to waf-events topic
└────────┬────────┘
         │
         ▼
  [waf-events topic]
         │
         ▼
┌─────────────────┐
│  KafkaConsumer  │  retries: 2× with 1s backoff → DLQ (waf-events-dlq)
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────────────────┐
│                  EnrichmentService                  │
│              (same pipeline as sync)                │
└────────┬────────────────────────────────────────────┘
         ▼
   ┌─────────────────┐
   │   EventStore    │  JdbcTemplate → PostgreSQL 16
   └─────────────────┘
```

**Dead Letter Queue (DLQ)**

If the consumer fails to process an event after 2 retries (1 s apart), it is published to
`waf-events-dlq` and an `ERROR` log line is emitted:

```
DLQ: event key=<eventId> offset=<n> partition=0 error=<message>
```

The DLQ topic uses Kafka's default retention of **7 days**, after which messages are deleted
automatically. To inspect queued events manually:

```bash
docker exec -it miniwsa-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic waf-events-dlq --from-beginning
```

## API

### Versioning

All endpoints are prefixed with `/{version}/`. The version controls how ingest is handled;
read endpoints (`stats`, `samples`, `alerts`) behave identically in both versions.

| Version | Ingest endpoint            | Response       | Ingest behaviour                  |
|---------|----------------------------|----------------|-----------------------------------|
| v1      | `POST /v1/events/ingest`   | `201 Created`  | Enriched and stored synchronously |
| v2      | `POST /v2/events/ingest`   | `202 Accepted` | Published to Kafka, stored async  |

### `POST /{version}/events/ingest`

Accepts a single event or an array. Validation is all-or-nothing — one invalid event rejects the
entire batch (nothing is stored or published).

```bash
curl -X POST localhost:8080/{version}/events/ingest -H 'Content-Type: application/json' -d '{
  "eventId": "evt-1",
  "timestamp": "2026-05-20T14:32:10Z",
  "configId": 14227,
  "clientIp": "203.0.113.42",
  "path": "/api/v1/login",
  "rule": { "severity": "CRITICAL", "category": "INJECTION" },
  "action": "DENY"
}'
```

| Field           | Type                                                     | Required |
|-----------------|----------------------------------------------------------|----------|
| `eventId`       | string                                                   | ✓        |
| `timestamp`     | ISO-8601                                                 | ✓        |
| `configId`      | long                                                     | ✓        |
| `clientIp`      | string                                                   | ✓        |
| `path`          | string                                                   | ✓        |
| `rule.severity` | `CRITICAL` `HIGH` `MEDIUM` `LOW`                         | ✓        |
| `rule.category` | `INJECTION` `XSS` `BOT` `BRUTE_FORCE` `SCANNER` `OTHER` | ✓        |
| `action`        | `DENY` `ALERT` `MONITOR`                                 | ✓        |
| `policyId`      | string                                                   |          |
| `hostname`      | string                                                   |          |
| `httpMethod`    | string                                                   |          |
| `httpStatus`    | int                                                      |          |
| `userAgent`     | string                                                   |          |
| `geoLocation`   | `{ "countryCode": "US", "city": "New York" }`            |          |
| `requestSize`   | int                                                      |          |
| `responseTime`  | int                                                      |          |

**`201 Created`** (v1) / **`202 Accepted`** (v2)
```json
{ "accepted": 1, "receivedAt": "2026-06-19T20:15:42.123Z" }
```

**`400 Bad Request`**
```json
{ "error": "validation failed",
  "invalidEvents": [ { "index": 0, "errors": ["eventId: must not be blank"] } ] }
```

### `GET /{version}/events/stats`

Returns aggregated analytics over stored events.

| Parameter  | Type     | Description                 |
|------------|----------|-----------------------------|
| `configId` | long     | Filter to a specific config |
| `from`     | ISO-8601 | Start of time range         |
| `to`       | ISO-8601 | End of time range           |

```bash
curl -s "localhost:8080/{version}/events/stats?configId=14227&from=2026-06-01T00:00:00Z" \
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

### `GET /{version}/events/samples`

Returns paginated individual event records.

| Parameter  | Type     | Default       | Description                  |
|------------|----------|---------------|------------------------------|
| `configId` | long     | —             | Filter by config             |
| `from`     | ISO-8601 | last 24 hours | Start of time range          |
| `to`       | ISO-8601 | —             | End of time range            |
| `category` | string   | —             | `INJECTION` `XSS` `BOT` …   |
| `action`   | string   | —             | `DENY` `ALERT` `MONITOR`     |
| `limit`    | int      | 20 (max 100)  | Page size                    |
| `offset`   | int      | 0             | Pagination offset            |

```bash
curl -s "localhost:8080/{version}/events/samples?category=INJECTION&limit=10" | python3 -m json.tool
```

### `POST /{version}/alerts/define`

Defines an alert rule: fire when the count of events in a given category exceeds `threshold`
within the last `windowMinutes` minutes.

```bash
curl -X POST localhost:8080/{version}/alerts/define -H 'Content-Type: application/json' -d '{
  "category": "BOT",
  "threshold": 5,
  "windowMinutes": 10
}'
```

| Field           | Type                                                     | Required |
|-----------------|----------------------------------------------------------|----------|
| `category`      | `INJECTION` `XSS` `BOT` `BRUTE_FORCE` `SCANNER` `OTHER` | ✓        |
| `threshold`     | int (≥ 1)                                                | ✓        |
| `windowMinutes` | int (≥ 1)                                                | ✓        |

**`201 Created`**
```json
{ "id": 1, "category": "BOT", "threshold": 5, "windowMinutes": 10, "createdAt": "2026-06-20T08:00:00Z" }
```

### `GET /{version}/alerts/evaluate`

Evaluates all defined rules against current event counts.

```bash
curl -s localhost:8080/{version}/alerts/evaluate | python3 -m json.tool
```

```json
[
  {
    "ruleId": 1, "category": "BOT", "threshold": 5,
    "windowMinutes": 10, "currentCount": 11, "firing": true
  }
]
```

### `GET /health`

```bash
curl localhost:8080/health
# {"status":"UP"}
```

## Data generator

Generates realistic WAF events with 30% attack waves (bursts from the same IP hitting the same path).

### 1. Start the stack

```bash
# sync (v1)
docker compose up --build -d

# async (v2) — also starts Kafka
APP_MODE=async docker compose --profile streaming up --build -d
```

### 2. Reset the database (optional)

```bash
./gradlew resetDb
```

### 3. Generate and send events

**Option A — generate to file, then POST with curl**

The file is written to `data/events-{timestamp}.json` by default, or to a fixed path with `-Poutput`.

```bash
# generate — file saved to data/events.json
./gradlew generateData -Pcount=10000 -Poutput=data/events.json

# start the app — sync
docker compose up --build -d

# start the app — async (also starts Kafka)
APP_MODE=async docker compose --profile streaming up --build -d

# wait until the app is healthy (check Docker Desktop or poll the health endpoint)
curl localhost:8080/health   # wait for {"status":"UP"}

# send — sync
curl -X POST localhost:8080/v1/events/ingest \
  -H 'Content-Type: application/json' \
  -d @data/events.json

# send — async
curl -X POST localhost:8080/v2/events/ingest \
  -H 'Content-Type: application/json' \
  -d @data/events.json
```

**Option B — generate and POST directly via Gradle**

```bash
# sync
./gradlew generateData -Pcount=10000 -Psend=http://localhost:8080

# async
./gradlew generateData -Pcount=10000 -Psend=http://localhost:8080 -Pv2
```

| Option          | Description                                      |
|-----------------|--------------------------------------------------|
| `-Pcount=N`     | Number of events (default: 10000)                |
| `-Poutput=FILE` | Output file path (default: `data/events-{ts}.json`) |
| `-Psend=URL`    | POST directly to the API (base URL)              |
| `-Pv2`          | Use `/v2/events/ingest` instead of `/v1`         |
| `-Pbatch=N`     | Batch size when sending (default: 100)           |

### 4. Stop the stack

```bash
# sync
docker compose down

# async (must include --profile streaming to also stop Kafka)
docker compose --profile streaming down
```

## Storage choice

**PostgreSQL 16 + JdbcTemplate** — no ORM (Hibernate/JPA).

The hard queries here are analytical: `GROUP BY category`, top-N attackers, time-range
aggregates. ORMs are designed for object persistence, not aggregations — you end up dropping to
raw SQL anyway, so they add a layer without paying off. JdbcTemplate keeps the SQL explicit and
readable; every query is exactly what runs on the database.

The `EventStore` interface is the seam: swapping the implementation for a columnar engine
(ClickHouse, SingleStore) at large scale is a one-class change — the rest of the app is unchanged.

## What I would improve with more time

- **Repeat-offender window: replace with Redis.** The current implementation is in-memory. Two problems: (1) the map grows forever — IPs that stop attacking are never removed because there is no scheduled eviction; (2) if the app restarts, the window is lost and a repeat offender resets to zero. Redis solves both: each IP's data lives in a sorted set with a native TTL, so keys expire automatically and data survives restarts. The `RepeatOffenderDetector` interface is already the seam for this swap.

- **Kafka batch consumer.** The consumer currently processes one event at a time. A batch listener would consume a whole poll batch together, sort it by timestamp before enrichment, and write to the database in a single bulk insert — better throughput and no need for the producer-side sort workaround.

- **Metrics and observability.** No Micrometer metrics. Missing: Kafka consumer lag, DLQ message count, enrichment latency histogram, per-category event rate.

- **Alert evaluation push.** Currently pull-based — you call `GET /alerts/evaluate`. Should fire on a schedule or on each ingest and send a webhook or notification.

- **SingleStore for analytical queries.** PostgreSQL stores data row by row — every row is one event with all its columns packed together. This is efficient for fetching a single event by ID, but slow for analytical queries like "sum threat scores by category across 10 million events", because Postgres has to read every column of every row even though the query only touches two columns. A columnar store like SingleStore stores each column separately. The query "group by category" only reads the category and threat_score columns — skipping everything else. For the stats and alerts endpoints this is the bottleneck at scale. The `EventStore` interface is already the seam: swapping the implementation is a one-class change with no impact on the rest of the app.

## What was challenging

### The sliding window and the out-of-order bug

The repeat-offender rule is: if the same IP sends more than 5 events within any 10-minute window, add +15 to the threat score.

The window is kept in memory, grouped by minute. For every event that arrives, we clean up old minutes that can no longer affect the count — this keeps memory bounded.

The bug: the cleanup was anchored to the **current event's timestamp**. So when an event at minute 12 arrived, we deleted everything before minute 2 (12 minus 10). That sounds right — minute 2 is outside a 10-minute window ending at minute 12.

The problem is that events do not always arrive in order. A later event at minute 6 might arrive after the minute-12 event. Minute 6's window is minutes -4 to 6 — and minute 1 and 2 **are** inside that range. But they were already deleted by the minute-12 event.

The fix: anchor eviction to the **furthest timestamp seen for that IP**, not the current event, and add a grace buffer of 5 minutes. When minute 12 arrives, we now delete only before minute -3 (12 minus 10 minus 5). Minute 1 and 2 survive, and the late minute-6 event can count them correctly.

## Known limitations

- **Pagination is slow at large scale.** The samples endpoint runs `count(*)` on every request to get the total number of results. On millions of rows this is expensive — it has to scan the whole table. For now the `from` filter defaults to the last 24 hours, which keeps the scan small. A proper fix is keyset pagination: instead of `LIMIT/OFFSET`, use `WHERE (timestamp, event_id) < (last seen values)` so each page jump is fast regardless of how deep you are.

- **The repeat-offender window is in-memory and grows forever.** Every IP that ever sends an event stays in the map — there is no cleanup. Production fix: replace with Redis, where each IP's data expires automatically.

- **No warm restart.** If the app restarts, the in-memory window is gone. An IP that sent 5 events just before the restart will not be flagged on its 6th event after — the counter starts from zero. Redis fixes this too, since the data lives outside the process and survives restarts.

- **The repeat-offender score can miss events that arrived out of order.** The window only counts events that were already processed when the current event arrives. If an event from the same IP arrives late — after other events from that IP were already scored — those earlier events are not re-scored. So an IP could slip past the +15 bonus simply because its events arrived in the wrong order. The only real fix is to query all events for that IP from a shared store (Redis or the database) on each new arrival, instead of relying on what has been seen so far in memory.

- **No deduplication in the async path.** `event_id` is the primary key, so the sync path handles retries correctly — a duplicate insert fails and the client knows. In the async path, if the Kafka consumer crashes after storing an event but before committing the offset, it re-processes that event on restart. The insert fails with a PK violation, the event goes to the DLQ, and the repeat-offender window is not inflated — but the event is lost rather than gracefully skipped. Fix: use `ON CONFLICT (event_id) DO NOTHING` on insert so redelivered events are silently ignored instead of erroring.
