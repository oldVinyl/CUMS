# Credit Union Management System

Ghana cedi credit union management system. The authoritative build requirements are in the user-provided master brief. Implementation status is tracked in [docs/PROGRESS.md](docs/PROGRESS.md); do not use an unchecked workflow for real funds.

## Local development

See [docs/SETUP.md](docs/SETUP.md). The repository uses Java 21/Spring Boot, PostgreSQL 16, and React 18/TypeScript/Vite.

The signed-in workspace connects to live member, ledger, investment, dashboard, report, payment, reconciliation and account APIs. The signed-out home page is a simple landing page with a clear login and Vinette/Vinyl credit. Development can opt into fictitious balances and a payment simulator.

See the [user guide](docs/USER_GUIDE.md), [runbook](docs/RUNBOOK.md) and [release checklist](docs/CHECKLIST.md). Imports, historical corrections, deeper recovery administration and production gates remain open; this is not yet production complete.

[Production deployment and encrypted recovery](docs/DEPLOYMENT.md) includes Compose/Caddy, guarded leadership bootstrap, database/receipt backups and a reproducible synthetic restore drill. Actual hosting, credentials and off-site recovery acceptance are owner setup steps.

Settings credits **Vinette by Vinyl** and links to [Vinyl’s GitHub profile](https://github.com/oldVinyl).
