# Local verification results — 2026-10-05

Only synthetic fixtures and disposable databases/uploads were used. The private workbooks, member identities, staged migration and owner ledger were not used by these suites. This records local acceptance; production/provider/client acceptance remains in OWNER_ACTIONS.md.

| Check | Result |
| --- | --- |
| Full backend regression against fresh PostgreSQL database | 232 tests passed; allocation property exercised 250 generated cases |
| Added historical receipt workflow checks | Focused 8-test payment suite passed after adding two cases |
| Final Maven container/financial coverage gate | Passed; PostgreSQL 16 migration/immutability and S3 object-store tests both passed |
| Financial package line coverage | Ledger 92.62%; allocation 92%; dashboard 95.95%; approval 91.18%; payment 89.09% (85% gate) |
| Frontend Vitest | 81 tests across 26 files passed |
| Responsive/axe browser checks | 190 scenarios passed initially; ten investment fixture failures corrected and all ten reruns passed; ten additional organization/retention scenarios passed. 210 passing scenarios across 320/375/768/1024/1440, light/dark |
| Real HTTP/browser financial lifecycle | Passed: synthetic enrollment, members, historical seed approvals, maturity/loss, reverse/repost, rollover, owned receipts/replacement/export, settlement, audit and permission revocation |
| Encrypted local and S3 restore drills | Passed: exact ledger totals, proof integrity, anonymized/unreferenced local proof exclusion, populated-target and wrong-key refusal |
| Frontend lint and production build | Passed; two development Fast Refresh warnings and bundle-size advisory remain |
| Development backend after packaging | Health UP, V43 |

## Corrections made during verification

- Update investment component fixtures with router context and the server search-page shape.
- Include public branding and the historical correction inbox in isolated browser fixtures.
- Update the production-safe fixture to require a scanner, with explicit missing-scanner rejection checks.
- Update fresh-container expectation from V39 to V43; compare archived numeric proceeds by value rather than decimal formatting.
- Give local restore fixtures the live attachment inventory now required by privacy-aware backups; assert anonymized and orphan proof exclusion.
- Add independent original-date receipt posting checks to close the payment coverage gap. No financial coverage threshold was lowered.

## Newly exercised financial/security paths

Current proceeds corrections cover profit, zero income and loss; original ledger entries remain. Archived corrections apply at the original maturity and offset at the recorded reversal date, leaving current equity unchanged. Original-date principal reversal preserves its source and recomputes matured shares. Investment imports distinguish expected and received income, reject missing dates/mismatched shares, require independent posting and retain reversal evidence.

Retention checks exercise separate maker/checker review, immutable financial totals with approved identity redaction, changed policy and active-account refusal. Replay checks require matching input, current permissions, session and CSRF and return the original opaque acknowledgement. Browser retry checks retain keys after ambiguous network/server failures. Idle checks show passive profile polling and refresh do not extend inactivity. Organization checks cover guarded administration routes, public field exclusion, policy floor, contrast and malformed logo refusal.

## Scope still requiring acceptance

OWNER_ACTIONS.md covers real source reconciliation, credentialed Google/Paystack/mail, production scanner/hosting, off-site backups and hosted security/release acceptance. Local fake provider/S3 fixtures cannot certify those services. TEST_PLAN.md retains broader edge-case verification targets not exhaustively exercised here, including replay concurrency/rollback, every archived timeline combination, S3 retention deletion retry, exhaustive route/log sweep and investment pagination beyond 100. Those are additional agent/operator verification work, not missing owner credentials and not marked passed.
