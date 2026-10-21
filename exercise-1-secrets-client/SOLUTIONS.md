# Solutions: Exercise 1 (secrets client SDK)

Root cause, fix, regression test and demo evidence for every ticket. See `git diff main solutions -- exercise-1-secrets-client` for the code changes.


This branch holds the CORRECT version. `main` has the six bugs below and no solutions-only tests.
All paths are relative to `src/main/java/com/acme/secrets/client/`. Line numbers refer to the buggy main.

## Verification summary

- Solution: `mvn test` gives 30 tests, 0 failures, on JDK 17.0.20.1 and on JDK 25.0.4.1 (about 4 s).
  It was still green after the bugs were injected into the repo copy.
- Main: `mvn test` gives 25 tests, 7 failures, 0 errors (5 test methods; one has 3 parameterized cases).
  Results were the same on 3 full runs on JDK 17, 3 runs of only the failing tests, and 1 run on JDK 25.
- Single-fix check: main plus all solution tests, with ONE bug file restored from this snapshot.
  Each fix makes only that bug's tests pass and only that ticket's demo behave correctly, whatever order is used.
- On main, the four solutions-only tests fail for their bugs (messages below).
- Demos were run with `mvn -q compile exec:java -Dexec.args=SDK-10x`, with the wrapper (`./mvnw`, Maven 3.9.16),
  and as a plain `java` process (the way IntelliJ runs it).

## Failing tests on main

| Test | Bug | Assertion message on main |
|---|---|---|
| `SecretTest.toString_secretRecord_doesNotContainValue` | SDK-101 | `Expecting actual: "Secret[id=prod/orders/db-password, value=Ord3rs-Pa55word, version=3]" not to contain: "Ord3rs-Pa55word"` |
| `RetryPolicyTest.isRetryable_clientError_returnsFalse` [400], [403], [404] | SDK-102 | `Expecting value to be false but was true` (x3) |
| `SecretsClientTest.getSecret_unknownSecret_sendsSingleRequest` | SDK-102 | `expected: 1 but was: 4` |
| `SecretCacheTest.get_afterTtlElapsed_returnsEmpty` | SDK-103 | `Expecting an empty Optional but was containing value: Secret[id=prod/orders/db-password, value=orders-pass-1, version=1]` |
| `SecretsClientTest.getSecret_afterCacheTtl_returnsRotatedValue` | SDK-103 | `expected: "orders-pass-2" but was: "orders-pass-1"` |

All other tests on main pass: 20 test cases, including `getSecrets_severalIds_returnsAllInRequestOrder`
(the token is fetched first, so the SDK-106 race cannot happen in it).

## Solutions-only tests (in this snapshot only)

| Test | Bug | Result on main |
|---|---|---|
| `http/SecretPathsTest.secretUri_idWithReservedCharacters_encodesItAsOnePathSegment` | SDK-104 | error: `IllegalArgumentException: Illegal character in fragment at index 59: https://secrets.example.com/v1/secrets/payments/stripe#live key` |
| `SecretsClientTest.getSecret_idWithHashOrSpace_returnsThatSecret` [`payments/stripe#live`], [`hr/db password`] | SDK-104 | `#`: got `["payments/stripe", "sk_test_value"]`; space: `IllegalArgumentException: Illegal character in path at index 39` |
| `SecretsClientTest.getSecret_tokenRevokedServerSide_refreshesTokenAndSucceeds` | SDK-105 | error: `AuthenticationException: Not authorized to read secret prod/orders/api-key: HTTP 401` |
| `auth/TokenProviderConcurrencyTest.getToken_concurrentCallersOnExpiredToken_fetchOneToken` | SDK-106 | `Expecting empty but was: [SecretsClientException: Token request failed: HTTP 429, ...]` |

## SDK-101: secret values appear in application logs (easy, security)

- Where: `Secret.java:6`. The `toString()` override was removed.
- Buggy:
  ```java
  public record Secret(String id, String value, int version) {
  }
  ```
- Fixed:
  ```java
  public record Secret(String id, String value, int version) {

      @Override
      public String toString() {
          return "Secret[id=" + id + ", version=" + version + ", value=****]";
      }
  }
  ```
- Why: `SecretsClient.fetchAndCache` logs `log.info("Fetched secret {}", secret)`, and the record's
  generated `toString()` contains the value.
