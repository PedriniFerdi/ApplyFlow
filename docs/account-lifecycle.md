# Account lifecycle policy — beta

Use **Security → Your data and account** to download your data or permanently delete your ApplyFlow account. These controls work for password and Google-only accounts.

## Export before deletion

- The browser downloads a JSON attachment containing your profile, all owned applications/history, companies/technologies, relationships and associated shared metadata. Credentials, provider identifiers, tokens and sessions are excluded; decimal salaries are strings.
- The download is one consistent database snapshot, not a live backup. Check browser download completion and retain only valid JSON ending with `complete: true`. Errors may appear in a new tab; clicking Download does not prove success.
- Exports contain private information: store them safely. There is no import or restore feature. A download already in progress may finish after deletion.

## Confirm permanent deletion

1. Request an email code while signed in. Acceptance may mean queueing or cooldown, not successful delivery. Requesting a code deletes nothing.
2. Stay signed in to the same account. Paste the 43-character code and type exactly `DELETE`, then explicitly submit permanent deletion.
3. The default code lifetime is 15 minutes, with a one-minute request cooldown. Account/IP limits apply. Invalid/expired codes or integrity conflicts do not delete the account; no automatic retries run. If a connection fails after submission, check the account by signing in again rather than assuming the outcome.

This is verified-mailbox confirmation, not Google reauthentication or MFA; Google-only users do not need to create a password. The email links to the static Security page, never a tokenized deletion URL.

## What deletion does and does not remove

Deletion atomically removes the account, owned applications and history, personal companies/technologies, relationships, tokens/outbox records, stored sessions and account/email rate-limit buckets. Shared catalogs, other users' data and shared IP security counters remain. The current browser session is cleared only after the transaction succeeds; old sessions cannot access a replacement account using the same email.

Deletion cannot be undone in ApplyFlow. It does not delete your Google account or erase previously downloaded exports, sent/in-flight email, backups, logs or external copies. Those follow their own retention; this beta does not promise immediate erasure of those copies or a backup/log deletion deadline.

Password reset closes **all** sessions. Changing a password while signed in closes **other** sessions. Email changes, device inventory, data import and deleted-account restoration are not offered in this beta. See the [API contract](../README.md#permanent-account-deletion-api) for deployment limits and response codes.
