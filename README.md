# ApplyFlow

ApplyFlow is a multi-user job application tracker built as a production-minded portfolio project. It combines a React single-page application with a Spring Boot REST API for account security, private application tracking, workflow history, and owner-scoped analytics.

## What is shipped

- Register with email and password, verify an email address, sign in, sign out, recover access, and change or add a password.
- Sign in with Google OpenID Connect and link compatible credentials to one account.
- Create, browse, filter, sort, edit, advance, and delete private job applications.
- Track applications across bookmarked, applied, response, interview, offer, and rejection stages with status history.
- Extract bounded suggestions from a public job-posting URL before saving an application; manual entry remains available.
- Reuse or create companies during application entry, manage custom technologies, and use seeded job-source data.
- Query owner-scoped summaries, funnels, trends, source and technology comparisons, and response-time metrics through the backend API.

> Analytics are currently an authenticated backend API capability. The shipped frontend does **not** include an analytics dashboard.

## Architecture and stack

```text
React SPA -> Spring Security session -> REST controllers -> services -> repositories -> PostgreSQL
```

| Area | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring Security, OAuth2/OIDC, Spring Session JDBC, Spring Data JPA, Flyway, Maven |
| Frontend | React 19, TypeScript, Vite, React Router, TanStack Query, React Hook Form, Zod, Tailwind CSS |
| Data and local infrastructure | PostgreSQL 17, Docker Compose, Mailpit |
| Testing | JUnit 5, Spring Boot integration tests, Testcontainers PostgreSQL, Vitest, Testing Library |

The backend follows a direct layered design. Controllers obtain the authenticated user identity, services receive that owner context and enforce business rules, repositories handle persistence, and Flyway owns schema changes. The frontend uses protected routes, feature-oriented modules, lazy-loaded pages, and a shared API client.

## Repository layout

```text
backend/       Spring Boot API, migrations, and tests
frontend/      React application and tests
compose.yaml   Local PostgreSQL and Mailpit services
.env.example   Documented local configuration template
```

## Local setup

### Prerequisites

- Java 21
- Node.js 22 and npm
- Docker Desktop or Docker Engine with Compose

The Maven Wrapper is included; a global Maven installation is unnecessary.

### 1. Create local configuration

Copy the template to the ignored root `.env` file:

```bash
cp .env.example .env
```

PowerShell equivalent:

```powershell
Copy-Item .env.example .env
```

The template contains development defaults. For Google sign-in, replace `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` with a Web application client and register this callback:

```text
http://localhost:8080/login/oauth2/code/google
```

Never place secrets in `VITE_*` variables; those values are exposed to the browser.

### 2. Start local services

```bash
docker compose up -d postgres mailpit
docker compose ps
```

