package com.vaultdrive.e2e;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AsyncPurgeIsolationTest {
    @Test
    void rejectsMissingOrNonTestVhostBeforeConnectingOrCreatingResources() {
        assertThatIllegalStateException().isThrownBy(() ->
                AsyncPurgeEndToEndTest.Isolation.requirePrivateTestEnvironment(Map.of()));
        for (String vhost : List.of("/", "vaultdrive", "", "vaultdrive_outbox_test ")) {
            assertThatIllegalStateException().isThrownBy(() ->
                    AsyncPurgeEndToEndTest.Isolation.requirePrivateTestEnvironment(Map.of("RABBITMQ_TEST_VHOST", vhost)))
                    .withMessage("Isolation requires RABBITMQ_TEST_VHOST=vaultdrive_outbox_test");
        }
    }

    @Test
    void rejectsEachMissingPrivateSettingWithoutRenderingAnyValue() {
        var environment = new HashMap<>(Map.of("RABBITMQ_TEST_VHOST", "vaultdrive_outbox_test"));
        var required = List.of("DB_PASSWORD", "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD", "S3_ACCESS_KEY", "S3_SECRET_KEY", "JWT_SECRET");
        required.forEach(name -> environment.put(name, "private-value-must-not-be-rendered"));
        for (String name : required) {
            var missing = new HashMap<>(environment);
            missing.remove(name);
            assertThatIllegalStateException().isThrownBy(() ->
                    AsyncPurgeEndToEndTest.Isolation.requirePrivateTestEnvironment(missing))
                    .withMessage("Isolation requires private environment variable " + name);
            for (String absent : List.of("", " ")) {
                var invalid = new HashMap<>(environment);
                invalid.put(name, absent);
                assertThatIllegalStateException().isThrownBy(() ->
                        AsyncPurgeEndToEndTest.Isolation.requirePrivateTestEnvironment(invalid))
                        .withMessage("Isolation requires private environment variable " + name);
            }
        }
    }
}
