# Tickets: secrets-client 2.3

You are not expected to fix all six. The real interview most likely has fewer and simpler bugs. Start with the easy ones tonight, and treat the hard one as an optional extra.

| Ticket | Title | Severity | Difficulty | Failing test on main? |
|---|---|---|---|---|
| SDK-104 | Wrong secret returned for ids with `#`; ids with spaces crash | Critical (security) | Medium | No, use the demo |
| SDK-101 | Secret values in the application log | High (security) | **Easy** | Yes |
| SDK-105 | First read after a token revocation fails | High | Medium | No, use the demo |
| SDK-106 | HTTP 429 errors when reading secrets in parallel | High | Hard (optional) | No, use the demo |
| SDK-103 | A rotated password is not picked up for days | Medium | **Easy** | Yes |
| SDK-102 | Reading a missing secret takes seconds to fail | Medium | **Easy** | Yes |

Easy mode: SDK-101, SDK-102 and SDK-103.

---

## SDK-104: wrong secret returned for ids with `#`; ids with spaces crash

**Severity:** Critical (security). **Reporter:** Payments team. **Version:** 2.3.0.

The Payments team stores the live Stripe key as `payments/stripe#live` and the test key as `payments/stripe`. In production, `getSecret("payments/stripe#live")` returned the **test** key, with no error. The HR team reports that `getSecret("hr/db password")` throws an exception.

**Reproduce:** run **Demo SDK-104**.

**Expected:** each id returns its own secret.
**Actual:**
```
'payments/stripe#live' -> version 1: 'sk_test_...demo'
'hr/db password' -> IllegalArgumentException: Illegal character in path at index 39: http://127.0.0.1:59881/v1/secrets/hr/db password
```

<details><summary>Hint 1: where to look</summary>Compare the path in the fake server's log line with the id you asked for. Then find where the client builds the URL.</details>
<details><summary>Hint 2: what to inspect</summary>Put a breakpoint where the request URI is built. Evaluate <code>uri.getRawPath()</code> and <code>uri.getFragment()</code>.</details>
<details><summary>Hint 3: the concept</summary>Some characters have a special meaning in a URL. Read the API contract in the README again.</details>

---

## SDK-101: secret values in the application log

**Severity:** High (security). **Reporter:** AppSec review. **Version:** 2.3.0.

A log review found plaintext secret values in an application's INFO logs. The SDK must never log a secret value.

**Reproduce:** run **Demo SDK-101**, or the test `SecretTest.toString_secretRecord_doesNotContainValue`.

**Evidence:**
```
[INFO] SecretsClient - Fetched secret Secret[id=prod/payments/db-password, value=Pa55w0rd-demo, version=1]
```

<details><summary>Hint 1: where to look</summary>Find the log statement that prints the line above.</details>
<details><summary>Hint 2: what to inspect</summary>What text does the logger get for the object it prints? Evaluate it in the debugger.</details>
<details><summary>Hint 3: the concept</summary>What does Java generate for a record by default? Compare <code>Secret</code> with <code>AccessToken</code>.</details>

---

## SDK-105: first read after a token revocation fails

**Severity:** High. **Reporter:** Platform team. **Version:** 2.3.0.

During an incident drill, the platform team revoked all access tokens. Every service then failed its **next** secret read with `AuthenticationException`. The read after that worked. The SDK documentation promises that on a 401 the SDK gets a new token and retries once.

**Reproduce:** run **Demo SDK-105**.

**Actual:**
```
1. prod/inventory/api-key -> version 1: 'inventory-api-key-demo'
   The platform team revoked all access tokens.
2. prod/inventory/db-password -> AuthenticationException: Not authorized to read secret prod/inventory/db-password: HTTP 401
3. prod/inventory/db-password -> version 1: 'inventory-pass-demo'
```
**Expected:** step 2 succeeds after one 401 and one new token.

