# Setup

### Development sample data

Set `DEV_SAMPLE_DATA=true` alongside `SPRING_PROFILES_ACTIVE=dev` to load twelve fictitious members (`DEMO-001`–`DEMO-012`) and ledger entries for opening balances, monthly contributions, and quarterly income. The local workspace enables this flag. Samples are labelled in the overview and transaction narration. Repeated startup does not duplicate entries, closed fiscal years are skipped, and this seeder cannot run outside the dev profile. Turning the flag off stops future seeding; existing sample records remain in that local database.

Run financial integration tests against a fresh disposable PostgreSQL database using `DB_URL`, with `DEV_SAMPLE_DATA=false` and no active dev profile. Set `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3` during the full suite so its multiple Spring contexts can coexist with the running application. This avoids mixing development samples into financial test fixtures.

`frontend/scripts/overview-browser-smoke.mjs` checks the rendered overview and Settings against mocked API responses in a separate headless Chromium instance on CDP port 9223. It captures desktop/mobile light/dark screenshots under the ignored `frontend/artifacts/ui-check` directory and rejects horizontal page overflow.

This document is updated as each phase becomes runnable. Current status is in [PROGRESS.md](PROGRESS.md). Do not use this code for live funds until all acceptance gates pass.

## 1. Install tools

1. Install Git: Windows: `winget install Git.Git`; macOS: `brew install git`; Linux: use your distribution's package manager, for example `sudo apt install git`. Run `git --version`; a version should print.
2. Install Temurin JDK 21: Windows: `winget install EclipseAdoptium.Temurin.21.JDK`; macOS: `brew install --cask temurin@21`; Linux: follow the [Adoptium packages](https://adoptium.net/installation/linux/) instructions for your distribution. Run `java -version`; it should report 21.
3. Install Node.js 20 LTS: use [nodejs.org](https://nodejs.org/) or a version manager. Run `node -v` and `npm -v`; Node should report v20.
4. Install Docker Desktop on Windows/macOS from [docker.com](https://www.docker.com/products/docker-desktop/), or Docker Engine with the Compose plugin on Linux. Start Docker. Run `docker --version` and `docker compose version`; both should print versions.
5. Install an IDE such as IntelliJ IDEA Community or VS Code. Open the repository root.

## 2. Local configuration

Copy `.env.example` to `.env`. Windows PowerShell: `Copy-Item .env.example .env`; macOS/Linux: `cp .env.example .env`. Keep `.env` out of Git. Generate separate 32-byte base64 keys for `APP_ENCRYPTION_KEY` and `AUTH_THROTTLE_KEY` with `openssl rand -base64 32`. Docker Compose reads `.env`; a locally run Spring Boot process needs the same values exported in its terminal. The default browser origin is `http://localhost:5173`. Cookies are secure by default; set `APP_COOKIE_SECURE=false` only for local HTTP development.

## 3. Start the app

For now, run `docker compose up -d postgres`. MailHog is deferred until email notifications are implemented; keep its Compose service for that milestone. In `backend`, run `./mvnw spring-boot:run` (Windows: `.\mvnw.cmd spring-boot:run`); `http://localhost:8080/api/v1/health` should return `{ "status": "UP" }`. In `frontend`, run `npm ci` then `npm run dev`; open the Vite URL printed by the command, normally `http://localhost:5173`. The interface is currently an explicitly marked design preview; sample balances cannot be posted. Run `./mvnw verify` in `backend` and `npm run lint; npm run test; npm run build` in `frontend` to check the current slice.

To exercise sign-in locally, start the backend with `SPRING_PROFILES_ACTIVE=dev`, both generated keys, `APP_COOKIE_SECURE=false`, and a unique `DEV_BOOTSTRAP_EMAIL` and `DEV_BOOTSTRAP_PASSWORD` (at least 12 characters). The dev profile creates one ADMIN account when the email does not exist. The first login requires a password change and TOTP enrollment. Keep `DEV_BOOTSTRAP_EMAIL` set during local development: that existing account receives every known permission only while the `dev` profile is active. It still cannot approve its own requests; create a second local account for maker-checker tests. This permission override is ignored outside `dev`. Use the frontend's **Sign in** link. Overview, Members, Investments, Ledger, Approvals and Reports now use live backend data. Payments and the member portal remain under development.

Google sign-in is gated until a Google Identity Services **web client ID** is configured. Set `APP_GOOGLE_CLIENT_ID` for the backend and `VITE_GOOGLE_CLIENT_ID` for the frontend to the same ID, and register the frontend origin (for example `http://localhost:5173`) as an authorised JavaScript origin in Google Cloud. This ID token flow does not need a client secret or redirect URI. A Google identity must be linked during first sign-in after password verification or from Account security while signed in; a matching email alone never grants access.

Passkey registration and sign-in use WebAuthn on a secure context. `localhost` works for local development. For a deployed site, use HTTPS, set `APP_ALLOWED_ORIGIN` to the exact frontend origin, and set `APP_PASSKEY_RP_ID` to the site's registrable domain. Changing the RP ID later prevents existing passkeys from authenticating. A passkey can be created during first sign-in after password verification or in Account security while signed in. Device-loss recovery and credential removal still need acceptance work.

The Windows passkey browser smoke test is `pwsh -NoProfile -File scripts/passkey-browser-smoke.ps1`. Keep the frontend and Docker PostgreSQL running, and stop the regular backend first because the test starts a backend on port 8080. It creates and drops a disposable database, uses Chrome's virtual authenticator, and checks registration, preferred method, sign-in, and replay rejection. Restart the regular backend afterward.

For this local workspace, the bootstrap email is `dev-admin@invalid.example`; read its password from the ignored root `.env` file. An HTTP 401 from `/api/v1/auth/login` means the entered credentials were not accepted. An HTTP 401 from `/api/v1/auth/me` before sign-in means there is no session yet. The sign-in page no longer makes that pre-login profile request. The first successful login proceeds through password change and authenticator setup; use the displayed QR code or manual key.

When email notifications are ready, start MailHog with `docker compose up -d mailhog` and verify its SMTP and web ports. A previous Docker Hub pull failed with `unexpected EOF`; no email delivery test has passed yet.

## 4. Repository and production services

Create a **private** repository in GitHub (`New repository` → `Private`), add the remote with `git remote add origin <private-repo-url>`, then `git push -u origin main`. In GitHub, review `Settings → Security → Code security and analysis` for secret scanning and `Settings → Security → Advanced Security` or Dependabot settings as available to the account. Production setup, Paystack test/live keys, SMTP, S3-compatible storage, backups, and first-day onboarding instructions will be completed alongside their implemented features.

## Financial screens and downloads

- **Ledger** lists immutable approved entries in a selected date range, with 50 rows per page. Authorized makers submit seed/addition contributions, current-day manual reversals and completed-year close requests. **Approvals** shows the financial impact, requires a review confirmation, and allows the maker to cancel. Investment requests are reviewed alongside their allocation in **Investments**.
- **Reports** and the overview offer Sheet B Excel/PDF downloads. Each investment offers its Sheet A download. Ledger exports honor the selected range, and directory exports include only names, numbers, join dates and current status. Member details and Reports offer ranged member statements with opening, running and closing balances. A portal account can request only its own statement; other member IDs are rejected.
- Downloads require live permissions, use no-store responses, and write an audit event. Reports are capped at 5,000 source rows; shorten a ledger/statement range when necessary. Excel values exceeding 15 significant digits are stored as exact text because Excel cannot preserve their cents numerically. PDF reports embed the redistributable Noto Sans font with its SIL OFL license in `backend/src/main/resources/fonts`.
- Investment reversal is a future-only correction effective today. It archives the previous revision, adds exact compensation for gains/losses, and returns a new working draft with today's purchase date and an explicitly selected future maturity date. It does not rewrite earlier statements. Historical period unlock and snapshot recomputation remain pending.
- `frontend/scripts/financial-browser-smoke.mjs` verifies Reports, Ledger contribution forms and approval confirmations at desktop/phone widths in light/dark mode using isolated fixtures on headless Chromium port 9223. It rejects mutations and page overflow. Screenshots are ignored under `frontend/artifacts/ui-check/financial`.
