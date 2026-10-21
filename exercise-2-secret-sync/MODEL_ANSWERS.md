# Exercise 2 (secret-sync) — model answers: research and design

> Written from fact-checked research, but this file itself did not get its own fact-check pass. Check numbers and limits against the linked sources before you quote them.

These are reference answers for the research and design prompts of Exercise 2. The exercise is a mock "SecretSync" engine that copies secrets from a source vault into cloud secret stores, in the spirit of CyberArk (now Idira) Secrets Hub.

**How to use this file**
1. **After your attempt:** do the prompt on a timer first. Then read the matching section and compare. Do not read ahead.
2. **As a practice partner:** use these to judge a live answer. The companion file `INTERVIEWER_RESEARCH_DESIGN.md` has the handout lines, hints and score guide.

**What separates a senior answer.** Each section marks these with "**Senior signal:**". A mid-level answer is correct and complete. A senior answer states the trust boundary, picks a trade-off out loud, names a failure mode before being asked, and says how it verifies the claim.

**Facts and sources.** Facts are cited inline as links. The dates were checked on Maven Central, NVD, EPSS and the official docs on 2026-10-21. "UNVERIFIED" marks a claim that could not be confirmed. Do not repeat a number in the interview that you have not seen in a primary source. In the real interview the internet and AI are allowed, so open the doc and read the number out.

---

# Research A — New targets: Azure Key Vault and GCP Secret Manager

**Prompt.** SecretSync syncs to an AWS-Secrets-Manager-like store today. Product wants to add Azure Key Vault and GCP Secret Manager as targets. Research their constraints versus AWS. List the 3-5 things that would break the current engine, with a recommendation for each.

## 5-minute spoken summary

- The three stores agree on the shape (named secret, versioned value, tags or labels) but disagree on every limit that the engine hard-codes.
- Our engine assumes the **AWS** rules: name charset `A-Za-z0-9/_+=.@-`, value up to 65,536 bytes, delete with a recovery window.
- **Azure** is the strictest. Names allow only `0-9 a-z A-Z -`. Names are **case-insensitive**. Values cap at **25 KB**. Soft-delete is on and effectively mandatory, so a deleted name **cannot be reused** until it is recovered or purged.
- **GCP** names allow `A-Za-z0-9-_`, values go to **64 KiB**, and **delete is irreversible** with no recovery window.
- Five things break: the name mapper, the size check, delete-then-recreate, the rate limiter, and treating a stale or partial list as the truth.
- My recommendation is a **per-target adapter**: each target owns its name rule, size limit, delete semantics and rate budget. The engine keeps one diff algorithm and asks the adapter.
- The biggest risk is deletion. Different stores punish a wrong delete differently, so the engine must never delete from an empty or partial source. That also motivates the deletion guard in the other exercise.

## Key facts, with sources

