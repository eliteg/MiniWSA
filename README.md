# Mini WSA — Mini Security Analytics Pipeline

A backend service that ingests security event records (DLRs), classifies and enriches them,
stores them, and exposes analytics over a REST API. Java / Spring Boot.

> Work in progress. This README grows with each milestone — it documents only what's built so
> far. Right now that's the application skeleton (`v0.1`).

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

## Test

```bash
./gradlew test
```
