package org.example.miniwsa;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("sync")
@Tag("sync")
class ApiScenarioSyncIT extends ApiScenarioIntegrationTest {

    @Override
    String version() { return "v1"; }
}
