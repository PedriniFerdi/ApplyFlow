# Contributing to ApplyFlow

Use the issue-first flow so each change has an approved purpose and reviewable boundary.

## Quick path
1. Submit a structured bug or feature issue.
2. Wait for `status:approved`.
3. Branch from the requested base.
4. Implement one work unit and run relevant checks.
5. Commit with an imperative-English Conventional Commit.
6. Open a PR that closes the issue and has one `type:*` label.

## Branch names
Branches must match:
```text
^(feat|fix|chore|docs|style|refactor|perf|test|build|ci|revert)/[a-z0-9._-]+$
```
Examples: `feat/application-filters`, `fix/oauth-callback`, `chore/repository-governance`.

## Commits
Use `type(optional-scope): imperative description`. Allowed types are `build`, `chore`, `ci`, `docs`, `feat`, `fix`, `perf`, `refactor`, `revert`, `style`, and `test`. Scopes use lowercase letters, digits, `.`, `_`, or `-`; `!` before `:` marks a breaking change.

```text
feat(applications): add status filtering
fix(auth): reject expired callback state
docs(contributing): clarify review checks
chore(governance): add repository policy
```

Keep each work unit, its tests, and related documentation in one commit. Historical import PRs must have exactly one commit. Attribution trailers, including `Co-Authored-By`, are forbidden.

## Stage explicitly
Name every intended path:
```bash
git status --short
git diff -- path/to/file another/path
git add path/to/file another/path
git diff --cached --stat
```
Never use `git add .` or `git add -A`; broad staging can capture secrets, generated files, or unrelated work.

## Pull requests
Every PR must:
- contain `Closes #N`, `Fixes #N`, or `Resolves #N`;
- link an issue with `status:approved`;
- have exactly one `type:*` label;
- use a Conventional Commit title and commit messages; and
- complete the PR template.

Use `size:exception` only with a documented rationale and review boundary.

## Checks
Run applicable checks and record exact commands and results in the PR:
```bash
cd backend && ./mvnw test
cd frontend && npm test
cd frontend && npm run build
```
If a check cannot run or does not apply, explain why and provide the closest bounded evidence.

## Security
Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
