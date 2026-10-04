# Privacy implementation notes — draft

The union must approve the final policy and retention schedule before production. These notes describe implementation, not legal compliance.

Private member fields and TOTP secrets are encrypted. Passwords use Argon2id; session, invitation and recovery tokens are hashed. Queued invitation links are encrypted. Staff access follows current permissions; member APIs derive membership from the authenticated account. Receipt/statement downloads enforce ownership and are audited.

Financial ledger, audit, payment events, revision history and reconciliation records are append-only. Receipt images are decoded/re-encoded without EXIF/GPS metadata. PDF document metadata is removed and interactive documents are rejected. HEIC conversion and external scanning remain pending. Private uploads are never public static files.

The payment provider receives transaction email, amount, reference and member UUID needed for verification. Status emails exclude receipt attachments and balances. Only public static assets enter the offline cache; APIs and financial documents are excluded.

Members can download an audited JSON export of their own profile, account metadata, exact financial records, allocation revisions, snapshot history and receipt metadata. The server derives the member from the signed-in account; other members' data and authentication secrets are excluded. Receipt files remain separately protected downloads. Snapshot corrections preserve previous values in immutable history.

HTTP audit events include request IP/trace metadata for authorized investigations; background events have no client IP. Audit access and exports require AUDIT_READ. The union must approve IP retention and disclose this processing in its final policy. Raw receipt bytes and normalized files can be checked by a configured private ClamAV daemon; no file is submitted to a public scanning service. If configured scanning fails, upload is rejected. HEIC conversion and real scanner acceptance remain outstanding.

Database and private-upload backups are age encrypted, with plaintext preparation/recovery confined to tmpfs. Application encryption keys and the age private identity must be stored separately and recoverably. The synthetic local restore drill passed; real off-site storage/recovery still requires owner acceptance. Password and independently authorized fully lost-factor recovery use hashed, single-use 30-minute links in an encrypted outbox; the URL fragment is cleared without browser persistence. Live recovery email delivery, retention/anonymization and policy review remain outstanding. No automated financial-record deletion is enabled by this draft.
