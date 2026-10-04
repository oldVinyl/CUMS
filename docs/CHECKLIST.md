# Release checklist

Check only implemented and verified gates. This system is not yet production complete.

- [x] Member CRUD, generated IDs, encryption and permissions
- [x] Immutable ledger, current-day contributions/reversals and independent approval
- [x] Investment lifecycle and current-day compensating revision history
- [x] Dashboard and Excel/PDF financial reports with supplied fixtures
- [x] Private receipt submission/review and approved exact-once payment posting
- [x] Signed webhook jobs, strict provider verification and dev-only simulator
- [x] Member portal, invitation activation/replacement and own-data ownership tests
- [x] Reviewed role-management UI and current permissions
- [x] Current-day reconciliation snapshots and source freshness checks
- [x] Persistent email outbox and opt-in SMTP implementation
- [x] Responsive fixture checks at 320/375/768/1024/1440 in both themes
- [x] 40 Playwright/axe scenarios for public/sign-in/overview/settings/member creation in five sizes and both themes
- [x] Expand to 140 Playwright/axe scenarios including ten operational screens and provide synthetic role-guide screenshots
- [x] Real synthetic financial browser workflows: historical year/contributions, maturity/rollover, receipt/provider posting and role revocation
- [ ] Final full accessibility/release acceptance
- [ ] Real Paystack sandbox, direct MoMo prompts and settlement matching
- [ ] SMTP/MailHog delivery and operational retry acceptance
- [x] Historical contribution impact preview, future-only/repost, period unlock and snapshot recomputation
- [x] Approved reasoned period unlock, immutable snapshot revisions, later-year recomputation and reviewed historical import integration
- [x] Overview fiscal-year selector and component/missing-member snapshot reconciliation
- [x] CSV/XLSX member/opening-balance/contribution dry runs, approval and batch compensation
- [ ] Historical investment/allocation imports and explicit dependent-cycle correction/repost
- [x] Filterable audit metadata screen and audited CSV export with authorization and injection tests
- [x] Own-account security controls, protected verification, passkey revocation, recovery-code replacement and password changes
- [x] Fully lost-factor recovery and independent account-reset administration
- [x] Transactional financial before/after audit, private-field exclusion and authenticated checker attribution
- [ ] Full personal-data log-scrubbing acceptance
- [ ] Complete original Sheet B workbook acceptance
- [x] Local HEIC-to-JPEG conversion, bounded input/dimensions/time and production CSP browser acceptance
- [ ] Real malware scanner and final file security acceptance
- [ ] Production CSP/TLS/security review and deployment
- [x] Production Compose/Caddy configuration, startup guards, separate leadership bootstrap and synthetic encrypted recovery tooling
- [x] Local age-encrypted database/receipt restore drill with exact totals, wrong-key and populated-target rejection
- [ ] Encrypted off-site database/files backups, retention and restore drill
- [x] Audited owned JSON export, decimal preservation, private response headers and IDOR/secret-exclusion tests
- [ ] Retention/anonymization and owner policy review

No production deployment or successful external service delivery is claimed.
