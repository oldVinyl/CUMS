# Architecture

## Modules and data flow

`frontend` is a React client. It calls versioned `/api/v1` endpoints in `backend`. Controllers accept validated DTOs, services enforce permission and domain rules, repositories persist to PostgreSQL 16. Flyway alone owns schema changes; Hibernate validates. Date interpretation uses Africa/Accra and ISO dates. JSON money values are strings; Java uses `BigDecimal` and the allocation engine uses integer pesewas.

Contributions and allocated income become append-only ledger entries after independent approval, except gateway-verified payments. Balances and as-at reports are computed from the ledger. Investment participation is separate from ledger components. Approved investment maturity writes income allocations to the ledger. The role assignment tables are the current source of permission truth.

## Boundaries

Packages follow `config`, `security`, `common`, `member`, `user`, `role`, `ledger`, `investment`, `allocation`, `payment`, `dashboard`, `report`, `importer`, `reconciliation`, `notification`, and `storage`. The storage and payment provider interfaces permit local/fake implementations in development and external providers in production. Never expose JPA entities directly from controllers.

## UI direction

The interface uses a navy frame (#083a4f), teal controls (#407e8c), aqua surfaces (#c0d5d6), sand background (#e5e1dd), restrained gold accents (#a58d66), and spiced paprika (#c31b1b) for destructive or negative states. Rounded shapes are reserved for cards and controls, with crisp typography and plain, specific labels. Mobile navigation, compact summaries, and expandable detail support progressive disclosure. Tables remain available on wider screens.

## Decisions

- Financial actions remain server-authoritative and require idempotency keys.
- Distinguish economic `effective_date` from immutable `recorded_at`.
- Treat the UI as a view of the backend, never as the source of balances or permissions.
- All data in seeds and tests is obviously fake.
- `V1__core_financial_schema.sql` establishes members, users, role assignments, fiscal years, investments, payments, ledger entries, and audit records. The `member_balances` view derives balances from the ledger; database triggers reject ledger and audit updates or deletes. Later versioned migrations will add workflow-specific tables as those features are implemented.
- `V2__encrypt_member_private_fields.sql` stores private member fields as ciphertext and keeps HMAC blind indexes for exact-match lookup. The AES-256-GCM field format starts with a version byte and uses a fresh nonce for each value. The application creates this service when `APP_ENCRYPTION_KEY` is configured; member data write paths must require it.
- Approval requests store an immutable snapshot of the required approval count. `ApprovalService` locks a pending request, checks the approver's current permission, records each distinct decision, and invokes its registered action handler within the same transaction as the final approval. Unsupported action types cannot be submitted. Financial handlers and authenticated endpoints are added with their features.
- `ContributionApprovalHandler` accepts only current-day SEED and ADDITION amounts expressed as decimal strings. It checks the maker's live permission, member status, and open fiscal year at submission and again when approval completes. The final approval inserts one principal ledger row and one audit row in the same transaction; balances remain derived from the ledger.
- `ContributionReversalHandler` accepts only a current-day manual contribution and a mandatory reason. Approval inserts its exact negative amount with `reversal_of` set to the original entry. Historical corrections will use the separate backdating and period workflow so past investment ratios are not silently changed.
- `FiscalYearCloseHandler` records cumulative principal, income, and equity snapshots for members who joined by year end, then closes the year and audits it in the approval transaction. All current financial posting handlers and year-end close lock the fiscal-year row before they write, serializing those operations. Period unlock and snapshot recomputation remain separate work.
- `RoleChangeApprovalHandler` grants or revokes assignments only through maker-checker approval. It locks the role row before applying a change, denies recipient self-approval, and blocks revoking the last currently active ADMIN or PRESIDENT. Future account-disable and assignment-expiry paths must preserve that invariant too.
- Authentication uses Argon2id password hashes. The session service issues random 256-bit access, refresh, and CSRF tokens; PostgreSQL stores only SHA-256 token hashes. Access lasts 15 minutes, refresh lasts 7 days, and idle sessions expire after 30 minutes. Refresh rotation invalidates the previous tokens, and replay of a consumed refresh token revokes the session. The HTTP cookie and authentication flows are still pending.
- `TotpCodes` implements RFC 6238 HMAC-SHA1 with 30-second steps and accepts at most one adjacent step for clock drift. It is checked against the RFC test vectors. Enrollment, encrypted secret storage, replay prevention, and login challenges remain pending.
- `LoginThrottleService` stores HMAC-SHA256 digests of normalized account identifiers and remote IPs, with account backoff after five failed password attempts and an IP limit. `PasswordLoginService` uses a dummy Argon2id hash for unknown accounts and returns a generic failure; it yields only the next required step after a correct password. Browser-facing challenges and completion of login remain pending.

## Reviewed periods, financial change records and receipt evidence

Historical fiscal-year creation is a PERIOD_CREATE approval using current leadership permission and a separate checker. It only opens the missing calendar period; importing balances remains a separate approval.

V37 database triggers capture explicitly allowed scalar financial fields in append-only `financial_audit_changes`, linked to event metadata and a database transaction number. Exact numeric text survives API/CSV serialization. Session resolution supplies actor context server-side; connection checkout clears actor/IP/trace for background work. Original arbitrary audit payloads are not exposed by the change view, and existing events are not backfilled.

V38 `payment_receipt_revisions` retains prior and replacement proof IDs and the author/time. ReceiptRevisionService locks the same payment row used by verification, enforces current ownership and upload ownership, rejects stale forms and replayed archived evidence, and allows replacement only before verification. Original files remain private and downloadable through the existing permission gate. Revision writes never change financial amounts or add ledger entries. Owned export includes revision metadata. Members and authorized staff can view history after posting; checkers gain no replacement permission.

HEIC preparation runs in a bounded browser worker with a self-hosted CSP decoder. Only the normalized anonymous JPEG reaches the existing receipt validation/scan/storage API. Storage currently uses the private local disk volume; a configurable S3 receipt adapter remains a master-brief implementation gate.
