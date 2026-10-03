# Privacy implementation notes — draft

The union must approve the final policy and retention schedule before production. These notes describe implementation, not legal compliance.

Private member fields and TOTP secrets are encrypted. Passwords use Argon2id; session, invitation and recovery tokens are hashed. Queued invitation links are encrypted. Staff access follows current permissions; member APIs derive membership from the authenticated account. Receipt/statement downloads enforce ownership and are audited.

Financial ledger, audit, payment events, revision history and reconciliation records are append-only. Receipt images are decoded/re-encoded without EXIF/GPS metadata. PDF document metadata is removed and interactive documents are rejected. HEIC conversion and external scanning remain pending. Private uploads are never public static files.

The payment provider receives transaction email, amount, reference and member UUID needed for verification. Status emails exclude receipt attachments and balances. Only public static assets enter the offline cache; APIs and financial documents are excluded.

Members can download an audited JSON export of their own profile, account metadata, exact financial records, allocation revisions, snapshot history and receipt metadata. The server derives the member from the signed-in account; other members' data and authentication secrets are excluded. Receipt files remain separately protected downloads. Snapshot corrections preserve previous values in immutable history.

Retention/anonymization, recovery administration, backup recovery and policy review remain outstanding. No automated financial-record deletion is enabled by this draft.
