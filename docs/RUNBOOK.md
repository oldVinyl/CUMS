# Operational runbook — working draft

Complete CHECKLIST before handling production funds. Production Compose, HTTPS routing and encrypted database/upload recovery tooling are described in DEPLOYMENT. Live hosting, off-site credentials and owner restore acceptance remain release gates.

## Startup

Start PostgreSQL with Docker Compose, supply environment settings from SETUP, build both applications and start the correct backend profile. Verify health endpoints and unauthenticated financial-route rejection. Production requires a fresh database, HTTPS, secure cookies, correct origin and real service credentials. Do not promote the demonstration database.

## Approvals and accounts

Use separate named maker/checker accounts. Role changes take effect after final approval. Production ADMIN alone cannot approve money. The explicit `prod,bootstrap` procedure provisions separate ADMIN/PRESIDENT accounts atomically on a fresh account table, forces password replacement and refuses repeat runs. Follow DEPLOYMENT and remove bootstrap secrets/profile afterwards. ADMIN/PRESIDENT grants use explicit guarded revocation instead of expiry.

## Payments and mail

Inspect references/state before repeating a payment. Signed webhook jobs persist and retry; mismatched confirmation never credits money. Closed-year confirmation requires investigation, without automatic reopening. Never directly edit immutable financial/audit records.

SMTP is disabled by default while notifications remain queued. Enable only after verifying delivery, sender and TLS. Delivery has bounded timeouts and retries; ten failed attempts become FAILED. Invitation payloads are encrypted: do not print decrypted links or credentials in diagnostics. An operator retry screen remains pending.

## Files and recovery

Back up PostgreSQL, private uploads and recoverable versioned encryption keys together, with keys kept separately from encrypted archives. A database-only restore cannot recover receipt files or encryption keys. Private uploads must not be mounted as public web content.

`deploy/backup-host.sh` stops the backend under a host lock and restarts it on success/failure. `backup.sh` encrypts a PostgreSQL dump and private uploads with age, uploads to S3-compatible storage and retains 30 daily/12 monthly generated archives. Plaintext staging is confined to tmpfs; private keys are stored separately. `restore.sh` refuses populated database/upload targets, validates archive paths/types and restores the database in one transaction. The synthetic local drill passed exact ledger/receipt recovery, wrong-key rejection and populated-target refusal. Off-site delivery, actual-key recovery, monitoring and owner acceptance remain release gates; see DEPLOYMENT.

Preserve audit/approval/provider references during incidents. Audit events now capture request IP and trace metadata at database insert; background operations have no fabricated client address. Production trusts forwarded client addresses only from the configured gateway. Financial corrections use approved compensation, not editing prior rows. Historical corrections, resets, recovery administration and retention procedures still need implementation. Local tests and fixtures do not establish production or external-service acceptance.


## Owner handoff

See [OWNER_ACTIONS.md](OWNER_ACTIONS.md) for the central credential, union-policy, private migration, email, scanner, hosting, off-site recovery and release steps. Main-build implementation is separate from the next isolated test pass and owner production acceptance.
