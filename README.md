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

Required fields: `eventId`, `timestamp`, `configId`, `clientIp`, `path`, `rule.severity`,
`rule.category`, `action`. All other fields are optional.

## Test

```bash
./gradlew test
```
