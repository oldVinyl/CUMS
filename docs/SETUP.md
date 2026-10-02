# Setup

This document is updated as each phase becomes runnable. Current status is in [PROGRESS.md](PROGRESS.md). Do not use this code for live funds until all acceptance gates pass.

## 1. Install tools

1. Install Git: Windows: `winget install Git.Git`; macOS: `brew install git`; Linux: use your distribution's package manager, for example `sudo apt install git`. Run `git --version`; a version should print.
2. Install Temurin JDK 21: Windows: `winget install EclipseAdoptium.Temurin.21.JDK`; macOS: `brew install --cask temurin@21`; Linux: follow the [Adoptium packages](https://adoptium.net/installation/linux/) instructions for your distribution. Run `java -version`; it should report 21.
3. Install Node.js 20 LTS: use [nodejs.org](https://nodejs.org/) or a version manager. Run `node -v` and `npm -v`; Node should report v20.
4. Install Docker Desktop on Windows/macOS from [docker.com](https://www.docker.com/products/docker-desktop/), or Docker Engine with the Compose plugin on Linux. Start Docker. Run `docker --version` and `docker compose version`; both should print versions.
5. Install an IDE such as IntelliJ IDEA Community or VS Code. Open the repository root.

## 2. Local configuration

Copy `.env.example` to `.env` after it exists in Phase 0. Windows PowerShell: `Copy-Item .env.example .env`; macOS/Linux: `cp .env.example .env`. Keep `.env` out of Git. Generate keys with `openssl rand -base64 32`, once for each secret. The initial admin email and password will come only from environment variables.

## 3. Start the app

After Phase 0 is complete: run `docker compose up -d` and expect PostgreSQL and MailHog to become healthy. Run `./mvnw spring-boot:run` in `backend` (Windows: `.\mvnw.cmd spring-boot:run`) and `npm install; npm run dev` in `frontend`. Open the Vite URL printed by the command. Until authentication is complete, only the health endpoint is useful.

## 4. Repository and production services

Create a **private** repository in GitHub (`New repository` → `Private`), add the remote with `git remote add origin <private-repo-url>`, then `git push -u origin main`. In GitHub, review `Settings → Security → Code security and analysis` for secret scanning and `Settings → Security → Advanced Security` or Dependabot settings as available to the account. Production setup, Paystack test/live keys, SMTP, S3-compatible storage, backups, and first-day onboarding instructions will be completed alongside their implemented features.

