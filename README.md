# Mini WSA — Mini Security Analytics Pipeline

A backend service that ingests security event records (DLRs), classifies and enriches them,
stores them, and exposes analytics over a REST API. Java / Spring Boot.

> Work in progress. This README grows with each milestone — it documents only what's built so
> far. Right now that's the skeleton (`v0.1`) and the ingestion API (`v0.2`).

## Prerequisites

- **Docker** (Docker Desktop running) — to run the app.
- **JDK 21+** — to run the tests (`./gradlew`).

## Run it

```bash
docker compose up --build
```

The app starts on **http://localhost:8080**. Check it's up:

```bash
curl localhost:8080/health
# {"status":"UP"}
```

## API

### `POST /v1/events/ingest`

Accepts a **single event or an array**. Each event is validated for required fields, valid enum
values, and a valid ISO-8601 timestamp. Validation is **all-or-nothing**: if any event is
invalid, the whole batch is rejected and nothing is accepted.

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

**`201 Created`** — server stamps a `receivedAt`:

```json
{ "accepted": 1, "receivedAt": "2026-06-19T20:15:42.123Z" }
```

**`400 Bad Request`** — one body shape for every failure; `invalidEvents` names the offending
event by its index in the batch and why it failed:

```json
{ "error": "validation failed",
  "invalidEvents": [ { "index": 0, "errors": ["eventId: must not be blank"] } ] }
```

`invalidEvents` is **as precise as the failure allows**: missing/invalid required fields are
reported for **every** offending event in the batch; a value the parser rejects (bad enum or
timestamp) is reported for the **first** such event (parsing stops there); and JSON that can't be
parsed at all has no event to point to, so `invalidEvents` is empty and the reason is in `error`.

Required fields: `eventId`, `timestamp`, `configId`, `clientIp`, `path`, `rule.severity`,
`rule.category`, `action`. All other fields are optional.

## Test

```bash
./gradlew test
```

## Known limitations

- **Repeat-offender bonus under out-of-order delivery.** The "more than 5 events from one IP in
  10 minutes" bonus is counted with an in-memory sliding window (O(1) per event). Events inside a
  single ingest request are sorted by `timestamp`, so order within a batch is handled. But an event
  delivered **significantly out of order across separate requests** — an older event arriving after
  newer events have already advanced (and aged out) the window — may miss the +15, because the
  earlier events in its 10-minute window have already been evicted from memory. Counts for in-order
  and live traffic are exact. Closing this fully would require counting from the database
  (`SELECT count(*) … WHERE client_ip=? AND timestamp BETWEEN t-10m AND t`) or retaining a grace
  buffer beyond the window — the accuracy-vs-throughput trade-off, deliberately left as documented
  rather than built.
