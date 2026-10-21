# Exercise 1: secrets client SDK

You just joined the SDK team of "Acme Secrets Manager". The team ships `secrets-client` 2.3, a Java library that applications embed to read secrets. It trades an API key for a short-lived access token, fetches secrets over REST, caches them in memory with a TTL, and retries transient failures. `FakeSecretsServer` runs the REST API in-process, so everything works on your laptop. Six tickets landed this week: see `TICKETS.md`.

## How the debugging part works

The bugs are described in `TICKETS.md`, like Jira tickets. The code does not mention them. For each ticket:

1. Read the ticket in `TICKETS.md`.
2. See the bug happen: choose **Demo SDK-10x** in IntelliJ's run dropdown (top right) and press Run, or run the failing test the ticket names.
3. Find the code that causes it. Use the debugger.
4. Fix it.
5. Run the demo or the test again, and check that the bug is gone.

Example: SDK-101 says secret values appear in the log. Run **Demo SDK-101**: the console prints a log line with the password in plain text. Find where that line is logged and why the password is in it, fix it, and the test `SecretTest` turns green.

## Run it

- **IntelliJ:** File > Open > this folder (`exercise-1-secrets-client`, not the repo root). Let it load Maven. If it asks for a JDK, choose Download JDK, version 17 or newer.
- **Tests:** run the **All tests** configuration (top-right dropdown). 7 test cases fail on purpose. They belong to the tickets.
- **Demos:** run **Demo SDK-101** to **Demo SDK-106**. Each one reproduces one ticket and prints what happened.
- **Terminal:** `./mvnw test` and `./mvnw -q compile exec:java -Dexec.args=SDK-104`. On Windows PowerShell: `.\mvnw.cmd test`.

## Pick a mode

This folder holds more than two hours of material on purpose. A 2-hour session uses only part of it:

| Mode | Time | Plan |
|---|---|---|
| **Easy** (start here tonight) | 60 min | Debug SDK-101, SDK-103 and SDK-102 (each has a failing test), 30 min. Research B2 as a comparison table only, 15 min. Design B2 spoken, no code, 15 min. |
| **Realistic 2 hours** (the reported order: debug, research, design) | 2 h | Orientation 10 min. Debug SDK-101, SDK-103, SDK-102, then SDK-104 if time is left, 40 min. Research B2, 20 min. Design B2 and implement a thin slice, 40 min. Wrap-up 10 min. |
| **Extra practice** (any time, no clock) | - | SDK-105, SDK-106 (hard), Research A, Research B, Design A. |

In the orientation, explain the request flow out loud: `SecretsClient.getSecret` > `SecretCache` > `ApiClient` > `TokenProvider` > server.

## Code map

| Package | What is in it |
|---|---|
| `com.acme.secrets.client` | `SecretsClient` (public API: `getSecret`, `getSecrets`), `SecretsClientConfig`, `Secret`, the exceptions |
| `...client.auth` | `TokenProvider` (API key to access token), `AccessToken` |
| `...client.cache` | `SecretCache`, the in-memory TTL cache |
| `...client.http` | `ApiClient` (REST calls and the retry loop), `RetryPolicy`, `SecretPaths` (URL building) |
| `...client.fake` | `FakeSecretsServer`, a local fake of the API with knobs: failures, latency, token TTL, token revocation |
| `...client.demo` | `DemoApp`, one scenario per ticket |

**API contract**
- `POST /v1/auth/token` with header `X-Api-Key`. Response: `{"access_token": "...", "expires_in": 900}`, in seconds.
- `GET /v1/secrets/{id}` with `Authorization: Bearer <token>`. Response: `{"id": "...", "value": "...", "version": 3}`.
- The secret id is sent as **one URL-encoded path segment**: `/` becomes `%2F`, `#` becomes `%23`, a space becomes `%20`.
- Retries: retry HTTP 429, 5xx and I/O errors with exponential backoff. On a 401, get a new token and retry once. Fail fast on other 4xx.
- Cache: an entry lives for `cacheTtl` (default 5 minutes).

## Research prompts

- **B2. Pick a cache library (20 min).** The SDK must keep serving secrets during a short Secrets Manager outage, and should refresh hot secrets before they expire. Compare **Caffeine** and **Guava Cache**, plus "keep our own `SecretCache`". Criteria: refresh-ahead (`refreshAfterWrite`), what happens to the old value when a refresh fails, expiry options, size-based eviction, async loading, testability (custom ticker or clock), Java version support, license, maintenance activity, dependency footprint. Deliver a comparison table and a recommendation with its risks.
- **A. Remove the static API key, the "secret zero" (25 min).** How does a workload authenticate to a secrets manager without a stored secret? Cover Kubernetes pods (projected service-account JWT) and AWS workloads (IAM role, signed STS `GetCallerIdentity`), and how CyberArk Conjur and HashiCorp Vault implement each. Deliver a 5-minute explanation with a sequence sketch, what `TokenProvider` would need, the top security risks, and what to ship first.
- **B. Should `Secret.value()` be a `String`? (20 min).** Research `char[]` versus `String` for secrets in Java: memory dumps, immutability, what the Java Cryptography Architecture guide says, what real SDKs do. Recommend an API.

## Design prompts

- **B2. Stale-if-error (design 15 min, then implement 25 min).** When a fetch fails with HTTP 5xx, 429 or an I/O error, and the cache holds a value that expired at most `maxStale` ago (new config, for example 15 minutes), return the stale value and log a WARN. Never serve a stale value after 401, 403 or 404: revoked access or a deleted secret must stop working. Count the stale serves. Design it first: a behaviour table, the config, the security trade-off. Then build a thin slice in `SecretCache` and `SecretsClient` with a test that uses `MutableClock` and `FakeSecretsServer.failNextSecretRequests`.
- **A. Rotation-aware secret delivery at scale (35 min).** A customer runs 20,000 pods in 40 clusters. Each pod reads about 30 secrets at startup, so a rolling deploy sends about 600,000 requests in a few minutes and gets throttled. A daily database-password rotation causes auth errors for up to the cache TTL. Design the next version: for example a node-level agent or sidecar cache, change notifications, versions or ETags, rotation with overlapping credentials. Cover the API, data flow, consistency, failure modes (the server is down: serve stale, and for how long?), security of local caches, observability, and rollout. Use `../cheatsheets/design-checklist.md`.

## Debugging rules

1. Pick tickets by severity. `TICKETS.md` lists them in that order.
2. Reproduce first, with the failing test or the demo.
3. Find the root cause with the debugger. Say your hypothesis before you test it.
4. Fix the cause, not the symptom. Check other callers of the code you change.
5. SDK-104, SDK-105 and SDK-106 have no failing test. Reproduce them with the demo, then write the test yourself.
6. Explain each fix in two sentences: the cause, and why the fix is right.

Use AI like a colleague: narrow questions, say out loud what you ask, verify by running code. Do not paste the whole project and ask for the bugs.

## After the session

- Did you reproduce each bug before you fixed it? Did you fix causes, not symptoms? Did you add tests? Did you talk the whole time? Did you call out the security impact (secrets in logs, the wrong secret returned)?
- Solutions: `git checkout solutions`, then `git diff main solutions -- exercise-1-secrets-client`. On that branch this folder also has `SOLUTIONS.md` (every bug) and `MODEL_ANSWERS.md` (the research and design parts).
