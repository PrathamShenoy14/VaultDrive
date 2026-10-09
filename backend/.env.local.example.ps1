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
# Enable live tests only after isolated test-vhost setup is explicitly approved.
# $env:RABBITMQ_TEST_VHOST="vaultdrive_outbox_test"
