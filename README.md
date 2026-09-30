# News-Blog — Local AI API

The `ai-local` profile exposes only the backend integration for testing a locally installed
Ollama model. It binds to `127.0.0.1`, uses HTTP Basic authentication, and excludes database
auto-configuration so PostgreSQL is not required for this test mode. Other profiles keep
the project's existing database/security configuration. No UI or automatic publishing is added.

## Start on Windows (PowerShell)

Requirements: JDK 17+, Ollama running, and the downloaded model:

```powershell
ollama pull qwen3:4b-instruct
```

From the repository directory:

```powershell
$env:AI_TEST_PASSWORD = 'choose-a-local-password'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=ai-local"
```

For Git Bash/Linux/macOS:

```bash
AI_TEST_PASSWORD='choose-a-local-password' ./mvnw spring-boot:run -Dspring-boot.run.profiles=ai-local
```

Defaults for this **local-only** profile: user `ai-test`, password `local-test`, model
`qwen3:4b-instruct`, Ollama URL `http://localhost:11434`, application port `8080`.
Set `AI_TEST_USER`, `AI_TEST_PASSWORD`, `OLLAMA_BASE_URL`, and `OLLAMA_MODEL` as environment
variables to override them. Do not expose this profile publicly or reuse its test credentials
for deployment. Ollama must run on the same machine as Java, unless its URL is overridden.

## API contract

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/admin/ai/health` | Check Ollama connectivity and whether the configured model is installed |
| POST | `/api/admin/ai/generate` | Generate final text from one complete prompt |

Both routes require Basic authentication. Health checks `/api/tags`; it does not load the
model or generate text. Generation calls `/api/chat` with a Persian editorial system prompt
and the complete user prompt. It uses `stream: false`, temperature `0.3`, context size `8192`,
and a maximum of `1024` generated tokens. Only `message.content` is returned, never the
separate thinking field. There is no conversation history between API requests.

### Health test (PowerShell)

```powershell
$pair = "ai-test:$env:AI_TEST_PASSWORD"
$token = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
$headers = @{ Authorization = "Basic $token" }
Invoke-RestMethod -Uri 'http://localhost:8080/api/admin/ai/health' -Headers $headers
```

Expected response:

```json
{"status":"UP","model":"qwen3:4b-instruct","modelAvailable":true}
```

### Generate test (PowerShell, preserves Persian UTF-8)

```powershell
$body = @{
    prompt = 'فقط بر اساس خبر زیر یک تیتر کوتاه و یک خلاصه یک‌جمله‌ای بنویس. زمان و اعداد را حفظ کن. خبر: کتابخانه محله از شنبه با افزایش دو ساعت زمان فعالیت، تا ساعت هشت شب پذیرای مراجعه‌کنندگان خواهد بود. قالب پاسخ: تیتر: ... خلاصه: ...'
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/admin/ai/generate' `
    -Headers $headers -ContentType 'application/json; charset=utf-8' `
    -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 200
```

Example response (actual text varies):

```json
{
  "model": "qwen3:4b-instruct",
  "text": "تیتر: فعالیت کتابخانه محله از شنبه تا ساعت هشت شب\nخلاصه: ...",
  "truncated": false
}
```

`prompt` is required, must not be blank, and accepts at most 12,000 characters.
`truncated: true` means the model reached the output token limit; review the incomplete text.
The initial generation can take longer while the model loads. The default generation deadline
is 180 seconds, connect timeout is 5 seconds, and health deadline is 5 seconds. Override
`ai.ollama.read-timeout` if needed. Only one generation is accepted at a time to limit local
resource usage. Text remains an editorial draft; model output still needs factual review.

### Errors

Handled errors return `{"code":"...","message":"..."}` without exposing Ollama's raw response.

| HTTP status | Code / cause |
|---|---|
| 400 | `INVALID_INPUT`: invalid JSON, missing/blank/too-long prompt |
| 401 | Missing or incorrect Basic credentials |
| 429 | `AI_BUSY`: another generation is running |
| 503 | `OLLAMA_UNAVAILABLE`, `MODEL_NOT_FOUND`, or `REQUEST_INTERRUPTED` |
| 504 | `OLLAMA_TIMEOUT` |
| 502 | `OLLAMA_ERROR` or `INVALID_OLLAMA_RESPONSE` |

## Tests

```powershell
.\mvnw.cmd test
```

The local API integration tests use an HTTP stub for Ollama; they do not need a downloaded
model or PostgreSQL. They verify authentication, input validation, the outgoing request,
final-content handling, model availability, upstream errors, token-limit reporting and timeouts.
Actual model quality and speed must be tested on the Windows machine hosting Ollama.
