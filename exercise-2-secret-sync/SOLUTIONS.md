# Solutions: Exercise 2 (secret sync service)

Root cause, fix, regression test and demo evidence for every ticket. See `git diff main solutions -- exercise-2-secret-sync` for the code changes.


This branch holds the correct version. `main` has the six bugs below.
Line numbers refer to `main` unless marked "solution".
All paths are relative to `src/main/java/com/acme/secrets/sync/`.

## Verification summary

| Check | Result |
|---|---|
| Solution, JDK 17 (Maven 3.10.0, local repo `m2-ex2`) | 27/27 green |
| Solution, JDK 25 | 27/27 green |
| Solution, re-checked after injection | 27/27 green |
| Main, JDK 17, 3 runs | 22 run, 17 pass, 4 failures + 1 error, identical each run |
| Main, Maven 3.9.16 via `./mvnw` | same 5 tests fail |
| Main, JDK 25 | same 5 tests fail |
| Solution tests run against main's production code | all 5 solutions-only tests fail, each for its bug |
| Demo, all 6 tickets, solution | correct behaviour |
| Demo, all 6 tickets, main | each shows its ticket's symptom (output below) |

Failing tests on main, the same set in all runs:

- `GlobMatcherTest.compile_singleStar_doesNotMatchNestedPath` (SYNC-202)
- `GlobMatcherTest.compile_pattern_doesNotMatchIdThatMerelyContainsIt` (SYNC-202)
- `GlobMatcherTest.compile_dot_isLiteral` (SYNC-202)
- `SourceScannerTest.scan_totalNotMultipleOfPageSize_returnsAllSecrets` (SYNC-201)
- `TokenBucketRateLimiterTest.acquire_bucketDrainedAtTenPerSecond_grantsTenMorePermitsWithinOneSecond` (SYNC-205; reported as an error, because `acquire` throws)

SYNC-203, SYNC-204 and SYNC-206 have no failing test on main. The demo shows each of them.

---

## SYNC-201: pagination drops the last partial page (easy)

- **File:** `engine/SourceScanner.java:48` (solution: line 41), in `listAll()`
- **Buggy:** `int pageCount = first.total() / pageSize;`
- **Fixed:** `int pageCount = (first.total() + pageSize - 1) / pageSize;`
- **Failing test on main:** `SourceScannerTest.scan_totalNotMultipleOfPageSize_returnsAllSecrets`
  ```
  [secrets returned by scan]
  expected: 250
   but was: 200
  ```
  A single partial page (42) and an exact multiple (200) still pass.
- **Demo on main (`SYNC-201`):**
  ```
  [INFO] SourceScanner - Scanned 200 vault secrets, 200 match policy 'payments-app'
  [INFO] SyncEngine - Sync cycle finished: policy=payments-app status=SUCCESS created=200 updated=0 deleted=0 failed=0
  Vault secrets matching prod/payments/*: 250
  Secrets in target aws-payments-app: 200
  Missing from the target: 50 (first prod/payments/svc-201, last prod/payments/svc-250)
  ```
- **Demo, solution:** `Secrets in target aws-payments-app: 250`, `Missing from the target: none`.

## SYNC-202: naive glob leaks admin and preprod secrets (medium, security)

- **File:** `config/GlobMatcher.java:16-17`
- **Buggy** (the whole class body is this naive translation):
  ```java
  Pattern pattern = Pattern.compile(glob.replace("*", ".*"));
  return id -> pattern.matcher(id).find();
  ```
- **Fixed** (solution lines 19-20 plus `toRegex`/`appendLiteral`, lines 23-44):
  ```java
  Pattern pattern = Pattern.compile(toRegex(glob));   // literal runs Pattern.quote'd; ** -> .*, * -> [^/]*, ? -> [^/]
  return id -> pattern.matcher(id).matches();
  ```
- **Failing tests on main:**
  ```
  GlobMatcherTest.compile_singleStar_doesNotMatchNestedPath  [pattern prod/payments/*] ... not to accept "prod/payments/admin/root-signing-key" but it did.
  GlobMatcherTest.compile_pattern_doesNotMatchIdThatMerelyContainsIt  [pattern prod/payments/*] ... not to accept "preprod/payments/db-password" but it did.
  GlobMatcherTest.compile_dot_isLiteral  [pattern prod/app.v1/*] ... not to accept "prod/appXv1/key" but it did.
  ```
