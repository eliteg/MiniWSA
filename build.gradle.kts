plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.example"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Integration tests (Spring + Testcontainers) live in their own source set so the fast `test`
// suite needs no Docker. Run them with `./gradlew integrationTest`; `check` runs both.
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.liquibase:liquibase-core")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Kafka (async streaming profile)
    implementation("org.springframework.kafka:spring-kafka")

    // Integration-test only: real Postgres via Testcontainers, wired by Spring Boot @ServiceConnection.
    integrationTestImplementation("org.springframework.boot:spring-boot-testcontainers")
    integrationTestImplementation("org.testcontainers:junit-jupiter")
    integrationTestImplementation("org.testcontainers:postgresql")
    integrationTestImplementation("org.testcontainers:kafka")
    integrationTestImplementation("org.awaitility:awaitility:4.2.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against Testcontainers (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform {
        val tag = project.findProperty("tag") as String?
        if (tag != null) includeTags(tag)
    }
    shouldRunAfter(tasks.test)
    // Pin a broadly-compatible Docker API version (works Docker 20.10 → 29). Without it the
    // bundled docker-java falls back to v1.32, which Docker 29+ rejects (min 1.40).
    environment("DOCKER_API_VERSION", "1.41")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.check {
    dependsOn(integrationTest)
}

tasks.register<JavaExec>("generateData") {
    group = "demo"
    description = """
        Generate random security events (30% attack waves).
        Options (pass as Gradle properties):
          -Pcount=N      number of events to generate (default: 10000)
          -Psend=URL     POST to /v1/events/ingest in batches (base URL, e.g. -Psend=http://localhost:8080)
          -Pv2           use /v2/events/ingest instead of /v1 (requires -Psend)
          -Pbatch=N      batch size when sending (default: 100)
          -Poutput=FILE  write JSON to a file instead of stdout (e.g. -Poutput=events.json)
        Examples:
          ./gradlew generateData -Poutput=events.json
          ./gradlew generateData -Pcount=500 -Poutput=events.json
          ./gradlew generateData -Pcount=50000 -Psend=http://localhost:8080
          ./gradlew generateData -Pcount=50000 -Psend=http://localhost:8080 -Pv2
    """.trimIndent()
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.example.miniwsa.generator.DataGenerator")

    val argsList = mutableListOf<String>()
    argsList += "--count=${project.findProperty("count") ?: 10_000}"
    argsList += "--batch=${project.findProperty("batch") ?: 100}"
    project.findProperty("send")?.let   { argsList += "--send=$it" }
    project.findProperty("output")?.let { argsList += "--output=$it" }
    if (project.hasProperty("v2"))       argsList += "--v2"
    args = argsList
}

tasks.register<Exec>("resetDb") {
    group = "demo"
    description = "Truncates events and alert_rules in the running Postgres container."
    commandLine("docker", "exec", "miniwsa-postgres-1",
        "psql", "-U", "miniwsa", "-d", "miniwsa",
        "-c", "DELETE FROM events; DELETE FROM alert_rules;")
}
