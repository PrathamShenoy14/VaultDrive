# RabbitMQ development infrastructure (Phase 1A)

## Implementation and verification boundary

The repository supplies an additive RabbitMQ Compose file. VM deployment and live RabbitMQ verification completed on 2026-10-09 after explicit approval of the four deployment commands. Spring Boot integration, application exchanges and queues, publishing, consumers, outbox tables, durable jobs, retries, and recovery are not implemented. PostgreSQL remains the intended authority for durable work under [ADR-0008](decisions/0008-transactional-outbox-rabbitmq.md).

## Existing VM setup

Inspected on 2026-10-09 through `ssh -o BatchMode=yes vaultdrive-vm`:

- Existing Compose file: `/home/pratham/vaultdrive/compose.yaml`; project: `vaultdrive`.
- PostgreSQL: `vaultdrive-postgres`, `postgres:17`, VM port `5432`, volume `vaultdrive_postgres_data` (external).
- Garage: `vaultdrive-garage`, `dxflrs/garage:v2.3.0`, VM ports `3900` and `3903`, volumes `vaultdrive_garage_meta` and `vaultdrive_garage_data`, and the existing `garage.toml` bind mount.
- Both services use `vaultdrive_default`; their credentials already come partly from the VM's existing `.env`.

The base Compose file is VM-managed and is not copied into this repository. Add [compose.rabbitmq.yaml](../infra/rabbitmq/compose.rabbitmq.yaml) beside it; leave the base file and `.env` intact. Compose merges the new service and volume into the same project. RabbitMQ uses `vaultdrive_default` and a new `vaultdrive_rabbitmq_data` volume mounted at `/var/lib/rabbitmq`. A stable hostname preserves its node identity with the data volume. No existing service depends on RabbitMQ.

## Credentials and deployment

Remote writes and service startup require explicit approval before execution. The approved deployment first confirmed both RabbitMQ files were absent, then copied only the additive Compose file to `/home/pratham/vaultdrive/compose.rabbitmq.yaml`, generated unique credentials directly in a new mode-0600 `/home/pratham/vaultdrive/.env.rabbitmq`, and ran the validation/startup commands below. The credential file was created exclusively, without overwriting an existing file or printing values. It uses the keys in [the example](../infra/rabbitmq/.env.example). The repository's `.env.*` ignore rule excludes a local `.env.rabbitmq`; never copy existing VM secrets into tracked files. Keep credentials out of command arguments, console output, logs, and rendered Compose output. Read the credential file privately when signing in to management.

Use both environment files so the existing PostgreSQL/Garage variables remain available. Run these commands on the VM in `/home/pratham/vaultdrive`:

```sh
docker compose -p vaultdrive --env-file .env --env-file .env.rabbitmq -f compose.yaml -f compose.rabbitmq.yaml config --quiet
docker compose -p vaultdrive --env-file .env --env-file .env.rabbitmq -f compose.yaml -f compose.rabbitmq.yaml up -d --no-deps --no-recreate --wait --wait-timeout 180 rabbitmq
```

