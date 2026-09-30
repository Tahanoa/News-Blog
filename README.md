# News-Blog

Spring Boot 4.1.1 / Java 17+ news-project foundation with persistent users, JWT authentication,
role authorization and local Ollama text generation. A minimal Persian login/signup page is
available at `/`, `/login`, and `/register`.

## Run locally — Git Bash

Stop any previous application on the same port. From the project directory:

```bash
./mvnw clean spring-boot:run -Dspring-boot.run.profiles=ai-local -Dspring-boot.run.arguments=--server.port=8081
```

Open **http://localhost:8081/login**. The `ai-local` profile binds to `127.0.0.1` and persists
users in an H2 file under `data/`; PostgreSQL is not needed in this profile. The H2 web console
is disabled. The default profile uses PostgreSQL and requires explicit configuration below.
Use `clean` for the first run after this upgrade to remove the old Basic-auth configuration class.

### Create the first administrator

There is **no default administrator or default password**. Optionally set all three bootstrap
variables before starting. They create an ADMIN only when no enabled ADMIN exists. They never
overwrite an existing administrator's password or promote a public signup account.

Git Bash:

```bash
export ADMIN_USERNAME='admin'
export ADMIN_EMAIL='your-real-email@example.com'
read -r -s -p 'Choose an admin password (12+ characters): ' ADMIN_PASSWORD
export ADMIN_PASSWORD
./mvnw clean spring-boot:run -Dspring-boot.run.profiles=ai-local -Dspring-boot.run.arguments=--server.port=8081
```

Use your own email and a unique password. Remove these bootstrap environment variables after
the initial account is created. If any bootstrap value is set, all three are required.

PowerShell alternative (**run in PowerShell, not Git Bash**):

```powershell
$env:ADMIN_USERNAME = 'admin'
$env:ADMIN_EMAIL = 'your-real-email@example.com'
$secret = Read-Host 'Choose an admin password (12+ characters)' -AsSecureString
$env:ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
.\mvnw.cmd clean spring-boot:run '-Dspring-boot.run.profiles=ai-local' '-Dspring-boot.run.arguments=--server.port=8081'
```

Without `JWT_SECRET`, the local profile creates a cryptographically random signing key each
startup. Users remain saved; previously issued JWTs stop working after restart. For stable
tokens across restarts, generate a random Base64 key of at least 32 bytes and set `JWT_SECRET`.
Never commit a signing key, database password, admin password, or `.env` file.

## PostgreSQL / default profile

Create a dedicated empty database and set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and
`JWT_SECRET`. Example URL: `jdbc:postgresql://localhost:5432/news_blog`. The signing secret
is Base64-encoded random bytes (minimum 32 bytes); the application fails startup without it
outside local/test profiles. Set optional bootstrap credentials as described above.

Flyway applies `db/migration/V1__users.sql`; Hibernate validates the schema and does not
create/update/drop tables automatically. If using an existing nonempty database, review the
migration and schema history first; no automatic Flyway baseline or destructive schema change
is enabled. PostgreSQL credentials are read from the environment, not hardcoded.

When deploying, terminate HTTPS correctly, protect the database/backups and signing key,
and configure explicit trusted origins/proxies only when required. The current UI is same-origin;
no permissive CORS configuration is enabled. Do not expose the local profile publicly.

## Roles

| Role | Permissions currently implemented |
|---|---|
| `USER` — کاربر | Own profile, password change and logout |
| `REPORTER` — خبرنگار | USER permissions plus AI APIs and `/api/reporter/**` |
| `ADMIN` — مدیر | All above, paginated user list, role and enabled-state changes |

Public signup always creates an enabled USER. A client cannot submit `role`, `enabled`,
`id`, or password hashes in signup JSON. Unknown JSON fields are rejected. Roles are checked
against the current database user on every authenticated request, not trusted from client claims.
The last enabled ADMIN cannot be disabled or demoted. There are no news/article endpoints yet;
new routes are denied until explicit authorization rules are added.

## API contract

| Method | Path | Authentication / body |
|---|---|---|
| POST | `/api/auth/register` | Public: `username`, `email`, `password` |
| POST | `/api/auth/login` | Public: `username`, `password` |
| POST | `/api/auth/logout` | Bearer JWT; revokes all of this user's sessions |
| GET | `/api/users/me` | Bearer JWT; own public user fields |
| POST | `/api/users/me/password` | Bearer JWT: `currentPassword`, `newPassword` |
| GET | `/api/admin/users?page=0&size=20` | ADMIN; size 1–100 |
| PATCH | `/api/admin/users/{id}/access` | ADMIN: `role`, `enabled` |
| GET | `/api/admin/ai/health` | REPORTER or ADMIN |
| POST | `/api/admin/ai/generate` | REPORTER or ADMIN: `prompt` |

User responses contain `id`, `username`, `email`, `role`, `enabled`, `createdAt`.
Password hashes and token versions are never included. Usernames are 3–40 ASCII letters,
digits, `.`, `_`, or `-`; usernames and emails are stored lowercase with unique database
constraints. Passwords require at least 12 characters, at most 72 UTF-8 bytes, and use salted
BCrypt with cost 12. Passwords are not recoverable plaintext or reversible encryption.
Emails are stored and validated syntactically; email verification and password-reset email
delivery are not implemented in this initial foundation.

