# Privacy implementation notes — draft

The union must approve the final policy and retention schedule before production. These notes describe implementation, not legal compliance.

Private member fields and TOTP secrets are encrypted. Passwords use Argon2id; session, invitation and recovery tokens are hashed. Queued invitation links are encrypted. Staff access follows current permissions; member APIs derive membership from the authenticated account. Receipt/statement downloads enforce ownership and are audited.

Financial ledger, audit, payment events, revision history and reconciliation records are append-only. Receipt images are decoded/re-encoded without EXIF/GPS metadata. PDF document metadata is removed and interactive documents are rejected. HEIC conversion and external scanning remain pending. Private uploads are never public static files.

The payment provider receives transaction email, amount, reference and member UUID needed for verification. Status emails exclude receipt attachments and balances. Only public static assets enter the offline cache; APIs and financial documents are excluded.

Retention/anonymization, complete own JSON export, recovery administration, backup recovery and policy review remain outstanding. No automated financial-record deletion is enabled by this draft.