- Failing test on main: `SecretTest.toString_secretRecord_doesNotContainValue`.
- Demo on main (`Demo SDK-101`):
  ```
  [INFO] SecretsClient - Fetched secret Secret[id=prod/payments/db-password, value=Pa55w0rd-demo, version=1]
  Fetched prod/payments/db-password. Check the log lines above.
  ```
  Correct version: `Fetched secret Secret[id=prod/payments/db-password, version=1, value=****]`.

## SDK-102: reading a missing secret takes seconds to fail (easy-medium)

- Where: `http/RetryPolicy.java:13`.
- Buggy: `return status >= 400;`
- Fixed: `return status == 429 || status >= 500;`
- Why: 404 (and 400, 401, 403) are retried with backoff. With the defaults (3 retries, 200 ms initial backoff),
  a missing secret costs 4 requests and 200 + 400 + 800 ms of sleep before `SecretNotFoundException`.
- Failing tests on main: `RetryPolicyTest.isRetryable_clientError_returnsFalse` (3 cases),
  `SecretsClientTest.getSecret_unknownSecret_sendsSingleRequest`.
- Demo on main (`Demo SDK-102`):
  ```
  [WARN] ApiClient - Request for secret prod/payments/does-not-exist failed with HTTP 404, retry 1/3 in 200 ms
  [WARN] ApiClient - Request for secret prod/payments/does-not-exist failed with HTTP 404, retry 2/3 in 400 ms
  [WARN] ApiClient - Request for secret prod/payments/does-not-exist failed with HTTP 404, retry 3/3 in 800 ms
  getSecret("prod/payments/does-not-exist") took 1475 ms -> SecretNotFoundException: Secret not found: prod/payments/does-not-exist
  Requests the server received for this id: 4
  ```
  Correct version: `took 48 ms`, `Requests the server received for this id: 1`.

## SDK-103: rotated secret not picked up after the cache TTL (medium)

- Where: `cache/SecretCache.java:17`, `:22` and `:39`.
- Buggy:
  ```java
  private final long ttl;
  ...
  this.ttl = ttl.toMillis();
  ...
  entries.put(secret.id(), new Entry(secret, clock.instant().plusSeconds(ttl)));
  ```
- Fixed:
  ```java
  private final Duration ttl;
  ...
  this.ttl = ttl;
  ...
  entries.put(secret.id(), new Entry(secret, clock.instant().plus(ttl)));
  ```
- Why: the TTL is stored in milliseconds but added as seconds, so it is 1000 times too long.
  The default 5 minutes becomes 300,000 s (about 3.5 days).
- Failing tests on main: `SecretCacheTest.get_afterTtlElapsed_returnsEmpty`,
  `SecretsClientTest.getSecret_afterCacheTtl_returnsRotatedValue`.
- Demo on main (`Demo SDK-103`, client cache TTL 2 s):
  ```
  First read:  version 1: 'orders-pass-v1'
  Rotated prod/orders/db-password on the server to version 2.
  Waiting 3 s (the client's cache TTL is 2 s)...
  Second read: version 1: 'orders-pass-v1'
  ```
  Correct version: `Second read: version 2: 'orders-pass-v2'`.

## SDK-104: secret ids with '#' or spaces return the wrong secret or fail (medium, security)

- Where: `http/SecretPaths.java:14`. The `encodePathSegment` helper and its imports were removed with it.
- Buggy:
  ```java
  return URI.create(withoutTrailingSlash(baseUri) + "/v1/secrets/" + secretId);
  ```
- Fixed:
  ```java
  return URI.create(withoutTrailingSlash(baseUri) + "/v1/secrets/" + encodePathSegment(secretId));

  private static String encodePathSegment(String value) {
      // URLEncoder applies form encoding, which turns a space into '+'; a path segment needs %20.
      return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
  ```
- Why: `#live` becomes a URI fragment that is never sent, so the server reads `payments/stripe` (the TEST key).
  A space makes `URI.create` throw. Ids with `/` still work by accident, because the fake server decodes everything
  after `/v1/secrets/`.
