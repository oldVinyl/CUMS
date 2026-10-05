# Production deployment and recovery

These files implement the local deployment and encrypted recovery tooling. They do not establish production acceptance: finish CHECKLIST before handling funds. Google/Paystack/SMTP acceptance, historical financial workflows and retention administration remain open.

## Prepare an isolated host

Use a Linux host with Docker Compose, a domain pointed at it, and ports 80/443 open. Start with 2 vCPU / 4 GB RAM and monitor memory/disk usage. PostgreSQL is internal only; private receipt uploads are a separate volume and are not served by Caddy. Keep the development/mock database separate.

Copy `deploy/.env.production.example` to `.env.production` in the repository root and run `chmod 600 .env.production`. Use independent random 32-byte Base64 values for APP_ENCRYPTION_KEY and AUTH_THROTTLE_KEY, a unique database password of at least 20 characters, the real domain, and owner-supplied provider credentials. Never put production secrets in Git. Mail can stay disabled while its outbox queues notifications; test sender/TLS/delivery before enabling it. Google remains unavailable until its web client ID is supplied.

```
docker compose --env-file .env.production -f deploy/docker-compose.prod.yml config --quiet
docker compose --env-file .env.production -f deploy/docker-compose.prod.yml build
docker compose --env-file .env.production -f deploy/docker-compose.prod.yml up -d postgres
```

The `prod` profile rejects development accounts/samples, malformed/placeholder key material, short database passwords, insecure cookies, a non-HTTPS/mismatched passkey host, fake payments and enabled SMTP without TLS. It cannot determine whether externally supplied credentials actually work; test them separately. Keep both encryption keys recoverable in an independent protected secret store. Changing APP_ENCRYPTION_KEY without a planned migration makes existing encrypted fields unreadable.

## First leadership accounts

On a **fresh database only**, create a temporary root `.env.bootstrap` (chmod 600) with BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PASSWORD, BOOTSTRAP_PRESIDENT_EMAIL and BOOTSTRAP_PRESIDENT_PASSWORD. Use two different people/accounts and unique temporary passphrases satisfying the password policy.

```
docker compose --env-file .env.production -f deploy/docker-compose.prod.yml run --rm --no-deps --env-file .env.bootstrap -e SPRING_PROFILES_ACTIVE=prod,bootstrap backend
```

Wait for the application to finish starting, then Ctrl+C to end the one-off container. Provisioning is atomic, requires an empty account table, records audit metadata, creates only ADMIN/PRESIDENT grants and forces password replacement/protected-method setup on first sign-in. Re-running bootstrap against an existing account table fails. Remove `.env.bootstrap` and its temporary passwords from the host after secure delivery to the two owners. Do not keep the bootstrap profile enabled during normal operation.

```
docker compose --env-file .env.production -f deploy/docker-compose.prod.yml up -d
```

Check HTTPS, `/actuator/health`, sign-in, cookies, permissions, CSRF/origin rejection and independent maker/checker approval. ADMIN production grants are deliberately narrower than the local developer account. Import real data only after the local dry-run/reconciliation in LEGACY_IMPORT and an independent checker review.

The gateway has a fixed private address (172.30.0.10). Its `backend-private` upstream alias forces traffic across that private network. Tomcat trusts forwarded client addresses only from that gateway, and Caddy overwrites forwarded request headers by default. If the private subnet overlaps your host networks, change its subnet, gateway address **and** the trusted proxy expression together. Do not trust forwarded headers from arbitrary clients. Access audit IPs are personal data and need the union's approved retention/access policy.

## Encrypted nightly backups

Generate an age identity offline. Put only its public recipient in AGE_RECIPIENT. Keep the private identity separate from the server, bucket and archives; test recovering it. Configure a dedicated S3-compatible bucket/prefix and an access key limited to listing, writing and deleting that prefix. Set AWS_ENDPOINT_URL only for your storage provider. Enable bucket encryption, restrict access and arrange independent monitoring of backup failures.

`backup-host.sh` holds a host lock, stops the backend for a consistent database/upload backup, then restarts it even if backup fails. The short outage also pauses webhook processing; provider retries remain durable/idempotent. Do not make direct database or receipt changes during the backup. Dumps and restore plaintext live only in the maintenance container's tmpfs. Persistent archives are age encrypted; application encryption keys are provisioned separately, not included in archives.

```
bash deploy/backup-host.sh
```

Schedule nightly on the host, for example `0 2 * * * cd /srv/cums && bash deploy/backup-host.sh >> /var/log/cums-backup.log 2>&1`. Alert on any nonzero exit and stale/missing off-site archives. Never enable shell tracing with secrets loaded. The scripts retain 30 daily archives and the latest archive for each of 12 months. Deletion is restricted to this application's exact generated key patterns; unrelated bucket objects are untouched. Local encrypted copies are also bounded to 30. A failed upload fails the job; local-only mode is for synthetic drills, not production acceptance.