Registration returns HTTP 201 and user fields. Login returns:

```json
{
  "accessToken": "<signed JWT>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": { "id": "...", "username": "reader", "email": "reader@example.com", "role": "USER", "enabled": true, "createdAt": "..." }
}
```

JWTs use HS256, a 15-minute default lifetime, issuer/audience/expiry validation and a
database token version. Logout, password changes and role/enabled changes revoke earlier
tokens immediately for subsequent requests. Login does not reveal whether an account is
missing, disabled, or has a wrong password. No refresh token is issued in this initial version;
users log in again after expiration. `security.jwt.access-ttl` may be adjusted up to one hour.

The browser keeps the token only in memory, never in localStorage, URLs or cookies. Refreshing
the page requires login again. API calls use the `Authorization: Bearer <token>` header.
Sessions, form login and Basic authentication are disabled. CSRF is disabled because the
application does not authenticate via cookies; if cookie-based authentication is added later,
CSRF protection must be reconsidered. CSP forbids inline scripts/styles and user values are
rendered with `textContent`, not HTML injection.

### Quick test — Git Bash

Register an ordinary user (replace the example password):

```bash
curl -i -H 'Content-Type: application/json' \
  --data-raw '{"username":"reader","email":"reader@example.com","password":"YOUR_UNIQUE_PASSWORD"}' \
  http://localhost:8081/api/auth/register
```

Login with the same credentials:

```bash
curl -i -H 'Content-Type: application/json' \
  --data-raw '{"username":"reader","password":"YOUR_UNIQUE_PASSWORD"}' \
  http://localhost:8081/api/auth/login
```

Use the returned token:

```bash
curl -i -H 'Authorization: Bearer YOUR_ACCESS_TOKEN' http://localhost:8081/api/users/me
```

For AI requests, log in as an ADMIN or REPORTER. The old `ai-test:local-test` Basic credentials
no longer work. An ADMIN can grant reporter access:

```bash
curl -i -X PATCH -H 'Authorization: Bearer ADMIN_ACCESS_TOKEN' \
  -H 'Content-Type: application/json' --data-raw '{"role":"REPORTER","enabled":true}' \
  http://localhost:8081/api/admin/users/USER_UUID/access
```

After an access change, the affected user logs in again to obtain a fresh token.

## Ollama

Run Ollama and install `qwen3:4b-instruct`. The default URL is `http://localhost:11434`.
Override with `OLLAMA_BASE_URL` / `OLLAMA_MODEL`. Health checks connectivity and model
installation without generating text. Generation sends one complete system/user request,
uses `stream: false`, temperature 0.3, context size 8192 and a 1024-token output cap.
It returns `{ "model": "...", "text": "...", "truncated": false }` and reads final content
only. `truncated: true` means the response hit the output limit. There is no conversation
history between requests. Model output remains a draft requiring factual review.

Only one generation is accepted at once. Default generation deadline is 180 seconds;
connect and health deadlines are 5 seconds. Change `ai.ollama.read-timeout` if needed.

For Persian requests from Windows, prefer a UTF-8 JSON file with `curl --data-binary @request.json`
or Unicode escapes. Pasting Persian inline into some Windows Git Bash/curl combinations can
alter the text. PowerShell can send explicit UTF-8 bytes:

```powershell
$headers = @{ Authorization = 'Bearer YOUR_ACCESS_TOKEN' }
$json = @{ prompt = 'فقط دو جمله کوتاه فارسی درباره اهمیت مطالعه بنویس.' } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/admin/ai/generate' `
  -Headers $headers -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 200
```

## Limits and errors

JSON POST/PATCH bodies are limited to 32 KiB. Signup is limited to 10 attempts/hour/IP;
login to 20 attempts/10 minutes/IP. The limiter is bounded to 5000 active IP/route windows,
uses the actual connection IP and is in-memory for a single application instance. For a
multi-instance deployment, move throttling to a shared store/gateway and explicitly configure
trusted proxy handling; restarting the application resets the current limiter.

Handled errors return `code` / `message`. No raw database or Ollama error bodies are exposed.

| HTTP | Meaning |
|---|---|
| 400 | Invalid fields, password or JSON (including unknown fields) |
| 401 | Invalid login or missing/invalid/expired/revoked JWT |
| 403 | Role lacks access |
| 409 | Username/email duplicate or last-admin protection |
| 413 | Request body too large |
| 429 | Login/signup limit or AI already busy |
| 502 | Invalid/upstream Ollama response |
| 503 | Ollama unavailable or configured model missing |
| 504 | Ollama deadline exceeded |

## Tests

```bash
./mvnw clean test
```

Tests use isolated H2 databases with Flyway and an HTTP Ollama stub; they need neither
PostgreSQL nor a downloaded model. They cover signup/password hashing, login, all roles,
privilege injection, disabled users, logout/password/access-change revocation, expired/tampered
tokens, issuer/audience/required claims, strong-key configuration, last-admin protection,
request limits, static pages/CSP and AI behavior. Live PostgreSQL connectivity and actual
model quality/speed require testing in the deployment environment.