- Failing tests on main: none (the solutions-only tests cover it).
- Demo on main (`Demo SDK-104`):
  ```
  [INFO] FakeSecretsServer - GET /v1/secrets/payments/stripe -> 200
  'payments/stripe#live' -> version 1: 'sk_test_...demo'
  'hr/db password' -> IllegalArgumentException: Illegal character in path at index 39: http://127.0.0.1:59307/v1/secrets/hr/db password
  ```
  Correct version: the server logs `GET /v1/secrets/payments%2Fstripe%23live -> 200`, the client gets `'sk_live_...demo'`
  and `'hr-pass-demo'`.

## SDK-105: first read after access tokens are revoked fails (medium-hard)

- Where: `http/ApiClient.java:58`. The request is built once, before the `while (true)` loop on line 61.
- Buggy:
  ```java
  URI uri = SecretPaths.secretUri(baseUri, secretId);
  HttpRequest request = authorizedGet(uri);
  boolean tokenRefreshed = false;
  int retries = 0;
  while (true) {
      HttpResponse<String> response = send(request);
  ```
- Fixed:
  ```java
  URI uri = SecretPaths.secretUri(baseUri, secretId);
  boolean tokenRefreshed = false;
  int retries = 0;
  while (true) {
      HttpRequest request = authorizedGet(uri);
      HttpResponse<String> response = send(request);
  ```
- Why: on 401 the loop invalidates the token and `continue`s, but it sends the same request again with the old
  `Authorization` header. The second 401 becomes an `AuthenticationException`. The next call works because it builds
  a new request with a new token.
- Interplay on main: SDK-102 also makes 401 retryable, so on main the stale request is retried 3 more times
  (about 1.4 s) before the exception. Once SDK-102 is fixed, it fails right after the second 401.
- Failing tests on main: none (the solutions-only test covers it).
- Demo on main (`Demo SDK-105`):
  ```
  1. prod/inventory/api-key -> version 1: 'inventory-api-key-demo'
     The platform team revoked all access tokens.
  [INFO] FakeSecretsServer - GET /v1/secrets/prod/inventory/db-password -> 401   (5 times)
  [WARN] ApiClient - Request for secret prod/inventory/db-password failed with HTTP 401, retry 1/3 in 200 ms   (then 2/3, 3/3)
  2. prod/inventory/db-password -> AuthenticationException: Not authorized to read secret prod/inventory/db-password: HTTP 401
  3. prod/inventory/db-password -> version 1: 'inventory-pass-demo'
  Token requests: 2
  ```
  Correct version: one 401, a new token, then 200. Step 2 prints `version 1: 'inventory-pass-demo'`, and there are 2 token requests.

## SDK-106: intermittent HTTP 429 errors when reading secrets in parallel (hard, concurrency)

- Where: `auth/TokenProvider.java:52-58`. The `synchronized (lock)` block, the re-check and the `lock` field
  were removed. `volatile` was kept (line 41).
- Buggy:
  ```java
  public AccessToken getToken() {
      AccessToken current = token;
      if (!isFresh(current)) {
          current = fetchToken();
          token = current;
      }
      return current;
  }
  ```
- Fixed:
  ```java
  private final Object lock = new Object();
  ...
  public AccessToken getToken() {
      AccessToken current = token;
      if (isFresh(current)) {
          return current;
      }
      synchronized (lock) {
          current = token;
          if (!isFresh(current)) {
              current = fetchToken();
              token = current;
          }
          return current;
      }
  }
  ```
- Why: after the token expires, each of the 8 pool threads fetches its own token. The fake server admits 5 token
  requests per second and answers 429 to the rest. Those reads fail with `Token request failed: HTTP 429`.
- Failing tests on main: none (the solutions-only test covers it).
- Demo on main (`Demo SDK-106`, the same result in 5 of 5 runs on JDK 17 and 1 run on JDK 25):
  ```
  Read prod/catalog/key-01; the client now holds a token valid for 3 s.
  First batch, 20 ids: got all 20 secrets
  Waiting 3 s for the access token to expire...
  [INFO] FakeSecretsServer - POST /v1/auth/token -> 200   (5 times)
  [INFO] FakeSecretsServer - POST /v1/auth/token -> 429   (3 times)
  Second batch, 20 ids: SecretsClientException: Failed to fetch 3 of 20 secrets (first failure: SecretsClientException: Token request failed: HTTP 429)
  Token requests during the second batch: 8
  ```
  Correct version: `Second batch, 20 ids: got all 20 secrets` and `Token requests during the second batch: 1`.