The image is pinned to `rabbitmq:4.3.6-management`, a stable patch tag listed in the [official image manifest](https://raw.githubusercontent.com/docker-library/official-images/master/library/rabbitmq). The service uses `restart: unless-stopped`. Its health check verifies that the RabbitMQ application is running and its listener ports accept connections. Container health does not prove application authentication or message delivery.

Start only `rabbitmq`. Do not run `docker compose down`, `--remove-orphans`, volume pruning, or recreate PostgreSQL/Garage. Retain the RabbitMQ volume as well. Bootstrap credentials apply only to an empty broker database; changing environment values later does not rotate an existing user. User rotation and broker upgrades require a separate task.

## Windows access through VirtualBox NAT

The SSH alias reaches the VM through Windows `127.0.0.1:2222`. A temporary SSH tunnel from Windows port `15432` to VM `127.0.0.1:5432` received a PostgreSQL SSL-negotiation response, verifying the actual forwarding path. Windows loopback ports `5672` and `15672` were available during inspection. Availability must be checked again when opening a tunnel.

RabbitMQ publishes AMQP and management only on VM `127.0.0.1`, so no new VirtualBox forwarding rule is required. After approved startup, keep this command running in a Windows terminal:

```powershell
ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -N -L 127.0.0.1:5672:127.0.0.1:5672 -L 127.0.0.1:15672:127.0.0.1:15672 vaultdrive-vm
```

Windows AMQP endpoint: `127.0.0.1:5672`. Management UI: `http://127.0.0.1:15672/`. Use the private credential file to sign in. Stop the SSH process with Ctrl+C when finished; this closes forwarding without stopping any container. These are development endpoints protected by SSH transport, with no public RabbitMQ binding.

Future Spring Boot RabbitMQ integration and RabbitMQ integration tests running on Windows must use host `127.0.0.1`, port `5672`, and credentials from ignored local environment configuration while this tunnel is running. The container hostname `rabbitmq` and the VM's loopback address are not directly reachable from Windows. Start the tunnel before starting the application or running broker-dependent tests; keep it running for their duration. Phase 1A does not add Spring AMQP dependencies, application properties, or RabbitMQ tests. The current application and test suite do not connect to RabbitMQ.

## Verification after approval

On the VM:

```sh
docker inspect --format '{{.State.Health.Status}}' vaultdrive-rabbitmq
docker exec vaultdrive-rabbitmq rabbitmq-diagnostics -q check_running
docker exec vaultdrive-rabbitmq rabbitmq-diagnostics -q check_port_connectivity
```

Through the Windows tunnel, verify an AMQP protocol response (send the AMQP 0-9-1 header and require a connection-start frame) and an HTTP 200 response from management `/`. Verify authenticated management `/api/overview` on the VM with credentials read in memory from `.env.rabbitmq`, reporting only status/version and never headers or response bodies containing sensitive data. Do not put credentials in a URL or `curl -u` command line. Compare the pre/post PostgreSQL and Garage container IDs, start times, port bindings, mounts, and networks to confirm preservation. Record actual live outcomes here after execution; a prepared configuration is not evidence of a running broker.

Configuration validation can use dummy RabbitMQ values in memory with `config --quiet`; never print the fully rendered configuration because the merged base contains existing secrets. Verify that merging adds only `rabbitmq` and `rabbitmq_data` and leaves the existing services, networks, and volumes equal to the base model. Run `git diff --check`. No Maven tests are required for this infrastructure-only slice.

Pre-deployment validation on 2026-10-09 passed: YAML parsing, Docker Compose merged-model validation with dummy credentials, equality of all existing service/network/volume definitions, rejection of either empty RabbitMQ credential, ignored-secret/tracked-example checks, documentation link checks, and `git diff --check`. The overlay was passed to Compose through stdin; these checks wrote no VM files.

Live deployment verification on 2026-10-09 passed:

- All four approved deployment commands exited successfully; RabbitMQ reached `healthy` within the startup wait.
- `check_running` and `check_port_connectivity` both passed.
- The container uses project `vaultdrive`, hostname `rabbitmq`, `unless-stopped`, existing network `vaultdrive_default`, and volume `vaultdrive_rabbitmq_data` at `/var/lib/rabbitmq`. Host bindings for `5672` and `15672` are exactly `127.0.0.1`; the credential file has mode `0600`.
- Windows received a complete AMQP 0-9-1 connection-start frame through localhost `5672` over SSH. This verifies protocol reachability, not authenticated AMQP sessions or message delivery.
- Windows management UI through localhost `15672` returned HTTP 200 and the RabbitMQ Management page. Authenticated `/api/overview` on the VM returned HTTP 200 and version `4.3.6`; credentials were never printed.
- PostgreSQL/Garage container IDs, start times, restart counts, images, Compose configuration hashes, mounts, network endpoints, port mappings, and restart policies matched the immediately captured pre-deployment baseline. Fingerprints of existing `compose.yaml`, `.env`, and `garage.toml` were unchanged.

Only the approved RabbitMQ files, image pull, container, and new volume were created. No additional infrastructure mutation was performed. The temporary Windows verification tunnel was closed; RabbitMQ remains running. Open the documented tunnel for subsequent Windows access. Persistence is configured through the volume; no restart/recreation or message-persistence experiment was performed. Application integration and broker-dependent application tests remain deferred.