The default 2 GB work tmpfs bounds backup/restore size. Increase it and host memory deliberately before larger data sets. Confirm available disk space for encrypted archives. Off-site credentials and retention have unit checks but require owner acceptance against the real bucket.

## Restore drill

The reproducible local test uses disposable databases, volumes and synthetic data only:

```
docker build -t cums-backup:local -f deploy/Dockerfile.backup deploy
pwsh -File scripts/backup-restore-drill.ps1
```

Verified 2026-10-03: encrypted dump and receipt recovery, exact restored ledger total 1200.0000, wrong-key rejection and populated-target refusal. The test cleans only its own named disposable resources. It is a local recovery test; off-site download/TLS/real-key recovery still needs the owner drill.

For an actual drill, create a **separate Compose project** with an empty database and uploads volume. Download the chosen encrypted archive into its backup volume, mount the offline age identity read-only at `/keys/identity`, and run `/opt/cums/restore.sh /backups/<archive>.tar.age /keys/identity` using the maintenance image with BACKUP_QUIESCED=true and RESTORE_EMPTY_TARGET=true. The target must have zero public tables and zero uploaded files. Restore does not drop or overwrite existing databases. Archive paths, links and device files are rejected before extraction; pg_restore uses one transaction and exits on errors.

Provision the original application encryption keys, check database totals/counts and receipt hashes, start the isolated backend, verify health, sign-in and financial reconciliation, then test encrypted member-field reads with authorized staff. Do not connect the restored drill instance to live payment/email providers. Record backup age, restore duration, checks and outcome. Only switch traffic after the owner accepts the recovery; preserve the old deployment until verification finishes.

## Monitoring and updates

Monitor HTTPS `/actuator/health` from outside the host, disk space, PostgreSQL health, outbox/webhook failures, backup freshness and container restarts. Do not expose other Actuator endpoints. Review Dependabot changes, run CI and the restore drill before upgrades. No real domain, external bucket, live-provider credentials or production hosting was configured by local development work.

## Private S3-compatible receipt storage

Set `APP_RECEIPT_STORAGE=s3`, a provisioned `APP_RECEIPT_S3_BUCKET`, a dedicated `APP_RECEIPT_S3_PREFIX` (default `receipts`), region, and optional HTTPS endpoint/path-style setting. The backend uses the AWS SDK credential chain. Keep all credentials server-side; instance/task roles are preferred where available. Production Compose requests AES256 server-side encryption. A provider without this capability requires an explicitly reviewed alternative; do not disable encryption in production. HTTP and `NONE` encryption are reserved for synthetic isolated tests.

Objects use UUID keys only. The application never creates public ACLs, public links or browser presigned URLs. All downloads continue through session, current permission/ownership and audit checks, then verify recorded SHA-256 and byte size. Conditional creates reject collisions; upload failures and downloads return generic messages. Provision a private bucket with public access blocked, TLS and a least-privilege application identity limited to GetObject/PutObject/DeleteObject under its dedicated prefix. Do not give clients direct bucket credentials or grant other writers to that prefix.

The maintenance identity additionally needs ListBucket restricted to the receipt prefix and receipt GetObject for backups, PutObject for isolated restores. Receipt endpoint/region variables are independent of the off-site encrypted backup endpoint. Configure compatible credentials for both services or supply an operator-specific maintenance credential chain. Do not accidentally reuse a public asset bucket.

With the backend quiesced, S3 backup enumerates attachment metadata, fetches every referenced receipt/revision into tmpfs, verifies its size/digest and includes the same anonymous UUID files in the encrypted archive. Failure aborts the backup. Increase bounded work-memory deliberately when necessary. Restore rejects an occupied target prefix and compares the complete decrypted inventory with the restored database before conditional uploads. An object-store failure may leave an isolated restored database and some uploaded objects: keep the application stopped, inspect locally, and use a fresh isolated target for another attempt. No production database/prefix is overwritten automatically.

To migrate existing local receipts, take a verified encrypted backup and restore it into an **empty isolated database and private S3 prefix** with the target storage variables enabled. Compare counts/hashes and exercise authorized receipt/revision downloads before switching the deployment. Merely changing the storage setting on an existing local installation does not copy its files. Reverse migration likewise requires a tested restore into an empty local target. Retain the previous deployment until owner acceptance.

The synthetic storage fixture uses pinned Moto server solely for isolated acceptance; it is not a production storage recommendation. Build it before container/storage recovery checks:

```
docker build -t cums-backup:local -f deploy/Dockerfile.backup deploy
docker build -t cums-storage-test:local -f deploy/Dockerfile.storage-test deploy
pwsh -File scripts/backup-restore-drill.ps1 -S3
```

SDK configuration follows the [AWS SDK S3 guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html) and [endpoint configuration](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html). Private real-bucket policy/TLS/credentials and an owner off-site recovery drill remain deployment acceptance requirements.