<details><summary>Hint 1: where to look</summary>The 401 branch in <code>ApiClient.fetchSecret</code>.</details>
<details><summary>Hint 2: what to inspect</summary>After the token is invalidated, which <code>Authorization</code> header does the next request carry? Does <code>TokenProvider.getToken()</code> run again before the retry?</details>
<details><summary>Hint 3: the concept</summary>A value computed before a loop does not change inside it.</details>

---

## SDK-106: HTTP 429 errors when reading secrets in parallel (hard, optional)

**Severity:** High. **Reporter:** Catalog team. **Version:** 2.3.0.

A service reads 20 secrets in parallel with `getSecrets`. A few minutes after startup, some reads fail with `Token request failed: HTTP 429`. The auth service team says the client sends bursts of token requests, and its rate limit (5 per second) rejects the extra ones.

**Reproduce:** run **Demo SDK-106**.

**Actual:**
```
Second batch, 20 ids: SecretsClientException: Failed to fetch 3 of 20 secrets (first failure: SecretsClientException: Token request failed: HTTP 429)
Token requests during the second batch: 8
```
**Expected:** one token request when the token expires, and no failures.

<details><summary>Hint 1: where to look</summary>Who calls <code>TokenProvider.getToken()</code>, and from how many threads at once?</details>
<details><summary>Hint 2: what to inspect</summary>Add a non-suspending logging breakpoint in <code>getToken()</code> that logs <code>Thread.currentThread().getName()</code>. Stopping the threads would hide the bug.</details>
<details><summary>Hint 3: the concept</summary>Check-then-act on shared state, without a lock.</details>

---

## SDK-103: a rotated password is not picked up for days

**Severity:** Medium. **Reporter:** Orders team. **Version:** 2.3.0.

The Orders team rotated their database password. Their service kept using the old password for days, although the cache TTL is 5 minutes.

**Reproduce:** run **Demo SDK-103** (cache TTL 2 seconds), or the tests `SecretCacheTest.get_afterTtlElapsed_returnsEmpty` and `SecretsClientTest.getSecret_afterCacheTtl_returnsRotatedValue`.

**Actual:**
```
First read:  version 1: 'orders-pass-v1'
Rotated prod/orders/db-password on the server to version 2.
Waiting 3 s (the client's cache TTL is 2 s)...
Second read: version 1: 'orders-pass-v1'
```

<details><summary>Hint 1: where to look</summary><code>SecretCache</code>: how does it decide when an entry expires?</details>
<details><summary>Hint 2: what to inspect</summary>Break right after <code>put</code> and evaluate the stored expiry time. Compare it with now plus the TTL.</details>
<details><summary>Hint 3: the concept</summary>Units.</details>

---

## SDK-102: reading a missing secret takes seconds to fail

**Severity:** Medium. **Reporter:** SRE. **Version:** 2.3.0.

A health check reads an optional secret that does not exist. Each check takes seconds and sometimes times out. The server receives 4 requests for every miss.

**Reproduce:** run **Demo SDK-102**, or the tests `RetryPolicyTest.isRetryable_clientError_returnsFalse` and `SecretsClientTest.getSecret_unknownSecret_sendsSingleRequest`.

**Actual:**
```
[WARN] ApiClient - Request for secret prod/payments/does-not-exist failed with HTTP 404, retry 1/3 in 200 ms
[WARN] ApiClient - Request for secret prod/payments/does-not-exist failed with HTTP 404, retry 2/3 in 400 ms
[WARN] ApiClient - Request for secret prod/payments/does-not-exist failed with HTTP 404, retry 3/3 in 800 ms
getSecret("prod/payments/does-not-exist") took 1475 ms -> SecretNotFoundException: Secret not found: prod/payments/does-not-exist
Requests the server received for this id: 4
```
**Expected:** a fast `SecretNotFoundException` after one request.

<details><summary>Hint 1: where to look</summary>The retry decision in <code>ApiClient.fetchSecret</code>.</details>
<details><summary>Hint 2: what to inspect</summary>Which status codes does <code>RetryPolicy</code> treat as retryable?</details>
<details><summary>Hint 3: the concept</summary>Is a 404 likely to turn into a 200 in 200 ms? Which errors are transient?</details>
