# Tickets: SecretSync

You are not expected to fix all six. The real interview most likely has fewer and simpler bugs. Start with the easy ones, and treat the hard one as an optional extra.

| Ticket | Title | Severity | Difficulty | Failing test on main? |
|---|---|---|---|---|
| SYNC-203 | Every target secret deleted during a vault maintenance window | Critical (data loss) | Medium | No, use the demo |
| SYNC-202 | Admin-only and preprod secrets appear in the app's AWS account | High (security) | **Easy-medium** | Yes |
| SYNC-206 | A secret never reaches the target, and the report says SUCCESS | High | Medium | No, use the demo |
| SYNC-201 | Customers with more than 100 secrets miss some of them | Medium | **Easy** | Yes |
| SYNC-204 | A secret disappears from the target every other cycle | Medium | Medium-hard | No, use the demo |
| SYNC-205 | Large tenants: sync stalls after the first few secrets | Medium | Hard (optional) | Yes |

Easy mode: SYNC-201, SYNC-202 and SYNC-203.

---

## SYNC-203: every target secret deleted during a vault maintenance window

**Severity:** Critical (data loss, outage). **Reporter:** Customer incident. **Version:** 2.3.0.

During a planned vault maintenance window, SecretSync deleted every synced secret from the customer's AWS account. The customer's apps failed until the next successful cycle recreated the secrets.

**Reproduce:** run **Demo SYNC-203**.

**Evidence:**
```
--- Vault maintenance window starts: the vault is unavailable ---
[WARN] SourceScanner - Could not list vault secrets for policy 'payments-app': Vault returned 503 Service Unavailable
[INFO] SyncEngine - Plan for policy 'payments-app': 0 creates, 0 updates, 5 deletes
[INFO] FakeCloudSecretStore - [aws-payments-app] DELETE prod/payments/db-password
...
Report: policy=payments-app status=SUCCESS created=0 updated=0 deleted=5 failed=0
Secrets in target (0):
```
**Expected:** when the vault is down, the cycle aborts and the target is left untouched.

<details><summary>Hint 1: where to look</summary>Follow the WARN line back to its source.</details>
<details><summary>Hint 2: what to inspect</summary>What does <code>SourceScanner.scan</code> return when the vault throws? What does <code>DiffCalculator</code> conclude from that?</details>
<details><summary>Hint 3: the concept</summary>"I could not read the source" and "the source is empty" are different answers.</details>

---

## SYNC-202: admin-only and preprod secrets appear in the app's AWS account

**Severity:** High (security). **Reporter:** Security team. **Version:** 2.3.0.

The `payments-app` policy uses the pattern `prod/payments/*`. A security review found `prod/payments/admin/root-signing-key` (admins only) and `preprod/payments/db-password` in the app's AWS account.

**Reproduce:** run **Demo SYNC-202**, or the tests in `GlobMatcherTest`.

**Actual:**
```
Secrets in target aws-payments-app (4):
  preprod/payments/db-password
  prod/payments/admin/root-signing-key
  prod/payments/db-password
  prod/payments/stripe-api-key
```
**Expected:** only `prod/payments/db-password` and `prod/payments/stripe-api-key`. See the pattern contract in the README.