- **Demo on main (`SYNC-202`):**
  ```
  [INFO] SourceScanner - Scanned 4 vault secrets, 4 match policy 'payments-app'
  Secrets in target aws-payments-app (4):
    preprod/payments/db-password
    prod/payments/admin/root-signing-key
    prod/payments/db-password
    prod/payments/stripe-api-key
  ```
- **Demo, solution:** only `prod/payments/db-password` and `prod/payments/stripe-api-key`.

## SYNC-203: vault outage is treated as an empty vault, so every target secret is deleted (medium, critical)

- **File:** `engine/SourceScanner.java:34-39`, in `scan()`. The import of `VaultUnavailableException` was added on line 8.
- **Buggy:**
  ```java
  List<SecretRef> all;
  try {
      all = listAll();
  } catch (VaultUnavailableException e) {
      log.warn("Could not list vault secrets for policy '{}': {}", policy.name(), e.getMessage());
      return List.of();
  }
  ```
- **Fixed** (solution line 32): `List<SecretRef> all = listAll();` The exception propagates, and `SyncEngine.runAll` reports the cycle as `FAILED` without touching the target.
- **Failing test on main:** none.
- **Solutions-only tests**, shown failing against main's code:
  - `SourceScannerTest.scan_vaultUnavailable_throws`: `Expecting actual not to be null`. Nothing was thrown.
  - `SyncEngineTest.runAll_vaultUnavailable_deletesNothing`: `Expecting actual: [] to contain exactly in any order: [...jwt-signing-key, ...stripe-api-key, ...db-password]`.
- **Demo on main (`SYNC-203`), cycle 2 after `vault.setAvailable(false)`:**
  ```
  [WARN] SourceScanner - Could not list vault secrets for policy 'payments-app': Vault returned 503 Service Unavailable
  [INFO] SyncEngine - Plan for policy 'payments-app': 0 creates, 0 updates, 5 deletes
  [INFO] FakeCloudSecretStore - [aws-payments-app] DELETE prod/payments/db-password      (x5)
  Report: policy=payments-app status=SUCCESS created=0 updated=0 deleted=5 failed=0
  Secrets in target (0):
  ```
- **Demo, solution:** `[ERROR] SyncEngine - Sync cycle for policy 'payments-app' aborted: Vault returned 503 Service Unavailable`, then `Report: policy=payments-app status=FAILED ... reason=Vault returned 503 Service Unavailable`. All 5 secrets stay in the target.

## SYNC-204: diff looks up the target by the raw source id, so the secret flaps (medium-hard)

- **File:** `engine/DiffCalculator.java:21`
- **Buggy:** `TargetSecret existing = remaining.remove(source.id());`. It still creates under `targetName`.
- **Fixed:** `TargetSecret existing = remaining.remove(targetName);`
- **Failing test on main:** none. The listed DiffCalculator and SyncEngine tests use ids that need no mapping.
- **Solutions-only tests**, shown failing against main's code:
  - `DiffCalculatorTest.plan_sourceIdNeedsMapping_matchesExistingTargetSecret`: `Expecting empty but was: [CREATE ...stripe_webhook_secret, DELETE ...stripe_webhook_secret]`.
  - `SyncEngineTest.runCycle_secretNameNeedsMapping_isStableAcrossCycles`: actual `[CREATE ...stripe_webhook_secret, DELETE ...stripe_webhook_secret]`.
- **Demo on main (`SYNC-204`)**, with vault ids `prod/payments/db-password` and `prod/payments/stripe webhook secret`:
  ```
  --- Cycle 1 ---  Plan: 2 creates, 0 updates, 0 deletes   -> target: db-password, stripe_webhook_secret
  --- Cycle 2 ---  Plan: 1 creates, 0 updates, 1 deletes
  [INFO] FakeCloudSecretStore - [aws-payments-app] DELETE prod/payments/stripe_webhook_secret
  Report: policy=payments-app status=SUCCESS created=0 updated=0 deleted=1 failed=0
  Secrets in target (1): prod/payments/db-password
  --- Cycle 3 ---  Plan: 1 creates, 0 updates, 0 deletes   -> created=1, target has both again
  --- Cycle 4 ---  Plan: 1 creates, 0 updates, 1 deletes   -> deleted=1, target: db-password only
  ```
  The CREATE in cycles 2 and 4 fails with `ResourceExistsException`. On main the failure is silent because of SYNC-206. Once SYNC-206 is fixed it shows as `Failed to CREATE prod/payments/stripe_webhook_secret: Secret prod/payments/stripe_webhook_secret already exists` with `PARTIAL_FAILURE`.
