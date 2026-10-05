# Privacy implementation notes — draft

The union must approve the final policy and retention schedule before production. These notes describe implementation, not legal compliance.

Private member fields and TOTP secrets are encrypted. Passwords use Argon2id; session, invitation and recovery tokens are hashed. Queued invitation links are encrypted. Staff access follows current permissions; member APIs derive membership from the authenticated account. Receipt/statement downloads enforce ownership and are audited.

Financial ledger, audit, payment events, revision history and reconciliation records are append-only. Receipt images are decoded/re-encoded without EXIF/GPS metadata. PDF document metadata is removed and interactive documents are rejected. HEIC receipts convert locally to JPEG before upload; raw HEIC is not stored on the server. External scanner acceptance remains pending. Private uploads are never public static files.

The payment provider receives transaction email, amount, reference and member UUID needed for verification. Status emails exclude receipt attachments and balances. Only public static assets enter the offline cache; APIs and financial documents are excluded.

Members can download an audited JSON export of their own profile, account metadata, exact financial records, allocation revisions, snapshot history and receipt metadata. The server derives the member from the signed-in account; other members' data and authentication secrets are excluded. Receipt files remain separately protected downloads. Snapshot corrections preserve previous values in immutable history.

HTTP audit events include request IP/trace metadata for authorized investigations; background events have no client IP. Audit access and exports require AUDIT_READ. The union must approve IP retention and disclose this processing in its final policy. Raw receipt bytes and normalized files can be checked by a configured private ClamAV daemon; no file is submitted to a public scanning service. If configured scanning fails, upload is rejected. Production-bundle HEIC conversion passed mobile/desktop CSP acceptance; real scanner acceptance remains outstanding.

Database and private-upload backups are age encrypted, with plaintext preparation/recovery confined to tmpfs. Application encryption keys and the age private identity must be stored separately and recoverably. The synthetic local restore drill passed; real off-site storage/recovery still requires owner acceptance. Password and independently authorized fully lost-factor recovery use hashed, single-use 30-minute links in an encrypted outbox; the URL fragment is cleared without browser persistence. Live recovery email delivery and final owner policy review remain outstanding. The reviewed local retention workflow is implemented; its new destructive path has not been regression-tested at the owner’s request. No automated financial-record deletion is enabled by this draft.


## Reviewed membership retention

Organization policies default to seven years after a confirmed leaving date and can be configured from one to thirty years. Deactivation now records the leaving date; older inactive memberships require an explicitly confirmed date. A separate eligible administrator must approve permanent anonymization. Active accounts, open investments, unfinished payments and unresolved financial/admin approvals block it.

The workflow removes member identity/contact ciphertext and blind indexes, linked sign-in credentials/tokens and their notification copies, and identifying values in associated encrypted imports. Identifying free text is redacted without changing amounts, economic dates, participation ratios or UUID links. Digest-bound authorization records accompany the limited private-field exceptions to append-only protection. Anonymized memberships and their linked accounts cannot be reactivated.

Receipt downloads are denied immediately; durable deletion jobs retry private local/S3 object removal. Current backup inventories exclude tombstoned receipts even before physical deletion succeeds. Retention settings show a deletion backlog rather than claiming every file has already been deleted.

Original Excel workbooks, exported statements, third-party email copies, previously made backups, PostgreSQL WAL and storage-provider object versions are separate copies. The owner must apply the approved retention/expiry process to them. Restoring an earlier backup requires reapplying later anonymization decisions before opening access; keep the decision register separately with the protected recovery records. Application cleanup alone does not make these external copies disappear.