| Constraint | AWS Secrets Manager | Azure Key Vault | GCP Secret Manager |
|---|---|---|---|
| Name charset / length | `A-Za-z0-9/_+=.@-`, 1–512 ([CreateSecret](https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_CreateSecret.html)) | `0-9 a-z A-Z -`, 1–127, **case-insensitive** ([about identifiers](https://learn.microsoft.com/en-us/azure/key-vault/general/about-keys-secrets-certificates)) | `A-Za-z0-9-_`, ≤255 ([creating secrets](https://cloud.google.com/secret-manager/docs/creating-and-accessing-secrets)) |
| Max value size | 65,536 bytes ([limits](https://docs.aws.amazon.com/secretsmanager/latest/userguide/reference_limits.html)) | **25 KB** octets ([about secrets](https://learn.microsoft.com/en-us/azure/key-vault/secrets/about-secrets)) | 64 KiB ([quotas](https://cloud.google.com/secret-manager/quotas)) |
| New value makes | a new version ([CreateSecret](https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_CreateSecret.html)) | a new version ([set secret](https://learn.microsoft.com/en-us/rest/api/keyvault/secrets/set-secret/set-secret)) | a new version, states ENABLED/DISABLED/DESTROYED ([versions](https://cloud.google.com/secret-manager/docs/reference/rest/v1/projects.secrets.versions)) |
| Delete semantics | recovery window 7–30 days, default 30, or force ([DeleteSecret](https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_DeleteSecret.html)) | **soft-delete 7–90 days, default 90, cannot disable** ([soft-delete](https://learn.microsoft.com/en-us/azure/key-vault/general/soft-delete-overview)) | **irreversible, no window**, version destroy can delay 1–1,000 days ([delete](https://cloud.google.com/secret-manager/docs/delete-secrets), [delay destroy](https://cloud.google.com/secret-manager/docs/delay-destruction-of-secret-versions)) |
| Reuse a deleted name | `InvalidRequestException` until gone ([repost](https://repost.aws/knowledge-center/delete-secrets-manager-secret)) | **HTTP 409 `ObjectIsDeletedButRecoverable`** ([real error](https://github.com/external-secrets/external-secrets/issues/3519)), recover with [Recover Deleted Secret](https://learn.microsoft.com/en-us/rest/api/keyvault/secrets/recover-deleted-secret/recover-deleted-secret) | irreversible, so the ID is gone ([delete](https://cloud.google.com/secret-manager/docs/delete-secrets)) |
| Write budget | 50 TPS shared Put+Update, 50 Create, 50 Delete ([limits](https://docs.aws.amazon.com/secretsmanager/latest/userguide/reference_limits.html)) | **300 creates / 10 s and 4,000 other / 10 s per vault** ([service limits](https://learn.microsoft.com/en-us/azure/key-vault/general/service-limits)) | **600 writes / min per project**, 2 qps per global secret ([quotas](https://cloud.google.com/secret-manager/quotas)) |
| Throttle signal | `ThrottlingException`, HTTP 400 ([errors](https://docs.aws.amazon.com/secretsmanager/latest/apireference/CommonErrors.html)) | **HTTP 429**, failed 429s do not count ([throttling](https://learn.microsoft.com/en-us/azure/key-vault/general/overview-throttling)) | `RESOURCE_EXHAUSTED` / HTTP 429 ([codes](https://github.com/googleapis/googleapis/blob/master/google/rpc/code.proto)) |
| List a page | ≤100, eventually consistent up to 5 min ([ListSecrets](https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_ListSecrets.html)) | ≤25, returns tags + attributes but **not the value** ([Get Secrets](https://learn.microsoft.com/en-us/rest/api/keyvault/secrets/get-secrets/get-secrets)) | ≤25,000 ([list](https://cloud.google.com/secret-manager/docs/reference/rest/v1/projects.secrets/list)) |
| Metadata | 50 tags, 128 / 256 ([tags](https://docs.aws.amazon.com/secretsmanager/latest/userguide/managing-secrets_tagging.html)) | 15 tags, 512 / 512 ([about secrets](https://learn.microsoft.com/en-us/azure/key-vault/secrets/about-secrets)) | 64 labels (63/63) + annotations <16 KiB ([secrets resource](https://cloud.google.com/secret-manager/docs/reference/rest/v1/projects.secrets)) |
| Change events | EventBridge: label-updated + CloudTrail ([events](https://docs.aws.amazon.com/secretsmanager/latest/userguide/secret-event-notifications.html)) | Event Grid: new version / near-expiry / expired, **no delete event** ([Event Grid](https://learn.microsoft.com/en-us/azure/key-vault/general/event-grid-overview)) | Pub/Sub, includes `SECRET_DELETE` ([events](https://cloud.google.com/secret-manager/docs/event-notifications)) |

## The 3-5 things that break the engine, and the fix for each

The engine's own code (from the spec) hard-codes AWS rules in `TargetNameMapper`, `FakeCloudSecretStore` (the 65,536-byte check), `DiffCalculator` (delete orphans), and one `TokenBucketRateLimiter`.

| # | What breaks | Why (concrete) | Recommendation |
|---|---|---|---|
| 1 | **Name mapping** (`TargetNameMapper.toTargetName`) | It maps to the AWS charset `[A-Za-z0-9/_+=.@-]`. Azure forbids `/ _ + = . @`, so `prod/payments/db-password` is an illegal Azure name. Azure is case-insensitive, so `DB-Pass` and `db-pass` collide into one secret. | Give **each target its own name mapper**. For Azure, replace every char outside `[0-9A-Za-z-]` and detect case-folding collisions before writing. Keep the raw source id in a tag or label so you can map back. |
| 2 | **Value size limit** | `FakeCloudSecretStore` rejects values over 65,536 bytes. Azure's real limit is **25 KB** (25,600 bytes). A 30 KB secret syncs to AWS and fails on Azure with a validation error. | Ask the adapter for its max size **before** the write. On overflow, record the operation as failed, do not abort the cycle, and alert. Never truncate a secret value. |
| 3 | **Delete-then-recreate** | After a rename the engine deletes the old orphan and creates a new name. On Azure the old name is soft-deleted and **cannot be reused** (409) until recovered or purged. On GCP the delete is **irreversible**. On AWS a create on a name scheduled for deletion fails. | Make delete a **per-target operation**. On Azure, recover-and-update instead of create when the target name is soft-deleted. On GCP, prefer a new version or disable over destroy. Treat every delete as high risk and cap it (see the deletion guard). |
| 4 | **Rate limits** | One global `TokenBucketRateLimiter`. Real budgets differ and are windowed: AWS 50 TPS per op type, Azure 300 creates / 10 s plus 4,000 other / 10 s **per vault**, GCP 600 writes / min **per project**. One global rate either throttles us or gets us a 429. | Use **one limiter per (target, operation class)** sized to that provider's window. On `ThrottlingException` / 429 / `RESOURCE_EXHAUSTED`, back off and retry. Azure and AWS say failed-throttled calls still cost you, so slow down, do not hammer. |
| 5 | **Treating a stale or partial list as truth** | Comparison deletes any managed target secret that is missing from the source list. AWS list is eventually consistent up to 5 minutes, and a page can be dropped. A lag then looks like "the source deleted it". | Never delete from an empty or partial source. Require the scan to **succeed and be complete** before any delete. Compare by source version to avoid needless rewrites (which burn the 100-version AWS cap). This is the same root cause as ticket SYNC-203. |

**Senior signal:** proposes a **target adapter interface** (the engine owns the diff, the adapter owns name, size, delete and rate) instead of `if (azure) ... else ...` branches scattered in the engine.
**Senior signal:** says deletes are the dangerous direction and that the three stores punish a wrong delete differently, so the safe default is to defer a delete, not to force it.
**Senior signal:** notes Azure list returns tags but not the value, so detecting a value change needs a GET per secret, which spends the 4,000 / 10 s budget — a real cost to design around.

## Risks of the recommendation

- A per-target adapter adds a layer. Keep the diff algorithm in one place so the behaviour stays testable with fakes.
- Azure soft-delete means a "recreate" path must handle recover and purge. That is new error handling and new permissions (`secrets/recover`).
- GCP irreversibility means a bug deletes real secrets with no undo. The deletion guard and dry-run matter most here.

## Common mistakes

- Quoting AWS limits as if they were universal. The value size and the delete model differ per store.
- Forgetting Azure case-insensitivity, so two source ids silently collapse to one target secret.
- Designing deletes as if every store had a recovery window. GCP does not.
- Ignoring that throttled calls still count against Azure and AWS budgets, so retrying fast makes it worse.
- Rewriting unchanged secrets every cycle, which burns the AWS 100-version cap and the write budget.

## Follow-up questions the interviewer may ask

1. **"A secret is 40 KB. It syncs to AWS but not Azure. What do you do?"** Record the Azure write as failed, keep the AWS write, alert the operator, and surface the reason (over 25 KB). Do not truncate. Offer a product decision: split the secret, or exclude that policy from the Azure target.
2. **"On Azure a rename leaves the old name soft-deleted. The new cycle wants that name back. What happens?"** A create fails with 409 `ObjectIsDeletedButRecoverable`. The adapter must recover the deleted secret, then update its value, or purge it first if the value must not survive. Both need extra permission and are per-target logic.
3. **"GCP delete is irreversible. How do you make a rename safe?"** Do not destroy on a rename. Add the new name, keep the old for a grace period, and let the deletion guard and a reconcile pass remove it only when it is a confirmed orphan. Prefer disabling a version over destroying it.
4. **"How do you detect a value change on Azure without downloading every secret?"** You cannot from the list alone — it returns metadata, not the value. Use the source version as the source of truth. Store the last-synced source version in a target tag, and GET only when the source version advanced.
5. **"Which store would you add first, and why?"** GCP or AWS parity first, because their write models are closest to what we have. Azure last, because soft-delete, case-insensitivity and the 25 KB cap need the most new code. State it as a risk-ordering, not a preference.
6. **"How do you test three stores without three cloud accounts?"** A fake per store that enforces that store's real rules (charset, size, delete, 429). The spec already uses a fake cloud store. Extend the pattern, one fake per provider, injected `Clock` and `Sleeper` so tests do not sleep.

---

# Research B — CVE-2022-1471 in snakeyaml 1.33

**Prompt.** A scanner flagged CVE-2022-1471 in snakeyaml 1.33. SecretSync lets tenant admins upload policy YAML, which `PolicyLoader` parses with `new Yaml().load(...)`. Triage it: is it exploitable here, how bad, what is the fix, how do you verify, and who do you tell?

## 5-minute spoken summary

- CVE-2022-1471 is **unsafe deserialization** in SnakeYAML. The default `Constructor` that `new Yaml()` uses will build **any class on the classpath** named by a YAML global tag. That can be remote code execution.
- Severity: NVD rates it **9.8 Critical**, the vendor (Google) rates it **8.3 High**. EPSS on 2026-10-21 is **0.996**, near the top. It is **not** in CISA KEV ([NVD](https://nvd.nist.gov/vuln/detail/CVE-2022-1471), [EPSS](https://api.first.org/data/v1/epss?cve=CVE-2022-1471)).
- Is it exploitable **here**? This is the key judgment. Our `PolicyLoader` calls `new Yaml().load(...)` on **tenant-uploaded** YAML. That is exactly the dangerous combination: the untrusted input reaches `Constructor`. So yes, treat it as exploitable, not theoretical.
- I confirmed the mechanism locally. On 1.33, `new Yaml().load` of a one-line global-tag payload **built my canary class**. On 2.x the same payload is **rejected** before any object is built.
- Fix: upgrade to a 2.x line, prefer the latest **2.7** (released 2026-08-30), and parse untrusted YAML with `SafeConstructor` plus `LoaderOptions` limits. 2.x is a breaking change.
- Verify: `mvn dependency:tree` to prove 1.x is gone, pin the version, and add a regression test that loads a malicious payload and asserts it throws.
- Tell: the team lead and product security, record a VEX decision, and if we think it was exploited, the affected tenants.

## Key facts, with sources

- **What it is.** "SnakeYaml's Constructor() class does not restrict types which can be instantiated during deserialization. Deserializing yaml content provided by an attacker can lead to remote code execution." CWE-502 and CWE-20 ([NVD](https://nvd.nist.gov/vuln/detail/CVE-2022-1471)).
- **Scores.** NVD **9.8 Critical** `AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H`. CNA (Google) **8.3 High** `AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:L`. I recomputed both from the vectors and they are right ([GHSA](https://github.com/advisories/GHSA-mjmj-j48q-9wg2)).
- **Exploit signals.** EPSS 0.99569 on 2026-10-21, near the maximum. Not in CISA KEV ([EPSS](https://api.first.org/data/v1/epss?cve=CVE-2022-1471)).
- **Affected and fixed.** All versions ≤ 1.33. There is no 1.34. The fix shipped as **2.0** on 2023-02-26. The latest is **2.7**, released 2026-08-30 ([Maven metadata](https://repo1.maven.org/maven2/org/yaml/snakeyaml/maven-metadata.xml)).
- **Mechanism.** A global tag such as `!!javax.script.ScriptEngineManager [[...URLClassLoader...]]` or `!!com.sun.rowset.JdbcRowSetImpl` (a JNDI lookup) makes `Constructor` instantiate a gadget class. The object's side effects run **before** the type-mismatch exception is thrown ([Snyk](https://snyk.io/blog/unsafe-deserialization-snakeyaml-java-cve-2022-1471/), [Google advisory](https://github.com/google/security-research/security/advisories/GHSA-mjmj-j48q-9wg2)).
- **What 2.x changed.** SnakeYAML 2.0 added a `TagInspector` that rejects global tags by default. `new Yaml()` still uses `Constructor`, but the tag inspector blocks the attack. 2.7 also removed Compact Object Notation, which bypassed the inspector (issue 1115) ([changelog](https://github.com/snakeyaml/snakeyaml/blob/master/src/changes/changes.xml)).

## Is it exploitable here? The triage

Run the standard dependency-CVE checklist. The answer turns on the trust boundary.

| Step | Finding for SecretSync |
|---|---|
| Presence and path | snakeyaml 1.33 is a direct dependency (the `pom.xml` pins it). Confirm with `mvn dependency:tree`. |
| Reachability | `PolicyLoader` calls `new Yaml().load(...)`. `new Yaml()` uses the unsafe `Constructor`. The vulnerable code is on the execution path. |
| Input trust boundary | **Tenant admins upload the policy YAML in the web console.** The input is attacker-influenced. This is the deciding fact. |
| Exploit preconditions | RCE needs a gadget class on the classpath (for example a JDBC or script-engine gadget) and, for the URL and JNDI payloads, outbound network egress. Do not rely on "we have no gadget" — the classpath changes over time. |
| Likelihood | EPSS 0.996, public proof-of-concept payloads exist. Not in KEV, but that is weak comfort for a near-top EPSS. |

**Conclusion: treat it as exploitable.** Even if a working RCE gadget is not on today's classpath, untrusted YAML reaching `Constructor` is a critical, latent flaw. Rate it high for our deployment because the input is multi-tenant and attacker-controlled.

**I verified the mechanism locally** (SnakeYAML 1.33 vs 2.7, JDK 17):
- 1.33, `new Yaml().load("policies:\n  - !!check.Canary []")` → the canary class was **constructed** (its constructor ran). Proof the attacker controls instantiation.
- 2.7, same payload → `ComposerException: Global tag is not allowed: tag:yaml.org,2002:check.Canary`. No object built.
- 1.33 with `SafeConstructor` → `ConstructorException: could not determine a constructor for the tag` and the canary was **not** constructed.

## The fix, how to verify it, and the subtlety

**Fix (defence in depth):**
1. **Upgrade** to a 2.x line. Prefer **2.7** — it is the latest and it also closes the later Compact Object Notation bypass (issue 1115). Pin it with `<dependencyManagement>` so nothing pulls an old transitive copy.
2. For untrusted input, parse with **`SafeConstructor`**: `new Yaml(new SafeConstructor(new LoaderOptions()))`. In 2.x, `new Yaml()` already rejects global tags, but `SafeConstructor` is explicit and survives a future code change.
3. Set **`LoaderOptions`** limits for denial-of-service defence: `codePointLimit`, `nestingDepthLimit`, `maxAliasesForCollections`, `allowDuplicateKeys(false)`.

**A subtlety I found by testing (worth stating out loud).** In 1.x, `LoaderOptions` limits passed only to a `SafeConstructor` are **ignored** — you must also pass the same `LoaderOptions` to the `Yaml` object. In 2.x the `SafeConstructor(LoaderOptions)` path applies them. So the correct 2.x call is `new Yaml(new SafeConstructor(options))`. I confirmed that a 100-code-point limit then rejects an oversize document and that `allowDuplicateKeys(false)` rejects a duplicate key.

**Verify the fix:**
1. `mvn dependency:tree | grep snakeyaml` shows only 2.7 and no transitive 1.x.
2. A **regression test** that loads a global-tag payload and asserts it throws (`ComposerException` on the `new Yaml()` path, or the `SafeConstructor` rejection). This is the single most convincing proof. The canary pattern above is exactly that test.
3. Re-run the existing `PolicyLoaderTest.load_validYaml_returnsPolicies` so the upgrade did not break valid parsing.
4. Re-scan with the SCA tool and confirm the finding clears.

**Who to tell.** The team lead and product security (a PSIRT-style owner). Record the decision as a VEX statement (`affected` + `action_statement: upgrade to 2.7`). If logs suggest the flaw was exercised, tell the affected tenants and incident response. File a ticket so the fix is tracked, not just discussed.

## Recommendation and risks

**Recommend: upgrade to 2.7 and parse untrusted YAML with `SafeConstructor` plus `LoaderOptions`.**

Risks:
- **2.x is a breaking API change.** The only safe constructor is `SafeConstructor(LoaderOptions)`, so 1.x code that calls `new SafeConstructor()` stops compiling.
- **Behaviour changes.** Duplicate-key handling and some implicit typing differ. Re-test `PolicyLoader` against the real demo `policies.yaml`.
- The upgrade is a dependency bump that touches a security boundary, so it needs a test and a review, not a silent version change.

## Common mistakes

- Treating it as "just bump the version" without naming the trust boundary. The input being tenant-uploaded is the whole point.
- Saying "we have no gadget, so we are safe". The classpath changes, and that is not a VEX you can defend over time.
- Using `new Yaml(new SafeConstructor())` on 1.x and thinking the `LoaderOptions` limits applied. They did not (see the subtlety above).
- Confusing this with the SnakeYAML denial-of-service CVEs (billion-laughs, stack overflow). This one is remote code execution.
- Quoting one score. Give both the NVD 9.8 and the vendor 8.3, and say why they differ (the CNA assumes some privilege, `PR:L`).

**Senior signal:** states the trust boundary first and re-scores for our deployment instead of quoting the NVD number.
**Senior signal:** proves the fix with a regression test, not with a version string.
**Senior signal:** knows the real 2.x protection is the tag inspector, and that `SafeConstructor` is belt-and-braces, not the only lever.

## Follow-up questions the interviewer may ask

1. **"The scanner says Critical but you have no internet egress. Does that change your rating?"** It lowers the URL and JNDI payload risk, but not to zero. The in-process gadget paths do not need egress, and egress rules change. I would still upgrade, and I would record egress filtering as a compensating control, not a reason to skip the fix.
2. **"Can you mitigate without upgrading?"** Yes, partially: parse with `SafeConstructor` on 1.33, and add `LoaderOptions` limits passed to the `Yaml` object. That blocks the type instantiation. But 1.33 still carries the other DoS CVEs, so upgrading is better.
3. **"How would you prove to an auditor it is fixed?"** The `dependency:tree` output, the pinned version in `dependencyManagement`, the passing regression test that rejects a malicious payload, and a clean re-scan. A VEX record with the decision.
4. **"What else would you check in the same sweep?"** Any other place that parses untrusted input (JSON, XML), and whether any of them deserialize to types. The pattern, not the one library.
5. **"Why 2.7 and not 2.0?"** 2.0 fixes the reported CVE, but 2.7 also closes the Compact Object Notation bypass (issue 1115) and years of smaller fixes. Pick the latest patch of the fixed major line.

---

# Research B2 — Pick a rate-limiting library

**Prompt.** The hand-rolled `TokenBucketRateLimiter` keeps causing trouble (see ticket SYNC-205). Each target needs its own limit with bursts, and we may need a shared quota across several worker instances. Compare **Bucket4j** and **Resilience4j RateLimiter** (option C: Guava `RateLimiter`), plus the option to keep our own. Give a comparison table and a recommendation with risks.

## 5-minute spoken summary

- The requirement has three hard parts: a **blocking acquire with a timeout** (our `acquire(Duration)` contract), **one limiter per target key** with bursts, and maybe a **shared quota across worker instances**.
- **Bucket4j** is a real token-bucket library with first-class blocking-with-timeout, a per-key manager, and distributed backends (Redis, Hazelcast, JDBC and more). It is the only option that can share a quota across instances.
- **Resilience4j RateLimiter** is lighter and part of a resilience suite (retry, circuit breaker). But it is a **fixed-window** limiter, not token-bucket, it has **no distributed mode**, and its 3.x needs Java 21.
- **Guava RateLimiter** is a token bucket with a blocking timeout, but it is marked **@Beta**, has **no per-key manager** and **no distributed mode**, and drags in the 3 MB Guava jar.
- **Keep our own** is fine for a single instance, but it does not solve shared quotas and the SYNC-205 bug shows the maintenance cost of hand-rolled time math.
- I recommend **Bucket4j** because it is the only one that meets all three hard parts and fits our `Clock`-injected, testable style. The risk is a one-maintainer project and a larger jar.

## Comparison table (versions and dates checked on Maven Central, 2026-10-21)

| Criterion | Bucket4j | Resilience4j RateLimiter | Guava RateLimiter | Keep our own |
|---|---|---|---|---|
| Latest version (date) | **8.21.0** (2026-10-02) ([metadata](https://repo1.maven.org/maven2/com/bucket4j/bucket4j_jdk17-core/maven-metadata.xml)) | **2.4.0** (2026-03-14) ([metadata](https://repo1.maven.org/maven2/io/github/resilience4j/resilience4j-ratelimiter/maven-metadata.xml)) | **33.7.2-jre** (2026-09-29) ([metadata](https://repo1.maven.org/maven2/com/google/guava/guava/maven-metadata.xml)) | n/a |
| Maven coordinates | `com.bucket4j:bucket4j_jdk17-core` | `io.github.resilience4j:resilience4j-ratelimiter` (+ `-core`) | `com.google.guava:guava` | n/a |
| Algorithm | **Token bucket**, integer math, bursts supported ([docs](https://bucket4j.com/8.21.0/toc.html)) | **Fixed window**: `limitForPeriod` permits per `limitRefreshPeriod`, reset each cycle ([docs](https://resilience4j.readme.io/docs/ratelimiter)) | Token bucket (SmoothBursty), double math, smoothing/warmup ([javadoc](https://guava.dev/releases/33.7.2-jre/api/docs/com/google/common/util/concurrent/RateLimiter.html)) | Token bucket (per spec) |
| Blocking acquire with timeout | **Yes.** `bucket.asBlocking().tryConsume(1, Duration, BlockingStrategy)`, throws `InterruptedException` | **Yes.** `acquirePermission()` waits up to `timeoutDuration` (default 5 s), returns boolean. `reservePermission()` returns nanos | **Yes.** `tryAcquire(Duration timeout)` / `acquire()` | **Yes.** our own `acquire(Duration)` |
| One limiter per key (per target) | **Yes.** `ProxyManager` (distributed) or local Caffeine / map | **Yes.** `RateLimiterRegistry.rateLimiter("target")` | **No.** you hold a `Map<key, RateLimiter>` yourself | **No.** one instance per bucket, manual map |
| Shared quota across worker instances | **Yes.** Redis (Lettuce/Redisson/Jedis), Hazelcast, Ignite, Infinispan, Coherence, JDBC, MongoDB, Cassandra, JCache ([README](https://github.com/bucket4j/bucket4j)) | **No.** in-memory per instance only | **No.** in-process only | **No** |
| Metrics | `BucketListener` / `SimpleBucketListener`, Micrometer and Dropwizard examples | `getMetrics()` + `resilience4j-micrometer` (`resilience4j.ratelimiter.available.permissions`, `.waiting.threads`) | None (only `getRate()`) | None unless we add them |
| Minimum Java | **17** (jdk17 build, jdk8 line frozen at 8.10.1, 2024-03) | **17** for 2.x, **3.x needs Java 21** ([README](https://github.com/resilience4j/resilience4j)) | **8** (class major 52) | our target (17) |
| License | Apache-2.0 | Apache-2.0 | Apache-2.0 | n/a |
| Maintenance (2026-10-21) | 2.8k stars, 101 commits in the last year, 8.21.0 this month. **One main maintainer** (~808 of contributions) — bus-factor risk | 10.8k stars, 77 commits in the last year, broad contributors | 51.9k stars, Google, very active, but RateLimiter is **@Beta** since 13.0 and rarely changes | us — the SYNC-205 bug is the maintenance cost |
| Footprint | core jar ~447 KB, no required runtime deps | ratelimiter 45 KB + core 99 KB + slf4j | **~3 MB** guava jar | ~1 class |
| API stability | stable, documented backward-compat policy | stable | **@Beta** — Guava may change or remove it, discouraged for libraries | we own it |

## Recommendation and risks

**Recommend: Bucket4j (`bucket4j_jdk17-core` 8.21.0).**

Why it wins for this requirement:
- It is a **true token bucket with bursts**, which matches how AWS, Azure and GCP budgets behave (a burst allowance that refills).
- It has **first-class blocking-with-timeout**, so it drops straight into our `acquire(Duration)` contract.
- It gives **one limiter per key** through `ProxyManager`, so one limiter per target is natural.
- It is the **only** option that can later share a quota across worker instances (Redis, Hazelcast, JDBC). That is the requirement neither Resilience4j nor Guava can meet.
- It is small (447 KB), has no required runtime dependency, and supports a custom `TimeMeter`, which keeps our tests deterministic (we already inject `Clock` and `Sleeper`).

Risks and how to handle them:
- **Bus factor.** One main maintainer. Mitigate by pinning the version, keeping the limiter behind our own small interface, and being ready to vendor the token-bucket code if the project stalls.
- **Jar is bigger than Resilience4j's.** Acceptable at 447 KB. Guava's 3 MB is the one to avoid.
- **Java-version-specific artifacts.** Use the `jdk17` artifact to match our `maven.compiler.release=17`. Do not mix the jdk8 line.
- **Distributed mode adds a runtime dependency (Redis or similar).** Only take that on when the shared-quota requirement is real. Start local, keep the interface.

**When to pick each alternative instead.**
- **Resilience4j** if we are **already** adding it for retries and circuit breakers and we only need a per-instance limit. Its RateLimiter is a light add with good Micrometer metrics. Accept that it is a fixed window and cannot share a quota, and that 3.x will push us to Java 21.
- **Guava** only if Guava is **already** on the classpath and we accept the `@Beta` tag. Otherwise the 3 MB jar and the Beta status rule it out.
- **Keep our own** as a short-term bridge: it is the smallest and most testable, but it does not solve shared quotas and it has already cost us one bug (SYNC-205).

## Common mistakes

- Calling Resilience4j's limiter a token bucket. It is a fixed window that resets permits each cycle. That matters for burst behaviour.
- Reaching for Guava's RateLimiter in a library without noticing it is `@Beta`.
- Ignoring the shared-quota requirement. It is the single criterion that separates the three.
- Not checking the Java version. Resilience4j 3.x needs Java 21, which our project (release 17) does not target.
- Picking on stars alone. The one-maintainer risk in Bucket4j is a real operational fact to state.

**Senior signal:** separates the three hard requirements (blocking timeout, per-key, shared quota) and scores each option against them, instead of a feature list.
**Senior signal:** names the shared-quota requirement as the deciding criterion and says the others fail it.
**Senior signal:** calls out the Bucket4j bus-factor and the Guava @Beta status, and wraps the choice behind our own interface so we can swap it.

## Follow-up questions the interviewer may ask

1. **"We only run one instance today. Does your answer change?"** For today, keep-our-own or Resilience4j would do. But the design asks for shared quotas across instances, so I pick the option that does not need a rewrite later. I would still hide it behind our interface.
2. **"How do you test a rate limiter without real time?"** Inject the clock. Bucket4j takes a custom `TimeMeter`, Resilience4j 2.4 lets you supply a `nanoTime` supplier, and our own already injects `Clock` and `Sleeper`. The SYNC-205 test drains the bucket and advances a fake clock.
3. **"Bucket4j's maintainer stops. What then?"** The limiter sits behind our interface, so we vendor the token-bucket class (it is small) or switch to our own. Pinning the version means nothing breaks in the meantime.
4. **"Why not just fix our own?"** We can for one instance. But our own has no per-key manager and no distributed backend, and the hand-rolled time math already produced SYNC-205. A library gives tested math and a shared-quota path.
5. **"What metric would you watch for the limiter?"** Permits available per target, threads waiting, and the rate of acquire-timeouts. A rising timeout rate means the limit is too low or a target is throttling us.

---

# Design (Flow A) — From 5-minute polling to near-real-time sync at multi-tenant scale

**Prompt.** Move SecretSync from 5-minute polling to near-real-time. Scale: 3,000 tenants, up to 100k secrets each, up to 20 targets per tenant. A change must be visible in the targets within **60 s (p99)**. A hard guarantee: a source glitch can **never** mass-delete target secrets.

## Clarifying questions (ask these first)

1. What is the **freshness target** for the common case versus the worst case? Is 60 s p99 for a value change, and is a delete allowed to be slower?
2. What **change rate** do we expect — how often does a secret rotate? That sets the event volume.
3. Does every secret go to all 20 targets, or does a policy pick a subset? That sets the fan-out.
4. Do the source and the targets emit **change events**, or must we poll? AWS, Azure and GCP differ (Azure has no delete event).
5. What is the **cost of a wrong delete** — can a target secret be recovered? This drives the deletion safety design.
6. Is **strict ordering** per secret required, or is last-writer-by-version enough?
7. What is the **consistency** promise to tenants — eventual within 60 s, or stronger?

## Stated assumptions (write these where they can see them)

- 60 s p99 applies to a value create or update. A delete may take longer because we make it deliberately cautious.
- Average fan-out is **2 targets** per secret, not 20. 20 is the per-tenant ceiling.
- Secrets rotate on the order of **once per 30 days** on average, with bursts.
- The source can emit change events, and we also run a periodic full reconciliation as a backstop.
- Last-writer-by-source-version is acceptable. We do not need a total order, only per-secret order.

## Back-of-envelope numbers (show the arithmetic)

- **Total secrets.** 3,000 tenants × up to 100k = **up to 300,000,000** secrets at the ceiling. Use 300M as the worst case.
- **A day is 86,400 s.** 1,000,000 changes/day ≈ **12/s**. Keep that ratio handy.
- **Steady change rate.** 300M secrets ÷ (30 days × 86,400 s) = 300,000,000 ÷ 2,592,000 ≈ **116 changes/s** average.
- **Peak.** Assume 10× burst → **~1,160 source changes/s**.
- **Target writes.** Fan-out 2 → ~2,300 target-writes/s at peak. Round to **~2.5k writes/s**.
- **Why polling cannot meet 60 s.** To compare values by polling, you list and often GET each secret. AWS list is ≤100 per page at 100 TPS. A full scan of 300M is 300M ÷ 100 = 3,000,000 list calls ÷ 100 TPS = **30,000 s ≈ 8.3 hours** per full pass — and that is list only, before any GET. So a 60 s target rules out full polling. We need **events** for latency, plus a slow reconcile for correctness.
- **Per-target budget sanity.** A single AWS target allows 50 writes/s per op type. 2.5k writes/s across many targets is fine **only if** spread over targets. One hot target is the bottleneck, so we rate-limit per target and queue.

## API (the operations and events that matter)

- **Change event in** (from the source feed or the outbox): `{tenantId, secretId, sourceVersion, changeType: UPSERT|DELETE, occurredAt}`. **No secret value in the event.**
- **Enqueue(tenantId, secretId, sourceVersion, changeType)** — idempotent on `(secretId, sourceVersion)`.
- **ApplyToTarget(target, secretId, sourceVersion, changeType)** — the worker fetches the value from the source at apply time, writes to the target, records the result.
- **RunReconcile(tenantId, target)** — the periodic full compare that catches missed events and owns deletes.
- **Admin: SetDeletionGuard(target, maxFraction, maxCount)**, **SetDryRun(policy, bool)**, **KillSwitch(target|tenant)**.
- Who may call: the event ingester and the reconcile scheduler enqueue. Workers apply. Only an operator role sets the guard, dry-run or kill switch.

## Architecture (ASCII, main flow)

```
                         source change events (per provider feed)
  +-----------+   events    +-------------------+
  |  Source   | ----------> |  Event ingester   |  writes watermark (ts, id)
  |  Vault    |             |  + outbox relay   |  only AFTER apply succeeds
  +-----------+             +---------+---------+
        ^                             | enqueue {tenant, secretId, version, type}
        | GET value at apply time     v           (no value in the message)
        |                   +-------------------------------+
        |                   |  Per-tenant fair queue        |  group = tenantId
        |                   |  (shuffle sharding)           |  bounded per tenant
        |                   +----------------+--------------+
        |                                    | lease
        |                       +------------v-------------+
        |                       |  Worker pool             |  upserts first, then deletes
        +---------------------- |  per-target rate limiter |  retry 429 w/ backoff+jitter
                                |  idempotency by version  |  DELETE passes the guard
                                +------+-------------+------+
                                       |             | on repeated failure
                            apply      v             v
        +--------------+  +-----------------+   +-----------+
        | AWS / Azure  |<-| Target adapters |   |   DLQ     |  alarm on any message
        | / GCP target |  | name/size/delete|   +-----------+
        +--------------+  +-----------------+
                 ^
                 | slow full pass, owns deletes, catches missed events
          +------+-------------------+
          |  Reconcile scheduler     |  every N minutes per (tenant, target)
          +--------------------------+
```

State lives in three places. The **queue** holds pending work. The **watermark store** holds the last applied `(timestamp, id)` per tenant, advanced only after a successful apply. The **targets** hold the synced secrets. No component holds secret values at rest beyond the target.

## Deep dive 1 — change capture that is correct

- **Events for latency, reconcile for correctness.** Events give the 60 s p99. A periodic full reconcile (constant-work loop) is self-healing. It catches missed events, late events and deletes that no event covered (Azure emits no delete event).
- **Watermark.** Use a `(timestamp, incrementing id)` watermark, not timestamp alone. A timestamp-only watermark drops a row when two rows share a timestamp and the process restarts between them. Advance the watermark **only after** the batch is applied. This mirrors Secrets Hub: the sync timestamp does not advance until a run succeeds.
- **Eventual consistency.** Cloud lists lag (AWS up to 5 min). Overlap the poll window and never treat a just-missing secret as a delete until the reconcile confirms it twice.
- **Transactional outbox** if we own the source database. Write the change to an outbox table in the same transaction as the secret change, and let a relay publish it. The relay can publish duplicates, so consumers must be idempotent.

**Senior signal:** proposes events **and** a reconcile backstop, and explains the watermark is `(timestamp, id)` because timestamp alone loses rows on restart.

## Deep dive 2 — per-tenant fairness and target rate limits

- **One tenant must not starve the others.** A tenant with 100k secrets rotating at once cannot block a tenant with 10. Use per-tenant queue groups (SQS `MessageGroupId` = tenant, or Kafka partition by tenant) and **shuffle sharding** so one noisy tenant touches only a few shards.
- **Bounded concurrency per tenant.** Cap the in-flight work per tenant with a semaphore. Spill the excess to a backlog queue. This is fairness throttling.
- **Per-target rate limit.** Each target has its own budget and window (AWS 50 TPS per op type, Azure 300 creates / 10 s per vault, GCP 600 writes / min per project). Use **one limiter per (target, operation class)** — this is where Research B2 lands on Bucket4j. On a 429 or `ThrottlingException`, back off with jitter. Throttled calls still cost budget, so slow down.
- **Ordering per secret.** Order by source version. A lower version never overwrites a higher one. Different secrets interleave freely.

**Senior signal:** separates per-tenant fairness (shuffle sharding, bounded concurrency) from per-target rate limits (provider windows), and does not conflate the two.

## Deep dive 3 — deletion safety (the hard guarantee)

The prompt demands that a source glitch can never mass-delete. Two layers:

1. **Never delete from a failed or partial source.** The scan must **succeed and be complete** before any delete is planned. A source error must propagate, not return an empty list. (This is exactly ticket SYNC-203: a swallowed `VaultUnavailableException` returned `List.of()`, and the engine then deleted every managed secret.)
2. **Cap the blast radius per cycle.** A **deletion guard** blocks a cycle whose deletes exceed `maxDeleteFraction` or `maxDeleteCount` of the target's managed secrets. When it trips, skip all deletes, still apply creates and updates, set a distinct status, and raise an ERROR an operator can alert on. (This is the Design + implement B2 feature below.)
3. **Soft delete and grace.** Prefer a soft delete or a grace period over an immediate destroy, especially on GCP where destroy is irreversible. A **kill switch** halts deletes for a target or tenant on command.
4. **Dry-run.** A per-policy dry-run logs the plan and changes nothing. Use it to roll out and to debug a suspicious diff.

**Senior signal:** gives two independent layers (fix the fail-open **and** cap the blast radius) and says why one is not enough: the guard catches the case where the source genuinely returns fewer secrets, and the propagate-on-error catches the outage case.

## Failure modes

| Component | What breaks | Mitigation |
|---|---|---|
| Source feed | events stop or lag | reconcile backstop still syncs, alert on event age |
| Source vault | returns 503 mid-cycle | propagate the error, skip the cycle, **do not** delete, retry with backoff |
| Queue | backs up for one tenant | shuffle sharding and bounded per-tenant concurrency contain it, plus backpressure |
| Worker | crashes mid-apply | at-least-once redelivery, idempotent by source version, lease timeout re-queues |
| Target | throttles (429) | per-target limiter plus backoff with jitter, throttled calls still cost budget |
| Target | rejects a value (too big) | record failed, keep other targets, alert, do not abort the cycle |
| Watermark store | advanced too early | advance only after apply, on restart re-process from the last safe mark |
| Diff | source list stale or partial | require a complete scan, deletion guard caps deletes, reconcile confirms twice |
| Delete path | a glitch plans mass delete | guard blocks, status DELETES_BLOCKED, ERROR, operator decides |

## Security

- **No secret values in the queue, the events, the logs or the metrics.** Pass a reference `(secretId, sourceVersion)` and fetch the value at apply time. The value record masks its `toString()`.
- **Least privilege per target.** The worker's target credential may only manage secrets this product owns (tag-scoped, as Secrets Hub does with a `Sourced by CyberArk`-style tag). It must not read or delete secrets it did not create.
- **TLS in transit, mTLS between internal services.** Values are encrypted at rest in the target's own store.
- **Idempotency keys.** Use a deterministic token derived from the source version (AWS `ClientRequestToken`) so a retry cannot create a duplicate version.
- **Tenant isolation.** One tenant's load or bug must not reach another tenant's targets. Fairness and per-tenant credentials enforce this.
- **Audit.** Record who changed the guard, the dry-run flag or the kill switch, and when.

## Observability (named metrics and alerts)

Metrics:
- `sync_change_lag_seconds` — age of the oldest unapplied change, per tenant and per target (the p99 is the 60 s promise).
- `sync_queue_depth` — per tenant.
- `sync_target_write_errors_total` — per target, by error type (validation, throttle, not-found).
- `sync_throttle_total` — per target (429 / ThrottlingException / RESOURCE_EXHAUSTED).
- `sync_deletes_planned_total` and `sync_deletes_blocked_total` — per target.
- `sync_dlq_depth` — per queue.
- `sync_reconcile_duration_seconds` and `sync_reconcile_lag_seconds`.

Alerts (on symptoms, not on every error):
- `sync_change_lag_seconds{quantile=p99} > 60` for 5 min → we are missing the freshness promise.
- `sync_dlq_depth > 0` → a bug is dropping work.
- `sync_deletes_blocked_total` increases → the guard tripped, an operator must look.
- `sync_throttle_total` sustained → a limit is too low or a target is rate-limiting us.
- `sync_reconcile_lag_seconds` rising → the backstop is falling behind.

## Rollout and backward compatibility

- **Feature flag** the event path. Keep the 5-minute reconcile running as the backstop, so the new path is pure speedup, not a risk to correctness.
- **Dry-run first** on a canary tenant. Log the plan, change nothing, compare to what the old path would do.
- **Canary tenant, then ramp.** Watch the lag metric and the deletes-blocked metric.
- **Backward-compatible policy schema.** New fields (`dryRun`, guard thresholds) default to safe values, so old policies keep working.
- **Rollback plan.** Turn off the event flag and fall back to polling. The watermark makes this safe.

## Trade-offs chosen

- **Events plus reconcile**, not events alone. Costs a slow full pass, buys correctness and delete coverage.
- **Last-writer-by-version**, not a global order. Costs strict ordering across secrets, buys simplicity and throughput. Per-secret order is enough.
- **At-least-once plus idempotency**, not exactly-once. Exactly-once across systems is not achievable, so idempotent-and-ordered-per-key gives the same user-visible result.
- **Deletes deliberately cautious.** Costs some staleness when the guard trips, buys the hard no-mass-delete guarantee.

## Curveballs (new constraints the interviewer may add)

1. **"Azure emits no delete event."** Then events drive only upserts, and the **reconcile loop owns deletes**. Say this plainly: you cannot rely on a delete event that does not exist, so deletes come from a confirmed, complete full compare, under the guard.
2. **"One tenant uploads 100k secrets at once."** Fairness throttle and backlog queue absorb it. Bound the per-tenant concurrency so the burst does not blow the p99 for other tenants. The 100k tenant drains over minutes, the small tenants stay fast.
3. **"Make it exactly-once."** You cannot guarantee exactly-once across a queue and three cloud stores. Make each apply **idempotent** (deterministic token from the source version) and **ordered per secret** (version compare). At-least-once delivery plus an idempotent consumer is effectively once, and it survives retries and restarts.

**Senior signal:** when handed a curveball, adapts the existing design in one sentence and names the property that makes it safe, instead of redrawing the whole diagram.

---

# Design + implement B2 — Deletion guard

**Prompt.** A sync cycle must never delete more than `maxDeleteFraction` (e.g. 10%) or `maxDeleteCount` of a target's managed secrets in one cycle. When the guard trips: skip **all** deletes of that cycle, still apply creates and updates, set a distinct report status (e.g. `DELETES_BLOCKED`), and log an ERROR an operator can alert on. Add a per-policy `dryRun` flag that logs the plan and changes nothing. Design it first, then implement a thin slice with tests.

## Design

**Where the check lives.** Between the diff and the execute step in `SyncEngine.runCycle`. The flow today is: scan → `target.listManagedSecrets()` → `DiffCalculator.plan(...)` → `SyncExecutor.execute(...)`. The guard sits **after** `plan` and **before** `execute`. It looks at `plan.deletes()` and the count of managed secrets, and it either passes the plan through or strips the deletes and flags the cycle.

Put the logic in a small, pure component so it is easy to test:
- `DeletionGuard` with one method `GuardDecision decide(SyncPlan plan, int managedCount, DeletionGuardConfig config)`.
- `GuardDecision` carries the plan to run (deletes removed if blocked) and a boolean `blocked`.
- `SyncEngine` calls the guard, runs the returned plan, and sets the report status.

**Interaction with the SYNC-203 fix.** These are two layers, and both are needed.
- SYNC-203 is the fail-open bug: a swallowed `VaultUnavailableException` made `scan` return an empty list, so the engine deleted everything. The first-line fix is that `scan` **propagates** the error and the cycle aborts with no target writes.
- The guard is the **second** line. It caps the blast radius when the source genuinely returns fewer secrets — a partial page, eventual-consistency lag, or a real but large deletion. The guard would have reduced the SYNC-203 damage even with the bug present, but it does not replace the fix. Say both.

**Defaults.** Guard **on** by default. `maxDeleteFraction = 0.10`. `maxDeleteCount` a small floor, for example `10`, so a small target can still lose a few orphans without tripping. Dry-run **off** by default.

**The fraction-or-count rule (state your interpretation).** The prompt says "never delete more than `maxDeleteFraction` or `maxDeleteCount`". That phrase is ambiguous. I read it as: the cycle may delete up to **`allowed = max(maxDeleteCount, floor(maxDeleteFraction × managedCount))`**, and it trips when `plannedDeletes > allowed`. The fraction protects large targets (10% of 1,000 = 100). The count is a floor so small targets are not blocked on a single legitimate orphan (10% of 5 = 0, which would block everything). I chose `max`, not `min`, so the two knobs are complementary, not fighting. (See the Notes at the end — this is a spec ambiguity worth confirming with the interviewer.)

**How an operator overrides it.** Per target or per policy config: raise `maxDeleteCount`, raise `maxDeleteFraction`, or a one-shot, **time-boxed, audited** override for a known-large cleanup — the same pattern Microsoft Entra Connect uses, where an admin runs `Disable-ADSyncExportDeletionThreshold` once and is told to re-enable it ([Entra Connect: prevent accidental deletes](https://learn.microsoft.com/en-us/entra/identity/hybrid/connect/how-to-connect-sync-feature-prevent-accidental-deletes), whose default threshold is 500 deletes). Never a silent permanent off.

## Behaviour table

| Input situation | Expected behaviour |
|---|---|
| deletes within the allowance (`≤ allowed`) | apply creates, updates and deletes. status `SUCCESS` |
| deletes exceed the allowance (fraction and count both) | skip **all** deletes. apply creates and updates. status `DELETES_BLOCKED`. log ERROR |
| small target, 1 delete of 5 secrets (20% > 10% but `≤ maxDeleteCount` floor) | apply the delete. status `SUCCESS` (the count floor allows it) |
| `dryRun = true`, any plan | log the plan summary. change nothing in the target. counters stay 0 |
| guard overridden (threshold raised or disabled for this run) | deletes apply up to the new allowance |
| target has 0 managed secrets, plan has 0 deletes | nothing to guard. apply creates. status `SUCCESS` |
| source unavailable (scan throws) | cycle aborts before the guard. no target writes. status `FAILED` / aborted (SYNC-203 fix) |

## Config

- `SyncPolicy` gains a `dryRun` flag (default false). It stays a validated record.
- A `DeletionGuardConfig(double maxDeleteFraction, int maxDeleteCount, boolean enabled)`, per target or per policy, with safe defaults (0.10, 10, true).
- The guard reads the managed count from `target.listManagedSecrets().size()` — the same list the diff already used, so no extra call.

## Where the code goes (real classes from the spec)

- **`com.acme.secrets.sync.config.SyncPolicy`** — add `boolean dryRun`.
- **`com.acme.secrets.sync.engine.DeletionGuard`** (new) — the pure decision.
- **`com.acme.secrets.sync.engine.SyncReport`** — add status `DELETES_BLOCKED`. It already has `status()` returning `SUCCESS` / `PARTIAL_FAILURE` and an `aborted(...)` factory. Add a `deletesBlocked` flag and factor it into `status()`.
- **`com.acme.secrets.sync.engine.SyncEngine.runCycle`** — call the guard between `plan` and `execute`, honour `dryRun`, set the status, log.
- **`com.acme.secrets.sync.engine.SyncPlan`** — reuse `upserts()` and `deletes()`. Build the blocked plan from `upserts()` only.

## Implementation outline (Java — against the spec's names, an outline, not compiled)

```java
// com.acme.secrets.sync.engine.DeletionGuard
public record DeletionGuardConfig(double maxDeleteFraction, int maxDeleteCount, boolean enabled) {
    public DeletionGuardConfig {
        if (maxDeleteFraction < 0 || maxDeleteFraction > 1) throw new IllegalArgumentException("fraction 0..1");
        if (maxDeleteCount < 0) throw new IllegalArgumentException("count >= 0");
    }
    public static DeletionGuardConfig defaults() { return new DeletionGuardConfig(0.10, 10, true); }
}

public record GuardDecision(SyncPlan planToRun, boolean blocked, int plannedDeletes, int allowed) {}

public final class DeletionGuard {
    private static final Logger log = LoggerFactory.getLogger(DeletionGuard.class);

    public GuardDecision decide(SyncPlan plan, int managedCount, DeletionGuardConfig config, String policyName) {
        int plannedDeletes = plan.deletes().size();
        if (!config.enabled() || plannedDeletes == 0) {
            return new GuardDecision(plan, false, plannedDeletes, Integer.MAX_VALUE);
        }
        int allowed = Math.max(config.maxDeleteCount(),
                               (int) Math.floor(config.maxDeleteFraction() * managedCount));
        if (plannedDeletes <= allowed) {
            return new GuardDecision(plan, false, plannedDeletes, allowed);
        }
        // Blocked: keep upserts only, drop every delete this cycle.
        SyncPlan upsertsOnly = new SyncPlan(plan.upserts());
        log.error("Deletion guard blocked policy '{}': {} deletes exceed allowed {} "
                + "(managed={}, fraction={}, count={}). Skipping all deletes this cycle.",
                policyName, plannedDeletes, allowed, managedCount,
                config.maxDeleteFraction(), config.maxDeleteCount());
        return new GuardDecision(upsertsOnly, true, plannedDeletes, allowed);
    }
}
```

```java
// com.acme.secrets.sync.engine.SyncEngine.runCycle  (wiring sketch)
public SyncReport runCycle(SyncPolicy policy) {
    List<SecretRef> sources = scanner.scan(policy);          // propagates VaultUnavailableException (SYNC-203 fix)
    TargetStore target = targets.get(policy.targetId());
    List<TargetSecret> managed = target.listManagedSecrets();
    SyncPlan plan = diff.plan(policy, sources, managed);

    if (policy.dryRun()) {
        log.info("[dry-run] Plan for policy '{}': {} creates, {} updates, {} deletes",
                policy.name(), /* counts from plan */ ...);
        return SyncReport.dryRun(policy.name());             // changes nothing
    }

    GuardDecision decision = deletionGuard.decide(plan, managed.size(), guardConfigFor(policy), policy.name());

    SyncReport report = new SyncReport(policy.name());
    executor.execute(decision.planToRun(), target, report);  // upserts + (deletes only if not blocked)
    if (decision.blocked()) report.markDeletesBlocked();     // status() then returns DELETES_BLOCKED
    log.info("Sync cycle finished: {}", report);
    return report;
}
```

```java
// com.acme.secrets.sync.engine.SyncReport  (additions)
private volatile boolean deletesBlocked = false;
public void markDeletesBlocked() { this.deletesBlocked = true; }
public Status status() {
    if (aborted) return Status.FAILED;
    if (deletesBlocked) return Status.DELETES_BLOCKED;
    return failed.get() == 0 ? Status.SUCCESS : Status.PARTIAL_FAILURE;
}
// toString(): policy=payments-app status=DELETES_BLOCKED created=3 updated=1 deleted=0 failed=0
```

Notes on the outline:
- The guard is **pure** — no I/O, no clock — so it is trivial to unit-test.
- The blocked plan reuses `SyncPlan.upserts()`, so a create and an update still apply. Only deletes are deferred.
- `dryRun` returns before the guard and before `execute`, so nothing is written. The status for a dry-run can be a `DRY_RUN` status or `SUCCESS` with zero counters — confirm which the team wants.

## Tests to write (names in method_scenario_expected form)

1. `decide_deletesWithinAllowance_returnsPlanUnchanged` — 2 deletes of 100 managed, allowance 10 → not blocked, plan has the deletes.
2. `decide_deletesExceedFractionAndCount_blocksAllDeletes` — 60 deletes of 100 managed, fraction 0.10 count 10 → blocked, plan has 0 deletes, upserts kept.
3. `decide_smallTargetWithinCountFloor_appliesDelete` — 1 delete of 5 managed (20% > 10% but ≤ count floor 10) → not blocked.
4. `decide_blocked_keepsCreatesAndUpdates` — blocked cycle → `GuardDecision.planToRun()` contains every CREATE and UPDATE, no DELETE.
5. `runCycle_dryRun_changesNothingInTarget` — `dryRun=true`, a plan with creates and deletes → target operations log is empty, counters are 0.
6. `runCycle_deletesBlocked_reportStatusIsDeletesBlocked` — a cycle that trips the guard → `report.status()` is `DELETES_BLOCKED` and an ERROR was logged.

(Extra, if time: `decide_guardDisabled_appliesAllDeletes`, and `runCycle_sourceUnavailable_neverReachesGuard` to show the SYNC-203 fix aborts first.)

Use the existing test utilities: fakes for the vault and target, `MutableClock` and `FakeSleeper`, AssertJ, JUnit 5. The guard tests need no clock at all because the component is pure.

## Security trade-off discussion

- **The guard trades freshness for safety.** When it trips, orphaned target secrets **linger** until an operator acts. A lingering secret can be a security issue — a credential that should be revoked stays readable in the target. So the guard must **alert loudly** (ERROR plus the `sync_deletes_blocked_total` metric) and the override must be **time-boxed and audited**, never a silent permanent off.
- **Deletes are deferred, not updates.** A blocked cycle still applies creates and updates. So a secret whose **value** must change still changes. Only **removal** waits. That is the right bias: a stale extra secret is safer than a mass deletion with no undo, and on GCP the delete is irreversible anyway.
- **The guard is not a substitute for the fail-open fix.** If `scan` still returned empty on error, the guard would cap the damage but the engine would still try to delete from bad data. Fix the fail-open **and** keep the guard.
- **Dry-run is a safety tool.** Roll out any change to the delete path behind dry-run on a canary tenant first, read the planned deletes, then enable.

**Senior signal:** names the trade (lingering secret vs mass delete) and resolves it with a loud alert and a time-boxed, audited override, not a silent toggle.
**Senior signal:** keeps the guard component pure so it is testable, and wires time and I/O in the engine.
**Senior signal:** ties the guard to the SYNC-203 fix as a second layer and refuses to let it replace the first.

---

*End of model answers. The companion interviewer script is in `INTERVIEWER_RESEARCH_DESIGN.md`.*