- **Demo, solution:** cycles 2 to 4 plan `0 creates, 0 updates, 0 deletes`, and both secrets stay.

## SYNC-205: integer token math, so the bucket never refills under polling (hard)

- **File:** `engine/TokenBucketRateLimiter.java:18` and `:56`
- **Buggy:**
  ```java
  private long tokens;
  long newTokens = elapsedMillis * permitsPerSecond / 1000;
  ```
  `lastRefillMillis = now` is still set on every call. With 20 ms polling at 10 permits/s, each refill rounds to 0 and resets the clock.
- **Fixed:**
  ```java
  private double tokens;
  double newTokens = elapsedMillis * permitsPerSecond / 1000.0;
  ```
- **Failing test on main:** `TokenBucketRateLimiterTest.acquire_bucketDrainedAtTenPerSecond_grantsTenMorePermitsWithinOneSecond`
  ```
  acquire_...:48->acquireTimes:66 » RateLimitTimeout No rate-limit permit within 5000 ms
  ```
  `tryAcquire_withinCapacity_grantsImmediately` and `tryAcquire_afterOneIdleSecond_refillsToCapacity` still pass. A 1000 ms gap refills exactly 10 tokens.
- **Demo on main (`SYNC-205`)**: 20 secrets, limiter 10 permits/s, burst 10, 8 workers.
  ```
  Rate limit: 10 permits/s, burst capacity 10; secrets to sync: 20
  Elapsed: 4.1 s
  Report: policy=payments-app status=SUCCESS created=10 updated=0 deleted=0 failed=0
  Secrets in target: 10 of 20
  ```
  This was stable across runs (4.0 to 4.1 s, always 10 of 20). The 10 permit timeouts are silent because of SYNC-206. Once SYNC-206 is fixed they show as `Failed to CREATE prod/payments/svc-0NN: No rate-limit permit within 2000 ms`.
- **Demo, solution:** `Elapsed: 1.0 s`, `created=20`, `Secrets in target: 20 of 20`. This held in 8 of 8 runs.

## SYNC-206: futures ignored, so task failures vanish (medium)

- **File:** `engine/SyncExecutor.java:55`, in `applyAll()`. The imports of `ExecutionException` and `Future` were removed.
- **Buggy:**
  ```java
  try {
      pool.invokeAll(tasks);
  } catch (InterruptedException e) { ... }
  ```
- **Fixed** (solution lines 57-66):
  ```java
  List<Future<Void>> results = pool.invokeAll(tasks);
  for (int i = 0; i < results.size(); i++) {
      try { results.get(i).get(); }
      catch (ExecutionException e) {
          SyncOperation op = ops.get(i);
          report.recordFailed();
          log.error("Failed to {} {}: {}", op.kind(), op.targetName(), e.getCause().getMessage());
      }
  }
  ```
- **Failing test on main:** none.
- **Solutions-only test**, shown failing against main's code: `SyncEngineTest.runCycle_targetRejectsSecret_reportsFailure` gives `expected: PARTIAL_FAILURE but was: SUCCESS`.
- **Demo on main (`SYNC-206`)**: 3 normal secrets plus `prod/payments/tls-bundle` with a 70,000-character value.
  ```
  Vault holds 4 secrets, including prod/payments/tls-bundle (70000 characters)
  [INFO] SyncEngine - Plan for policy 'payments-app': 4 creates, 0 updates, 0 deletes
  Report: policy=payments-app status=SUCCESS created=3 updated=0 deleted=0 failed=0
  Secrets in target (3): db-password, jwt-signing-key, stripe-api-key
  ```
  No ERROR line appears.
- **Demo, solution:** `[ERROR] SyncExecutor - Failed to CREATE prod/payments/tls-bundle: Secret value exceeds 65536 bytes`, then `status=PARTIAL_FAILURE created=3 ... failed=1`.

---

