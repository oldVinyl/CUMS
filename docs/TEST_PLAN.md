# Post-build verification plan

This follows main-build implementation. Local regression and focused new-workflow checks now pass; see TEST_RESULTS.md for exact evidence. The broad targets below include combinations not exhaustively exercised and must not be read as owner-only tasks. Use only synthetic accounts/data, temporary private uploads and an isolated database. Never point integration tests at the owner's development ledger or private migration target; never load the private workbooks into CI or browser fixtures.

## Environment

Use the existing disposable PostgreSQL/Testcontainers and financial-acceptance harnesses documented in SETUP.md. Allocate separate backend/frontend ports and database names for synthetic browser financial work. Confirm DB_URL points at the intended disposable target before integration commands. Keep application encryption/throttle keys local; CI receives synthetic setup, with NVD_API_KEY supplied separately as an owner secret. Existing restore scripts require an empty isolated target and must not overwrite a live database/upload prefix.

## Regression commands

- Backend: from backend, `./mvnw -Pcontainer-tests,financial-coverage verify` (Windows: `mvnw.cmd`), against the isolated integration database configuration.
- Frontend: from frontend, `npm run lint`, `npm run test`, `npm run build` and `npm run test:e2e`.
- Actual synthetic financial browser lifecycle: from root, `pwsh -File scripts/financial-acceptance.ps1`; it provisions its own database/accounts/uploads and ports.
- Storage/recovery: existing disposable `scripts/backup-restore-drill.ps1` and its `-S3` variant; the documented maintenance images must be built first.
- Dependency/secret and hosted CI: follow CI and OWNER_ACTIONS.md. External credential failure is recorded separately from application test failure.

## Additional new-workflow acceptance

- [ ] Historical receipt: exact original date, future-only boundary, source freshness, paid-amount validation, separate maker/checker and exact-once payment linkage.
- [ ] Historical investment import: CSV/XLSX bounds, one cycle per batch, supplied shares/TOTAL reconciliation, earliest-first equity, expected versus received proceeds, atomic posting and reversible batch evidence.
- [ ] Current and archived proceeds correction: profitable/loss/zero cases, unchanged source, selection of the applicable economic revision and refusal to invent missing compensation evidence.
- [ ] Moved/reversed economic timeline: original maturity income adjustments, exact offsets at the approved compensation date, later cycle recomputation, explicit exclusions/overrides, rollover caps and independently unlocked periods.
- [ ] Historical ledger reversal: principal and positive/negative income opening balances, exact opposite amount, source entry preserved, dependent impacts, negative equity protection and provider-payment exclusion.
- [ ] Retention: confirmed departure/version, due date policy, independent review, pending dependency/account checks, sensitive-field/factor/outbox/import cleanup, approved narrow redaction hashes, immutable financial fields and account reactivation denial.
- [ ] Receipt deletion: immediate download denial, retry/backlog behavior, local/S3 deletion, live-backup exclusion and older-backup handling documented for the owner.
- [ ] Owned exports: only the signed-in member's archived shares/adjustment links; no other members, tokens, authentication secrets or private proof binaries.
- [ ] Financial replay: same actor/key/body returns the original UUID/204 result; changed body denied, concurrent requests produce one effect, rollback does not create success evidence, current permission/CSRF/session checks, revoked-user denial and ambiguous browser retry.
- [ ] Organization policies: 12-character floor, configured minimum, allowed idle bounds, passive profile/refresh not extending inactivity, authorized/CSRF-protected saves and exact currency arithmetic.
- [ ] Public branding: member/contact/secret exclusion, PNG size/dimensions/type/metadata, configured scanner behavior, safe React text, long-name small-screen handling and primary button contrast in both themes.
- [ ] Security route sweep: every current controller path, unsigned/current-role/ownership checks, malformed inputs, generic errors, no-store, identifying searches outside URLs and synthetic sentinel log exclusion.
- [ ] Non-production API docs: current routes/request schemas, no source/runtime data, absent/denied in prod.
- [ ] Investment directory: more than 100 cycles reachable through server pagination, sort/status/search behavior, empty/error states and stale-search response handling.
- [ ] Responsive UI: new preferences, backup information, original/archive correction and retention overlays at 320/375/768/1024/1440, both themes, outside dismissal, keyboard focus, reduced motion, multiline/promise toasts and axe checks.
- [ ] Production safety: missing scanner/keys, dev flags and insecure cookies/origin rejected; bundled scanner health, clean/malware/outage checks use an isolated acceptance host.

Fix defects found by these checks, rerun only affected checks plus the required final gate, and then update PROGRESS/CHECKLIST with actual results. Owner services/private source/deployment approval remain OWNER_ACTIONS.md. No production completion/tag is inferred from main-build feature completion.
