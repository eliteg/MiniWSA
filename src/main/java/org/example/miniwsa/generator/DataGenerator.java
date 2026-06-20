package org.example.miniwsa.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.example.miniwsa.event.Action;
import org.example.miniwsa.event.Category;
import org.example.miniwsa.event.Event;
import org.example.miniwsa.event.GeoLocation;
import org.example.miniwsa.event.Rule;
import org.example.miniwsa.event.Severity;

/**
 * Standalone data generator. Produces realistic security events including attack waves
 * (bursts from the same IP to the same path).
 *
 * Usage (Gradle properties):
 *   ./gradlew generateData                                           # 10,000 events to stdout
 *   ./gradlew generateData -Pcount=500                              # custom count
 *   ./gradlew generateData -Pcount=10000 -Psend=http://localhost:8080  # POST to ingest API
 *   ./gradlew generateData -Pcount=50000 -Psend=http://localhost:8080 -Pbatch=200
 */
public class DataGenerator {

    private static final String[] PATHS = {
        "/api/v1/login", "/api/v1/users", "/api/v1/orders", "/api/v1/payments",
        "/api/v1/products", "/api/v1/auth/token", "/api/v1/upload", "/api/v1/export",
        "/admin/dashboard", "/admin/users", "/admin/config", "/search", "/graphql"
    };

    private static final long[] CONFIG_IDS = {14227L, 99001L, 55123L, 78432L};

