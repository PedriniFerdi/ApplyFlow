# Production release contract

ApplyFlow production startup validates configuration, but it does not select or provision a provider. Choose the
runtime, public domains, TLS path, trusted proxy CIDRs, database, SMTP service, and secret store before using this
runbook. Never copy the development `.env.example` into production.

## Required environment inventory

Store values in the selected platform's secret or configuration facility. The inventory records names and
classification only; it intentionally contains no credentials, domains, CIDRs, or provider commands.

| Classification | Environment inputs |
| --- | --- |
| Secret | `DB_PASSWORD`, `GOOGLE_CLIENT_SECRET`, `MAIL_PASSWORD`, `OUTBOX_ENCRYPTION_KEY` |
| Restricted configuration | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `GOOGLE_CLIENT_ID`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_FROM`, `MAIL_MESSAGE_ID_DOMAIN`, `TRUSTED_PROXY_HEADER_MODE`, `TRUSTED_PROXY_CIDRS` |
| Public runtime configuration | `SPRING_PROFILES_ACTIVE`, `FRONTEND_APP_URL`, `CORS_ALLOWED_ORIGINS`, `GOOGLE_REDIRECT_URI`, `SESSION_COOKIE_SAME_SITE`, `SESSION_COOKIE_SECURE`, `MAIL_DELIVERY_ENABLED`, `MAIL_SMTP_AUTH`, `MAIL_STARTTLS`, `MAIL_STARTTLS_REQUIRED` |
| Public build configuration | `VITE_BACKEND_BASE_URL` |

Production requires the `prod` Spring profile. Startup rejects local/sample database and outbox settings,
placeholder Google credentials, non-HTTPS or incoherent browser origins/callbacks, unsafe cookie/forwarding
settings, unauthenticated or optional-cleartext SMTP, and local/invalid sender or Message-ID domains. Errors name
invalid properties, never their values.

## Release sequence

1. **Freeze the candidate.** Record the application revision, migration set, expected schema version, and rollback
   candidate. Confirm the selected TLS/proxy topology satisfies the trusted proxy contract in `README.md`.
2. **Protect recovery.** Complete the database backup procedure supplied by the selected provider and prove the
   restore target can access the same outbox key before changing schema or traffic.
3. **Migrate before traffic.** Run the candidate's Flyway migrations as a pre-traffic release phase. Require a
   successful schema version and Hibernate validation before starting or shifting traffic to application replicas.
4. **Start without provider calls.** Startup validation must pass before Google or SMTP smoke tests. Then run those
   provider tests through the selected staging topology; startup success alone is not provider readiness.
5. **Shift traffic and observe.** Move traffic only after database, application, authentication, and mail checks pass.

## Schema and rollback rules

- Use expand/contract evolution: add backward-compatible schema first, deploy compatible readers/writers, migrate
  data, stop old writes, and remove old schema only in a later release.
- Never run a blind down migration. Flyway history and persisted data are production state, not disposable files.
- A code-only rollback is allowed only after proving the older binary accepts the current schema **and current data**.
  V8 adds `ACCOUNT_DELETION`; binaries predating that value are unsafe once such rows exist.
- If compatibility is unproven or false, keep traffic on the last compatible binary and forward-fix with a new
  migration or application revision. Do not edit an applied migration.

## Outbox key lifecycle

`OUTBOX_ENCRYPTION_KEY` is one stable AES-256 key per environment. Every replica, release, worker, backup restore,
and rollback that may read queued mail must receive the same key. Keep it in the secret store and recovery material;
never print it, bake it into an image, or replace it during an ordinary deploy.

Rotation is not currently supported because rows do not carry a key identifier. Losing or changing the key makes
pending encrypted tokens undecryptable. A future rotation must first add versioned key identifiers, dual-read
support, and a tested re-encryption/retirement procedure.

## Failure recovery

Stop traffic promotion on any failed migration, validation, or smoke test. Preserve logs with secrets redacted,
retain the database and Flyway history, and diagnose before retrying. Prefer a forward fix. Use code rollback only
when the compatibility gate above is documented as passing; otherwise restore only through the selected provider's
tested recovery procedure with the matching outbox key.
