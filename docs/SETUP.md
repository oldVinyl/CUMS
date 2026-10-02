# Setup

This document is updated as each phase becomes runnable. Current status is in [PROGRESS.md](PROGRESS.md). Do not use this code for live funds until all acceptance gates pass.

## 1. Install tools

1. Install Git: Windows: `winget install Git.Git`; macOS: `brew install git`; Linux: use your distribution's package manager, for example `sudo apt install git`. Run `git --version`; a version should print.
2. Install Temurin JDK 21: Windows: `winget install EclipseAdoptium.Temurin.21.JDK`; macOS: `brew install --cask temurin@21`; Linux: follow the [Adoptium packages](https://adoptium.net/installation/linux/) instructions for your distribution. Run `java -version`; it should report 21.
3. Install Node.js 20 LTS: use [nodejs.org](https://nodejs.org/) or a version manager. Run `node -v` and `npm -v`; Node should report v20.
4. Install Docker Desktop on Windows/macOS from [docker.com](https://www.docker.com/products/docker-desktop/), or Docker Engine with the Compose plugin on Linux. Start Docker. Run `docker --version` and `docker compose version`; both should print versions.
5. Install an IDE such as IntelliJ IDEA Community or VS Code. Open the repository root.

## 2. Local configuration

Copy `.env.example` to `.env`. Windows PowerShell: `Copy-Item .env.example .env`; macOS/Linux: `cp .env.example .env`. Keep `.env` out of Git. Generate keys with `openssl rand -base64 32`, once for each secret. Authentication is still under development; there is no seeded login yet.

## 3. Start the app

Run `docker compose up -d` and expect PostgreSQL and MailHog to become healthy. In `backend`, run `./mvnw spring-boot:run` (Windows: `.\mvnw.cmd spring-boot:run`); `http://localhost:8080/api/v1/health` should return `{ "status": "UP" }`. In `frontend`, run `npm ci` then `npm run dev`; open the Vite URL printed by the command, normally `http://localhost:5173`. The interface is currently an explicitly marked design preview; sample balances cannot be posted. Run `./mvnw test` in `backend` and `npm run lint; npm run test; npm run build` in `frontend` to check the current slice.

If Docker Hub reports `unexpected EOF` while pulling MailHog, rerun `docker compose pull mailhog` and then `docker compose up -d`; the PostgreSQL service can be started independently with `docker compose up -d postgres` while the pull is retried.

## 4. Repository and production services

Create a **private** repository in GitHub (`New repository` → `Private`), add the remote with `git remote add origin <private-repo-url>`, then `git push -u origin main`. In GitHub, review `Settings → Security → Code security and analysis` for secret scanning and `Settings → Security → Advanced Security` or Dependabot settings as available to the account. Production setup, Paystack test/live keys, SMTP, S3-compatible storage, backups, and first-day onboarding instructions will be completed alongside their implemented features.
