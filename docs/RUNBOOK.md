# Operational runbook — working draft

Complete CHECKLIST before handling production funds. Deployment and encrypted off-site backup automation remain outstanding.

## Startup

Start PostgreSQL with Docker Compose, supply environment settings from SETUP, build both applications and start the correct backend profile. Verify health endpoints and unauthenticated financial-route rejection. Production requires a fresh database, HTTPS, secure cookies, correct origin and real service credentials. Do not promote the demonstration database.

## Approvals and accounts

Use separate named maker/checker accounts. Role changes take effect after final approval. Production ADMIN alone cannot approve money. Initial leadership provisioning needs an owner-authorized bootstrap procedure. ADMIN/PRESIDENT grants use explicit guarded revocation instead of expiry.

## Payments and mail

Inspect references/state before repeating a payment. Signed webhook jobs persist and retry; mismatched confirmation never credits money. Closed-year confirmation requires investigation, without automatic reopening. Never directly edit immutable financial/audit records.

SMTP is disabled by default while notifications remain queued. Enable only after verifying delivery, sender and TLS. Delivery has bounded timeouts and retries; ten failed attempts become FAILED. Invitation payloads are encrypted: do not print decrypted links or credentials in diagnostics. An operator retry screen remains pending.

## Files and recovery

Back up PostgreSQL, private uploads and recoverable versioned encryption keys together, with keys kept separately from encrypted archives. A database-only restore cannot recover receipt files or encryption keys. Private uploads must not be mounted as public web content.

The outstanding release gate requires encrypted nightly dumps and uploaded-file archives, S3-compatible off-site retention of 30 daily/12 monthly backups, monitoring, and an isolated restore drill. These are not yet implemented or verified.

Preserve audit/approval/provider references during incidents. Financial corrections use approved compensation, not editing prior rows. Historical corrections, resets, recovery administration and retention procedures still need implementation. Local tests and fixtures do not establish production or external-service acceptance.
