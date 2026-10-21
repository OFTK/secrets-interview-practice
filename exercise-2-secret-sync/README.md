# Exercise 2: secret sync service

You just joined the SecretSync team. SecretSync copies secrets from Acme Vault, the source of truth, into cloud secret stores, so cloud-native apps can read them with native APIs. It is similar in spirit to CyberArk Secrets Hub. A sync cycle runs per **sync policy** every few minutes: list the vault secrets that match the policy, compare them with what the target store holds, then create, update or delete secrets in the target. Tenant admins write the policies as YAML in the web console. In-memory fakes stand in for the vault and for an AWS-Secrets-Manager-like target. Six tickets landed this week: see `TICKETS.md`.

## How the debugging part works

The bugs are described in `TICKETS.md`, like Jira tickets. The code does not mention them. For each ticket:

1. Read the ticket in `TICKETS.md`.
2. See the bug happen: choose **Demo SYNC-20x** in IntelliJ's run dropdown (top right) and press Run, or run the failing test the ticket names.
3. Find the code that causes it. Use the debugger.
4. Fix it.
5. Run the demo or the test again, and check that the bug is gone.

Example: SYNC-201 says a customer with 250 secrets sees only 200 in the target. Run **Demo SYNC-201**: it prints how many secrets are missing. Find where the vault listing is paged and why the last page is skipped, fix it, and the test `SourceScannerTest` turns green.

## Run it

- **IntelliJ:** File > Open > this folder (`exercise-2-secret-sync`, not the repo root). Let it load Maven. If it asks for a JDK, choose Download JDK, version 17 or newer.
- **Tests:** run the **All tests** configuration. 5 test cases fail on purpose. They belong to the tickets.
- **Demos:** run **Demo SYNC-201** to **Demo SYNC-206**. Each one reproduces one ticket and prints what happened.
- **Terminal:** `./mvnw test` and `./mvnw -q compile exec:java -Dexec.args=SYNC-203`. On Windows PowerShell: `.\mvnw.cmd test`.

## Pick a mode

This folder holds more than two hours of material on purpose. A 2-hour session uses only part of it:

| Mode | Time | Plan |
|---|---|---|
| **Easy** | 60 min | Debug SYNC-201 and SYNC-202 (failing tests) and SYNC-203 (clear logs), 30 min. Research B2 as a comparison table only, 15 min. Design B2 spoken, no code, 15 min. |
| **Realistic 2 hours** (the reported order: debug, research, design) | 2 h | Orientation 10 min. Debug SYNC-203, SYNC-202, SYNC-201, then SYNC-206 if time is left, 40 min. Research B2, 20 min. Design B2 and implement a thin slice, 40 min. Wrap-up 10 min. |
| **Extra practice** (any time, no clock) | - | SYNC-204, SYNC-205 (hard), Research A, Research B, Design A. |

In the orientation, explain one sync cycle out loud: `SyncEngine.runCycle` > `SourceScanner` (paging and policy filter) > `DiffCalculator` (the plan) > `SyncExecutor` (workers, rate limiter) > target store.

## Code map

| Package | What is in it |
|---|---|
| `com.acme.secrets.sync.config` | `SyncPolicy`, `PolicyLoader` (reads the policy YAML), `GlobMatcher` (policy patterns) |
| `...sync.source` | `VaultClient` and `FakeVault` (paged listing, can go unavailable), `SecretRef`, `SecretValue`, `Page` |
| `...sync.target` | `TargetStore` and `FakeCloudSecretStore` (AWS-like rules: allowed name characters, 65,536-byte limit, optional throttling), `TargetNameMapper`, the exceptions |
| `...sync.engine` | `SyncEngine`, `SourceScanner`, `DiffCalculator`, `SyncPlan`, `SyncExecutor`, `TokenBucketRateLimiter`, `SyncReport` |
| `...sync.demo` | `SyncDemo`, one scenario per ticket |

**Contracts**
- A policy pattern must match the **whole** secret id. `*` matches any characters except `/`. `**` matches any characters including `/`. `?` matches one character except `/`. Every other character is literal.
- Target names may contain only `A-Z a-z 0-9 / _ + = . @ -`. `TargetNameMapper` replaces any other character with `_`.
- With `deleteOrphans: true`, a managed target secret whose source no longer exists is deleted.
- Secret values are read from the vault at apply time. They never sit in the plan or in logs.

## Research prompts

- **B2. Pick a rate-limiting library (20 min).** The hand-rolled `TokenBucketRateLimiter` keeps causing trouble, and each target needs its own limit with bursts. Compare **Bucket4j** and **Resilience4j RateLimiter**, plus Guava's `RateLimiter`. Criteria: algorithm, blocking versus non-blocking acquire with a timeout, one limiter per target, shared quotas across several worker instances, metrics, Java version, license, maintenance activity, footprint. Deliver a comparison table and a recommendation with its risks.
- **A. New targets: Azure Key Vault and GCP Secret Manager (25 min).** Compare their constraints with AWS Secrets Manager: naming rules, maximum value size, versioning, deletion semantics (Azure soft-delete and name reuse), throttling limits, tags or labels. List the 3-5 things that would break the current engine, with a recommendation for each.
- **B. Scanner alert: CVE-2022-1471 in snakeyaml 1.33 (20 min).** Tenant admins upload policy YAML, and `PolicyLoader` parses it with `new Yaml().load(...)`. Is it exploitable here? How bad is it? What is the fix, how do you verify the fix, and who do you tell?

## Design prompts

- **B2. Deletion guard (design 15 min, then implement 25 min).** A sync cycle must never delete more than `maxDeleteFraction` (for example 10%) or `maxDeleteCount` of a target's managed secrets. When the guard trips, skip all deletes of that cycle but still apply creates and updates, set a distinct report status (for example `DELETES_BLOCKED`), and log an ERROR an operator can alert on. Add a per-policy `dryRun` flag that logs the plan and changes nothing. Design it first: where the check lives, the defaults, how an operator overrides it. Then build a thin slice with tests.
- **A. From 5-minute polling to near-real-time sync at scale (35 min).** 3,000 tenants, up to 100,000 secrets each, up to 20 targets per tenant. Product wants a change visible in the targets within 60 seconds (p99), and a hard guarantee that a source glitch can never mass-delete target secrets. Cover change capture (events versus polling), queueing and per-tenant fairness, target rate limits, idempotency and ordering (versions), retries and dead-letter queues, deletion safety, secrets in transit (no values in queues), observability, high availability and rollout. Use `../cheatsheets/design-checklist.md`.

## Debugging rules

1. Pick tickets by severity. `TICKETS.md` lists them in that order.
2. Reproduce first, with the failing test or the demo.
3. Find the root cause with the debugger. Say your hypothesis before you test it.
4. Fix the cause, not the symptom. Check other callers of the code you change.
5. SYNC-203, SYNC-204 and SYNC-206 have no failing test. Reproduce them with the demo, then write the test yourself.
6. Explain each fix in two sentences: the cause, and why the fix is right.

Use AI like a colleague: narrow questions, say out loud what you ask, verify by running code. Do not paste the whole project and ask for the bugs.

## After the session

- Did you reproduce each bug before you fixed it? Did you fix causes, not symptoms? Did you add tests? Did you talk the whole time? Did you call out the security and data-loss impact?
- Solutions: `git checkout solutions`, then `git diff main solutions -- exercise-2-secret-sync`. On that branch this folder also has `SOLUTIONS.md` (every bug) and `MODEL_ANSWERS.md` (the research and design parts).

This project pins snakeyaml 1.33 on purpose, for Research B. Do not copy that dependency into real projects.
