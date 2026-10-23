package com.vaultdrive.operations;

import org.springframework.core.env.Environment;
import java.time.Duration;
import java.net.URI;

public class DeploymentSafeguards {
    private final Environment env;
    private final OperationsProperties properties;
    public DeploymentSafeguards(Environment env, OperationsProperties properties) { this.env = env; this.properties = properties; }
    public void validate() {
        require(properties.isTimingValid(), "Invalid operational timing bounds");
        boolean operator = env.getProperty("vaultdrive.operator.enabled", Boolean.class, false);
        String[] processors = {"vaultdrive.outbox.publisher.enabled", "vaultdrive.purge.consumer.enabled",
                "vaultdrive.sharing.notifications.consumer.enabled", "vaultdrive.jobs.worker.enabled",
                "vaultdrive.jobs.reconciliation.enabled", "vaultdrive.multipart.expiry.enabled", "vaultdrive.jobs.finalization.enabled"};
        if (operator) {
            require(!properties.deployment() && !properties.probesEnabled(), "Operator mode cannot run deployment/probes");
            require("none".equals(env.getProperty("spring.main.web-application-type")), "Operator mode must disable HTTP");
            require(disabled("spring.flyway.enabled") && "validate".equals(env.getProperty("spring.jpa.hibernate.ddl-auto")),
                    "Operator mode requires migrations disabled and schema validation only");
            require(disabled("spring.rabbitmq.dynamic"), "Operator mode must disable automatic broker declarations");
            for (String key : processors) require(!enabled(key), "Operator mode must disable " + key);
        }
        if (!properties.deployment()) return;
        require(properties.probesEnabled(), "Deployment requires readiness probes");
        for (String key : processors) require(enabled(key), "Deployment requires " + key);
        require(disabled("spring.jpa.open-in-view"), "Deployment requires OSIV disabled");
        require(disabled("spring.rabbitmq.dynamic"), "Deployment requires explicit broker topology");
        require("validate".equals(env.getProperty("spring.jpa.hibernate.ddl-auto")), "Deployment requires schema validation");
        require("correlated".equals(env.getProperty("spring.rabbitmq.publisher-confirm-type"))
                && enabled("spring.rabbitmq.publisher-returns") && !enabled("spring.rabbitmq.template.retry.enabled"),
                "Deployment requires confirmed publication without template retries");
        for (String key : new String[]{"spring.rabbitmq.username", "spring.rabbitmq.password", "spring.rabbitmq.virtual-host",
                "storage.s3.namespace", "storage.s3.bucket", "storage.s3.region", "storage.s3.access-key", "storage.s3.secret-key"})
            require(!env.getProperty(key, "").isBlank(), "Deployment requires " + key);
        try {
            URI endpoint = URI.create(env.getRequiredProperty("storage.s3.endpoint"));
            require("https".equals(endpoint.getScheme()) && endpoint.getHost() != null && endpoint.getUserInfo() == null
                    && endpoint.getQuery() == null && endpoint.getFragment() == null, "Deployment requires a trusted HTTPS storage endpoint");
        } catch (IllegalArgumentException invalid) { throw new IllegalStateException("Deployment requires a valid HTTPS storage endpoint"); }
        for (String key : new String[]{"vaultdrive.outbox.publisher.poll-delay", "vaultdrive.jobs.reconciliation.poll-delay",
                "vaultdrive.multipart.expiry.poll-delay", "vaultdrive.jobs.worker.poll-delay", "vaultdrive.jobs.finalization.poll-delay"}) {
            Duration delay = org.springframework.boot.convert.DurationStyle.detectAndParse(env.getProperty(key, "1m"));
            require(delay.toMillis() >= 1 && delay.compareTo(properties.staleAfter()) < 0, "Processor delay must be below readiness stale-after: " + key);
        }
    }
    private boolean enabled(String key) { return env.getProperty(key, Boolean.class, false); }
    private boolean disabled(String key) { return Boolean.FALSE.equals(env.getProperty(key, Boolean.class)); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