<details><summary>Hint 1: where to look</summary><code>GlobMatcher.compile</code>.</details>
<details><summary>Hint 2: what to inspect</summary>Evaluate the regex it builds for <code>prod/payments/*</code>, then test it against each id in Evaluate Expression.</details>
<details><summary>Hint 3: the concept</summary>Three separate questions: does the match cover the whole id, can <code>*</code> cross a <code>/</code>, and what does <code>.</code> mean in a regex?</details>

---

## SYNC-206: a secret never reaches the target, and the report says SUCCESS

**Severity:** High. **Reporter:** Customer support. **Version:** 2.3.0.

A customer's `prod/payments/tls-bundle` never appears in AWS. The sync report says SUCCESS with `failed=0`, and the logs show no error.

**Reproduce:** run **Demo SYNC-206**.

**Actual:**
```
Vault holds 4 secrets, including prod/payments/tls-bundle (70000 characters)
[INFO] SyncEngine - Plan for policy 'payments-app': 4 creates, 0 updates, 0 deletes
Report: policy=payments-app status=SUCCESS created=3 updated=0 deleted=0 failed=0
Secrets in target (3): db-password, jwt-signing-key, stripe-api-key
```
**Expected:** an ERROR log, `failed=1`, and a status that is not SUCCESS. (The target store rejects values larger than 65,536 bytes. That limit is correct.)

<details><summary>Hint 1: where to look</summary>The plan had 4 creates and the report counts 3. Where do the operations run?</details>
<details><summary>Hint 2: what to inspect</summary>Set an exception breakpoint on <code>ValidationException</code>. Where does that exception go after it is thrown on a worker thread?</details>
<details><summary>Hint 3: the concept</summary>An exception inside a task submitted to an <code>ExecutorService</code> is stored in its <code>Future</code>.</details>

---

## SYNC-201: customers with more than 100 secrets miss some of them

**Severity:** Medium. **Reporter:** Customer support. **Version:** 2.3.0.

A customer with 250 secrets under `prod/payments/` sees only 200 in AWS, always the alphabetically last ones missing. Customers with 100 or fewer secrets are fine.

**Reproduce:** run **Demo SYNC-201**, or `SourceScannerTest.scan_totalNotMultipleOfPageSize_returnsAllSecrets`.

**Actual:**
```
Vault secrets matching prod/payments/*: 250
Secrets in target aws-payments-app: 200
Missing from the target: 50 (first prod/payments/svc-201, last prod/payments/svc-250)
```

<details><summary>Hint 1: where to look</summary>The paging loop in <code>SourceScanner</code>.</details>
<details><summary>Hint 2: what to inspect</summary>How many pages does it decide to read for 250 secrets with a page size of 100?</details>
<details><summary>Hint 3: the concept</summary>Integer division.</details>

---

## SYNC-204: a secret disappears from the target every other cycle

**Severity:** Medium. **Reporter:** Customer support. **Version:** 2.3.0.

The secret `prod/payments/stripe webhook secret` (with spaces) is present in AWS after one cycle and gone after the next, over and over. Apps that read it fail intermittently.

**Reproduce:** run **Demo SYNC-204** (four cycles).

**Actual (cycles 3 and 4):**
```
--- Cycle 3 ---
[INFO] SyncEngine - Plan for policy 'payments-app': 1 creates, 0 updates, 0 deletes
[INFO] FakeCloudSecretStore - [aws-payments-app] CREATE prod/payments/stripe_webhook_secret (v1)
--- Cycle 4 ---
[INFO] SyncEngine - Plan for policy 'payments-app': 1 creates, 0 updates, 1 deletes
[INFO] FakeCloudSecretStore - [aws-payments-app] DELETE prod/payments/stripe_webhook_secret
Secrets in target (1):
  prod/payments/db-password
```
**Expected:** after the first cycle, no further changes.

<details><summary>Hint 1: where to look</summary>Why does cycle 4 plan a create and a delete for a secret that did not change?</details>
<details><summary>Hint 2: what to inspect</summary>In <code>DiffCalculator.plan</code>, compare the keys of the target map with the value used to look them up.</details>
<details><summary>Hint 3: the concept</summary>Two systems, two names for the same thing.</details>

---

## SYNC-205: large tenants: sync stalls after the first few secrets (hard, optional)

**Severity:** Medium. **Reporter:** SRE. **Version:** 2.3.0.

With a rate limit of 10 operations per second, a tenant with 20 new secrets gets only 10 into the target, and the cycle takes much longer than 2 seconds.

**Reproduce:** run **Demo SYNC-205**, or `TokenBucketRateLimiterTest.acquire_bucketDrainedAtTenPerSecond_grantsTenMorePermitsWithinOneSecond`.

**Actual:**
```
Rate limit: 10 permits/s, burst capacity 10; secrets to sync: 20
Elapsed: 4.1 s
Report: policy=payments-app status=SUCCESS created=10 updated=0 deleted=0 failed=0
Secrets in target: 10 of 20
```
(The missing 10 fail silently because of SYNC-206.)

<details><summary>Hint 1: where to look</summary><code>TokenBucketRateLimiter.refill</code>.</details>
<details><summary>Hint 2: what to inspect</summary>Callers poll every 20 ms. How many new tokens does one refill add after 20 ms at 10 per second? What happens to the clock?</details>
<details><summary>Hint 3: the concept</summary>Integer math rounds the fraction away, and the remainder is thrown away on every call.</details>
