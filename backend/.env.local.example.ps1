$env:JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
$env:DB_PASSWORD="replace-with-local-postgres-password"
$env:JWT_SECRET="replace-with-base64-encoded-secret"
$env:S3_ACCESS_KEY="replace-with-local-s3-access-key"
$env:S3_SECRET_KEY="replace-with-local-s3-secret-key"

# Private RabbitMQ values go only in the ignored .env.local.ps1.
$env:RABBITMQ_USERNAME="replace-with-local-rabbitmq-user"
$env:RABBITMQ_PASSWORD="replace-with-local-rabbitmq-password"
$env:RABBITMQ_VHOST="/"
$env:OUTBOX_PUBLISHER_ENABLED="false"
$env:PURGE_CONSUMER_ENABLED="false"
$env:SHARING_NOTIFICATIONS_CONSUMER_ENABLED="false"
$env:JOB_WORKER_ENABLED="false"
$env:MISSING_JOB_RECONCILIATION_ENABLED="false"
$env:MULTIPART_EXPIRY_ENABLED="false"
$env:PURGE_FINALIZATION_ENABLED="false"
$env:OPERATIONS_PROBES_ENABLED="false"
# Deployment enables every processor explicitly; see docs/operational-hardening.md.
# Admission limits use vaultdrive.admission.<lane>.{concurrency,burst,per-second}.
# Conservative published-event age before missing-job recovery (minimum 1m).
$env:MISSING_JOB_RECONCILIATION_DELAY="5m"
# Enable live tests only after isolated test-vhost setup is explicitly approved.
# $env:RABBITMQ_TEST_VHOST="vaultdrive_outbox_test"