    private static final String[] HOSTNAMES = {
        "api.example.com", "admin.example.com", "app.example.com"
    };

    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "curl/7.68.0",
        "python-requests/2.28.0",
        "Go-http-client/1.1",
        "Nikto/2.1.6",
        "sqlmap/1.7.2",
        "masscan/1.3"
    };

    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};

    private static final String[][] GEO = {
        {"CN", "Beijing"}, {"RU", "Moscow"}, {"US", "New York"}, {"BR", "Sao Paulo"},
        {"KP", "Pyongyang"}, {"UA", "Kyiv"}, {"DE", "Berlin"},
        {"NL", "Amsterdam"}, {"SG", "Singapore"}
    };


    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static void main(String[] args) throws Exception {
        int count     = 10_000;
        int batchSize = 100;
        String sendTo = null;

        for (String arg : args) {
            if (arg.startsWith("--count="))  count     = Integer.parseInt(arg.substring(8));
            if (arg.startsWith("--batch="))  batchSize = Integer.parseInt(arg.substring(8));
            if (arg.startsWith("--send="))   sendTo    = arg.substring(7);
        }

        String outputFile = "data/events-" + Instant.now().toString().replace(":", "-") + ".json";
        for (String arg : args) {
            if (arg.startsWith("--output=")) outputFile = arg.substring(9);
        }

        System.err.printf("Generating %,d events (30%% attack waves)...%n", count);
        List<Event> events = generate(count);
        String json = MAPPER.writeValueAsString(events);

        if (sendTo != null) {
            sendInBatches(events, sendTo, batchSize);
        } else {
            java.nio.file.Path path = java.nio.file.Path.of(outputFile);
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, json);
            System.err.printf("Written to %s%n", path.toAbsolutePath());
        }
    }

    // ── generation ──────────────────────────────────────────────────────────

    private static List<Event> generate(int total) {
        ThreadLocalRandom rng     = ThreadLocalRandom.current();
        Instant           now     = Instant.now();
        Instant           weekAgo = now.minus(7, ChronoUnit.DAYS);
        long              rangeSec = ChronoUnit.SECONDS.between(weekAgo, now);

        List<Event> events = new ArrayList<>(total);

        // 30% of events are wave traffic
        int waveTarget = total * 30 / 100;

        // Build 5-8 attacker profiles: fixed IP + target path + category
        int numProfiles = 5 + rng.nextInt(4);
        List<AttackerProfile> profiles = new ArrayList<>();
        Category[] waveCategories = {Category.INJECTION, Category.XSS, Category.BOT};
        for (int i = 0; i < numProfiles; i++) {
            profiles.add(new AttackerProfile(
                    randomIp(rng),
                    PATHS[rng.nextInt(PATHS.length)],
                    waveCategories[rng.nextInt(waveCategories.length)],
                    weekAgo.plusSeconds(rng.nextLong(rangeSec - 3600))
            ));
        }

        // Emit wave bursts: 50-200 events per burst within a 10-minute window
        int waveEmitted = 0;
        int profileIdx  = 0;
        while (waveEmitted < waveTarget) {
            AttackerProfile p     = profiles.get(profileIdx++ % profiles.size());
            int             burst = Math.min(50 + rng.nextInt(150), waveTarget - waveEmitted);
            for (int i = 0; i < burst; i++) {
                Instant ts = p.startTime().plusSeconds(rng.nextInt(600));
                events.add(buildEvent(rng, p.ip(), p.path(), p.category(), ts));
            }
            waveEmitted += burst;
        }

        // Fill remaining with background noise
        Category[] allCategories = Category.values();
        while (events.size() < total) {
            Instant ts = weekAgo.plusSeconds(rng.nextLong(rangeSec));
            events.add(buildEvent(rng,
                    randomIp(rng),
                    PATHS[rng.nextInt(PATHS.length)],
                    allCategories[rng.nextInt(allCategories.length)],
                    ts));
        }

        Collections.shuffle(events);
        return events;
    }

    private static Event buildEvent(ThreadLocalRandom rng, String ip,
                                    String path, Category category, Instant ts) {
        Severity severity = Severity.values()[rng.nextInt(Severity.values().length)];
        Action   action   = Action.values()[rng.nextInt(Action.values().length)];
        String[] geo      = GEO[rng.nextInt(GEO.length)];

        Rule rule = new Rule(category.ruleId, category.ruleName, category.ruleMessage, severity, category);
        GeoLocation geoLocation = new GeoLocation(geo[0], geo[1]);

        return new Event(
                "gen-" + UUID.randomUUID(),
                ts,
                CONFIG_IDS[rng.nextInt(CONFIG_IDS.length)],
                "policy-" + (1000 + rng.nextInt(9000)),
                ip,
                HOSTNAMES[rng.nextInt(HOSTNAMES.length)],
                path,
                METHODS[rng.nextInt(METHODS.length)],
                Action.DENY.equals(action) ? 403 : 200,
                USER_AGENTS[rng.nextInt(USER_AGENTS.length)],
                rule,
                action,
                geoLocation,
                100 + rng.nextInt(9900),
                50  + rng.nextInt(2000));
    }

    // ── HTTP sender ──────────────────────────────────────────────────────────

    private static void sendInBatches(List<Event> events, String baseUrl,
                                       int batchSize) throws Exception {
        HttpClient client  = HttpClient.newHttpClient();
        String     url     = baseUrl.replaceAll("/$", "") + "/v1/events/ingest";
        int        batches = (events.size() + batchSize - 1) / batchSize;
        int        sent    = 0;

        System.err.printf("POSTing to %s in %d batches of %d...%n", url, batches, batchSize);

        List<List<Event>> pages = IntStream.range(0, batches)
                .mapToObj(i -> events.subList(i * batchSize, Math.min((i + 1) * batchSize, events.size())))
                .toList();

        for (int i = 0; i < pages.size(); i++) {
            List<Event> batch = pages.get(i);
            String body = MAPPER.writeValueAsString(batch);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            sent += batch.size();
            System.err.printf("Batch %d/%d — %d events → HTTP %d%n",
                    i + 1, batches, batch.size(), resp.statusCode());
        }
        System.err.printf("%nDone. %,d events sent to %s%n", sent, url);
    }

    private static String randomIp(ThreadLocalRandom rng) {
        return rng.nextInt(1, 256) + "." + rng.nextInt(256) + "."
                + rng.nextInt(256) + "." + rng.nextInt(1, 256);
    }

    record AttackerProfile(String ip, String path, Category category, Instant startTime) {}
}
