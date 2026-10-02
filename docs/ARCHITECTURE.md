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