PostgreSQL listens on `localhost:5432`. Mailpit accepts development email on port `1025` and exposes its inbox at [http://localhost:8025](http://localhost:8025).

### 3. Run the backend

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

On Windows, use `mvnw.cmd`. The API defaults to [http://localhost:8080](http://localhost:8080). The `local` profile imports the root `.env`; Flyway applies migrations and Hibernate validates the schema.

### 4. Run the frontend

In another terminal:

```bash
cd frontend
npm ci
npm run dev
```

The application defaults to [http://localhost:5173](http://localhost:5173). Vite reads the root `.env` and uses `VITE_BACKEND_BASE_URL`; its `/api` development proxy targets port `8080` when needed.

## Verification commands

Backend integration tests require Docker because they use Testcontainers:

```bash
cd backend
./mvnw clean test
```

Frontend checks:

```bash
cd frontend
npm ci
npm test -- --run
npm run lint
npm run build
```

## Frontend routes

| Route | Purpose |
|---|---|
| `/sign-in`, `/sign-up` | Password and Google account entry |
| `/verify-email` | Email verification |
| `/forgot-password`, `/reset-password` | Password recovery or setup |
| `/auth/callback` | Complete the frontend transition after Google sign-in |
| `/applications` | Paginated, filtered, sortable application list |
| `/applications/new` | Create an application or bookmark |
| `/applications/:id` | Application detail and status history |
| `/applications/:id/edit` | Edit application details |
| `/tracker` | Application-stage tracker |
| `/settings/security` | Add or change a password |

Application and settings routes require an authenticated session.

## API surface

| Area | Endpoints |
|---|---|
| Session and identity | `/api/auth/csrf`, `/api/auth/register`, `/api/auth/login`, `/api/auth/logout`, `/api/auth/me` |
| Verification and recovery | `/api/auth/email-verification/*`, `/api/auth/password/*` |
| Google sign-in | `/oauth2/authorization/google`, `/login/oauth2/code/google` |
| Applications | `/api/applications`, `/api/applications/{id}`, `/api/applications/{id}/status` |
| Catalogs | `/api/companies`, `/api/technologies`, `/api/sources` |
| Job-page suggestions | `POST /api/job-offers/extract` |
| Analytics API | `/api/analytics/summary`, `/api/analytics/funnel`, `/api/analytics/applications-over-time`, `/api/analytics/sources`, `/api/analytics/technologies`, `/api/analytics/response-time` |

Application listing supports pagination, status and catalog filters, and controlled sorting. API errors use `application/problem+json`. Browser clients authenticate with the server-side session cookie and send the CSRF header returned by `GET /api/auth/csrf` for unsafe requests.

## Security and owner isolation

- Spring Security protects every route except the explicit registration, verification, recovery, login, and OAuth entry points.
- Passwords use Spring Security's delegating encoder; verification and reset tokens are one-time, expiring values stored as hashes.
- Account emails use a durable encrypted outbox with retry and claim leasing.
- Application, company, custom-technology, and analytics operations derive the owner from the authenticated principal rather than client-supplied ownership fields.
- Credentialed CORS uses configured exact origins, session cookies are HTTP-only, and the production profile requires secure transport with HSTS.
- Authentication and job-offer extraction have application-level rate limits. URL extraction also has configured redirect, timeout, response-size, and concurrency limits.

### Trusted proxy contract

Spring forwarded-header rewriting is disabled, so the servlet peer address always identifies the immediate connection. By default, `TRUSTED_PROXY_HEADER_MODE=none` trusts no forwarding header. A deployment behind a proxy must choose exactly one mode (`forwarded` or `x-forwarded-for`) and set `TRUSTED_PROXY_CIDRS` to the explicit CIDRs of every trusted proxy hop. Do not add private, loopback, or provider-wide ranges unless those exact networks are controlled proxy peers. The backend must not be directly reachable around that trusted edge.

Production currently requires TLS to the servlet/backend: use TLS passthrough or an HTTPS upstream. Edge TLS termination with a plain HTTP upstream is unsupported: secure-request enforcement remains enabled and, without framework forwarding, that topology causes HTTPS redirect loops.

Because proxy headers do not rewrite the application base URL, production must set `GOOGLE_REDIRECT_URI` to the exact public callback, for example `https://api.example.com/login/oauth2/code/google`, and register the same URI with Google.

## Production-readiness notes

### Public request limits

ApplyFlow rejects oversized input before persistence and returns validation failures as
`application/problem+json`; values are never silently truncated. Text limits count Unicode
code points, matching PostgreSQL `char_length` semantics.

| Input | Maximum |
| --- | ---: |
| Position title | 180 characters |
| Job URL / extraction URL | 1,000 characters |
| Company name, full name, location, or company search | 160 characters |
| Company website / industry | 500 / 120 characters |
| Technology name / technologies per application | 100 characters / 50 items |
| Application notes | 5,000 characters |
| Salary value / currency | 20 / 3 characters |
| Email / provider subject / account token | 254 / 255 / 256 characters |
| Password | 72 characters and 72 UTF-8 bytes |
| Repeated application statuses | 9 values |

The repository provides a strong application foundation, not a turnkey production deployment. Before public release:

1. Replace every development credential and the sample outbox key with secret-managed production values.
2. Run with the `prod` Spring profile and configure HTTPS, exact CORS origins, the exact Google callback, and a production SMTP provider.
3. Add infrastructure-level throttling, monitoring, alerting, centralized logs, database backups, and recovery testing.
4. Validate proxy/client-IP handling, capacity limits, email delivery behavior, and security controls in the target environment.
5. Deploy the frontend and backend independently and set `VITE_BACKEND_BASE_URL` to the public backend origin at build time.

The local Compose file is for development only; it does not define a production topology.
