# Exercise 1: interviewer script for the research and design parts

This script is for a practice partner who plays the two interviewers. It covers the five research and design prompts of Exercise 1. Keep `MODEL_ANSWERS.md` open in a second window. Never read it aloud.

## Before you start

### Roles

| Role | Does | Does not |
|---|---|---|
| Driver | Reads each prompt aloud. Answers questions with the facts in "Provide if asked". Keeps time. Gives hints from the hint ladder. Asks the follow-ups and the curveballs. | Teach, volunteer facts, or show the model answer. |
| Observer | Fills in the score table. Notes how long the candidate stays silent. Writes down every AI prompt and whether she checked the answer. Prepares 3 strengths and 3 improvements. | Interrupt. The observer speaks only when the driver asks "Anything to add?" at the end of a part. |

With one partner: play the driver and keep short notes. Ask the candidate to record her screen, so you can score after the session.

### Rules for both interviewers

- Answer a question only with facts from "Provide if asked". If the fact is not there, say: "Assume what you think is reasonable, and tell us your assumption."
- When she is silent, wait 60 seconds. Then ask: "What are you thinking right now?"
- Use a hint only when the hint ladder says so. Write down every hint and its level.
- A level 2 or level 3 hint caps the related score at 3 for that part. This keeps the practice score honest.
- Keep the time checks, even when she is close to a good answer. Real interviews stop on time.
- Score the reasoning, the sources and the trade-offs, not only the conclusion.

### Opening lines (driver, at the start of the session)

> "This part simulates a normal work task. You can use the internet and any AI tool. Please think out loud and tell us what you look up and why. Ask us anything, as you would ask a teammate. We will give you facts when you ask. We will not give you the answer."

### AI checklist (observer, every part)

- She says what she will ask the AI before she asks it.
- Her prompts are narrow and give context.
- She opens at least one official source to check an AI claim.
- She never pastes a token, a key or a password, not even a fake one from the repo.
- She says so when the AI is wrong.

### Score scale

| Score | Meaning |
|---|---|
| 1 | Not yet. Missing or wrong. |
| 2 | Partial. The right direction, with clear gaps. |
| 3 | Solid. What a good mid-level or senior hire delivers. |
| 4 | Strong senior. All of 3, plus the "Senior signal" points in the model answer, without prompting. |

### Two-hour schedules

| Block | Flow A | Flow B |
|---|---|---|
| Opening lines and repo tour | 0:00-0:05 | 0:00-0:05 |
| Debugging tickets | 0:05-0:50 | 0:05-0:45 |
| Research | 0:50-1:15, Research A (or Research B) | 0:45-1:05, Research B2 |
| Design | 1:15-1:50, Design | 1:05-1:45, Design + implement B2 |
| Debrief | 1:50-2:00 | 1:45-2:00 |

Flow B copies the shape of one 2025 CyberArk candidate report: debug a Java client-server system, research two tools, then design and implement a feature ([Glassdoor](https://www.glassdoor.co.in/Interview/CyberArk-Software-Engineer-Intern-Interview-Questions-EI_IE30042.0,8_KO9,33.htm)). If the exercise README gives a different timeline, follow the README.

Do this before the Flow B implement step: make sure SDK-102 and SDK-103 are fixed. Use her fixes from the debugging part, or give her the fixes. SDK-103 makes cache entries live 1,000 times too long, and SDK-102 retries a 404. Both make the stale-if-error tests fail for the wrong reason.

---

## 1. Research A: remove the static API key

### Hand it out (driver reads)

> "Today every application that uses our SDK stores an API key to log in. That key is a secret zero: a long-lived secret that someone must store somewhere. We want to remove it. Please research how a workload can authenticate to a secrets manager without a stored secret, on two platforms. One: Kubernetes pods, with projected service-account tokens. Two: AWS workloads, with an IAM role and a signed STS GetCallerIdentity request. Look at how CyberArk Conjur and HashiCorp Vault implement each one. In about 20 minutes, give us a 5-minute explanation with a sequence sketch. Tell us what `TokenProvider` would need, the top security risks, and what you would ship first."

### Time box: 20-25 minutes

| At | Driver says | Observer checks |
|---|---|---|
| 0:00 | The hand-out lines. | Does she restate the task and plan her search? |
| 0:10 | "Ten minutes in. About 8 minutes left for research." | Has she found both mechanisms? |
| 0:15 | "Three minutes, then we would like your explanation." | Does a sequence sketch exist? |
| 0:18 | "Please give us your 5-minute explanation now." | Does she stay within 5 minutes? |
| 0:23 | Ask 2 follow-ups. Stop at 0:25. | Short, direct answers? |

### Provide if asked

| Question | Answer (fictional facts for the exercise) |
|---|---|
| Where do our customers run? | Most run on EKS, Kubernetes 1.33 or later. Some run on ECS and EC2. |
| Is the server side ours? | Yes. Acme Secrets Manager is our SaaS. The server team builds what you specify. |
| How long does our access token live? | 15 minutes, as `FakeSecretsServer` issues it. |
| Can our SaaS reach each cluster's JWKS? | EKS clusters have public OIDC issuers. Some customers run private clusters. |
| Do we need Azure or GCP? | Not in this round. |
| Can we add a sidecar to every pod? | We prefer not to. Tell us why you would need one. |
| Is mTLS required? | No. TLS plus a bearer token is the current standard. |
| How many clusters does a big customer have? | Up to 40. |

### Hint ladder

| Level | When to use it | What to say |
|---|---|---|
| 1 | She is stuck for more than 3 minutes at the start, or searches without a plan. | "What does the platform already know about this pod that an attacker outside the pod does not?" |
| 2 | 10 minutes in, and no concrete mechanism yet. | "Look up Kubernetes projected service-account tokens, and the Vault AWS auth `iam` method." |
| 3 | 15 minutes in, and no flow yet. | "Open CyberArk's page 'JWT-based Kubernetes authentication' and Vault's 'Kubernetes auth method' page. Compare how each one verifies the token." |

### Follow-ups and curveballs

| Question | A good answer includes |
|---|---|
| "Why not mount the API key from a Kubernetes Secret?" | Still static and long-lived. Secrets are unencrypted in etcd by default. Anyone who can read Secrets in the namespace gets it. |
| "Offline JWT check or TokenReview?" | Offline scales and needs no route to the cluster API. Only TokenReview rejects tokens of deleted pods. Short token lifetimes reduce the gap. |
| "What does `TokenProvider` keep, and what moves out?" | It keeps the cache and the single-flight refresh. The login moves into an `Authenticator`. It re-reads the token file at each login. |
| Curveball: "A customer's clusters are private. Our SaaS cannot reach their JWKS." | Static public keys (Conjur `public-keys`), with a manual key-rotation risk and an alert on an unknown `kid`. Or verify inside the cluster with a local component. |
| Curveball: "Security asks: when we delete a pod, how fast does its identity stop working?" | Offline: until the JWT `exp`, plus the life of our access token. TokenReview: at the next login, from 60 s after the pod's deletion timestamp. |
| Curveball: "IAM cannot deny GetCallerIdentity. Is that a problem?" | No. It proves identity without granting any permission. Our server decides what the role ARN may read. Bind replay with a server-ID header. |

### Green flags

- She restates the task and plans the search before she types.
- She uses official docs (Kubernetes, CyberArk, Vault, AWS) and checks AI claims against them.
- She draws a sequence with the verification step.
- She names the audience and the token lifetime as the main controls.
- She says the token file must be read again at each login.
- She proposes a pluggable authenticator and keeps the API key working.
- She makes a clear "ship first" decision with a reason and a risk.

### Red flags

- "Just store the key in a Kubernetes Secret."
- She trusts the default automounted token.
- She cannot say how the server verifies a token.
- Vague answers such as "use OAuth" without a flow.
- She pastes a real-looking key or token into an AI tool.
- No recommendation, or a list of every platform without a shipping order.

### Score guide

| Dimension | 1 | 2 | 3 | 4 |
|---|---|---|---|---|
| Research quality / decision making | No concrete mechanism, or wrong ones. No recommendation. | Names Kubernetes tokens and AWS IAM, but cannot explain verification. Sources are AI output only. A pick without a reason. | Correct flows for both platforms from official docs. Three real risks. A clear first pick with a reason and a risk. | All of 3, plus the offline-versus-TokenReview trade-off, AWS replay binding, a migration plan, and an AI claim checked out loud. |
| Design depth / trade-offs / security | No SDK change, or a change that stores a new long-lived secret. | "Add an auth method" without structure, or the token file is cached forever. | An `Authenticator` interface. `TokenProvider` keeps the cache and the single-flight refresh. The file is re-read. Tokens are never logged. | All of 3. Also: `apiKey` still works. A rejected identity is handled apart from transient errors. The AWS SDK sits in an optional module. The test plan uses `FakeSecretsServer` and `MutableClock`. |

---

## 2. Research B: should `Secret.value()` be a String?

### Hand it out (driver reads)

> "Our `Secret` record returns its value as a String. A security reviewer says secrets must be char arrays, not Strings. Please research char arrays versus Strings for secrets in Java: memory dumps, immutability, what the JCA Reference Guide says, and what real SDKs actually do. Then recommend an API for our SDK. You have about 20 minutes, including a short explanation at the end."

### Time box: 20 minutes

| At | Driver says | Observer checks |
|---|---|---|
| 0:00 | The hand-out lines. | Does she define the threat (heap dumps, core dumps, swap)? |
| 0:08 | "About 5 minutes left for research." | Has she found the JCA guide and one real SDK? |
| 0:12 | "Please start your recommendation." | Does she propose an API, not only an opinion? |
| 0:13 | Let her explain for up to 5 minutes. | Does she state the limits honestly? |
| 0:18 | Ask 1 or 2 follow-ups. Stop at 0:20. | |

### Provide if asked

| Question | Answer (fictional facts for the exercise) |
|---|---|
| Who calls `value()`? | JDBC data sources, HTTP `Authorization` headers and Kafka client properties. All of them take Strings. |
| Can we break the API? | Only in a major version (3.0). Every 2.x release must stay source compatible. |
| Do customers take heap dumps? | Yes. Many run with `-XX:+HeapDumpOnOutOfMemoryError`, and some attach dumps to support tickets. |
| Can the server change? | Yes, in 3.0. |
| How many customers? | About 300 companies use the SDK. |

### Hint ladder

| Level | When to use it | What to say |
|---|---|---|
| 1 | 5 minutes in, and she debates without a threat model. | "What would an attacker with a heap dump of the application see?" |
| 2 | 9 minutes in, and no primary source yet. | "Check what the JCA Reference Guide says about passwords, and what one real cloud SDK returns." |
| 3 | 12 minutes in, and no API proposal. | "Look at the `conjur-api-java` 3.4.0 release. What did it add, and what did it deprecate?" |

### Follow-ups and curveballs

| Question | A good answer includes |
|---|---|
| "Does char[] guarantee the secret leaves memory?" | No. Zeroing shortens the exposure window. HTTP, JSON, the cache and JDBC still make copies. |
| "How do you migrate without breaking 300 customers?" | Add a new type and method in 2.4, deprecate the String method, switch in 3.0. |
| "Why not a record for the new type?" | A record exposes the array through its accessor and compares arrays by identity. |
| Curveball: "Our biggest customer's DBA says the JDBC driver needs a String anyway." | Offer an explicit `asString()` escape hatch. Document that the String cannot be wiped. Keep the rest of the path wipeable. |
| Curveball: "Security wants proof that the SDK wipes secrets." | A test that `close()` zeroes the array, and that the cache wipes values on eviction. Be honest that the JVM can still hold copies. |

### Green flags

- She defines the threat before she argues for a type.
- She quotes the JCA guide and checks a real SDK (AWS, Azure, GCP or Conjur).
- She sees that a char[] API needs bytes from HTTP and zeroing in the cache.
- She proposes a non-breaking path for 2.x.
- She names logging and `toString()` (SDK-101) as the bigger leak.

### Red flags

- "char[] is always better" with no limits.
- She changes `value()` to `char[]` in a minor release.
- She forgets that the cache holds the value for the whole TTL.
- She returns the internal array to callers.

### Score guide

| Dimension | 1 | 2 | 3 | 4 |
|---|---|---|---|---|
| Research quality / decision making | "Use char[]" or "String is fine", with no reasoning. | Quotes the JCA guide, but ignores the copies in HTTP, JSON and the cache, or gives no real SDK example. | JCA guide, the copy problem, two real SDKs, and a recommendation that keeps 2.x compatible. | All of 3, plus `conjur-api-java` 3.4.0 as evidence, honest limits, and logs and heap dumps ranked as the bigger risks. |
| Design depth / trade-offs / security | A breaking change in a minor release. | A new type, but no rules for who zeroes it and when. | `SecretValue` with `close()`, a masked `toString()`, copies for callers, and a deprecation path. | All of 3, plus the record problem, cache zeroing on eviction, bytes from HTTP, and a 3.0 server change. |

---

## 3. Research B2: pick a cache library

### Hand it out (driver reads)

> "We want to replace our `SecretCache`. The SDK must keep serving secrets when Secrets Manager is briefly unreachable, and it should refresh hot secrets before they expire. Please compare Caffeine, Guava Cache, and a third option: keep our own. First, compare refresh-ahead semantics with `refreshAfterWrite`, and what happens to the old value when a refresh fails. Then compare expiry options, size-based eviction, async loading, and testability with a custom ticker or clock. Last, compare Java version support, license, maintenance activity and dependency footprint. In about 20 minutes, give us a comparison table and a recommendation with its risks."

### Time box: 20 minutes

| At | Driver says | Observer checks |
|---|---|---|
| 0:00 | The hand-out lines. | Does she check versions on Maven Central or the project pages? |
| 0:08 | "About 6 minutes left. Please start your table if you have not." | Does she verify refresh-failure behaviour in the Javadoc or with a spike? |
| 0:14 | "Please walk us through your table and your pick." | Is there one clear pick with risks? |
| 0:19 | Ask 1 follow-up. Stop at 0:20. | |

### Provide if asked

| Question | Answer (fictional facts for the exercise) |
|---|---|
| What does `SecretCache` do today? | A `ConcurrentHashMap` with one fixed TTL. No size limit. A small class. |
| How many secrets does a client hold? | 30 on average. The largest client we know holds 5,000. |
| Java baseline? | 17. |
| What is on customers' classpaths? | Many have Guava in different versions. Some have Caffeine 2.x. |
| License policy? | Apache-2.0 is fine. |
| Can we shade a dependency? | Yes. The build supports `maven-shade-plugin`. |
| Must 401, 403 and 404 stop a secret at once? | Yes. A revoked or deleted secret must stop working. |

### Hint ladder

| Level | When to use it | What to say |
|---|---|---|
| 1 | 6 minutes in, and the table has only generic rows ("fast", "popular"). | "What does each library do with the old value when a refresh fails?" |
| 2 | 10 minutes in, and she trusts blog posts or AI output for that row. | "A 20-line test with a fake ticker can answer that faster than reading." |
| 3 | 13 minutes in, and no pick yet. | "Read the first paragraph of Guava's `CacheBuilder` Javadoc." |

### Follow-ups and curveballs

| Question | A good answer includes |
|---|---|
| "Which option handles 'never serve stale after a 404' for us?" | None out of the box. Caffeine can drop the entry if `reload` returns null. Guava cannot. The rule is our code in every option. |
| "Why not Caffeine's `refreshAfterWrite` as is?" | Refresh failures are swallowed. A read after the TTL can get the cached value while the server is healthy. Returning the old value resets the expiry timer. |
| "What does a new dependency cost an SDK?" | It lands in every customer app. Version conflicts and CVE scans follow. Shading avoids the conflicts, but we must release to get fixes. |
| Curveball: "A customer has Caffeine 2.9 on the classpath and gets `NoSuchMethodError`." | Dependency resolution picked the older version. Shade and relocate Caffeine, or keep our own cache. |
| Curveball: "Management wants the decision today, with what would change it later." | A pick plus triggers: async API, thousands of keys per client, or measured refresh latency. |

### Green flags

- She checks the latest versions and dates on Maven Central or the release pages.
- She verifies refresh-failure behaviour in the Javadoc or with a small spike.
- She spots the Guava Javadoc note "Prefer Caffeine over Guava's caching API".
- She weighs the dependency cost for an embedded SDK and mentions shading.
- She says which rules stay custom code in every option.

### Red flags

- She picks the most popular library with no criteria.
- She copies an AI table without checking two cells.
- She claims Guava refreshes in the background by default.
- She ignores what happens to 401, 403 and 404.
- No recommendation, or "it depends" without saying on what.

### Score guide

| Dimension | 1 | 2 | 3 | 4 |
|---|---|---|---|---|
| Research quality / decision making | A pick by popularity. No table. | A table from AI output with unchecked or wrong cells. | A mostly correct, sourced table. One clear pick with two risks. | All of 3. Also: a spike or an exact doc quote on refresh failures. The Guava "Prefer Caffeine" note. Triggers that would change the pick. |
| Design depth / trade-offs / security | Ignores the 401, 403 and 404 rules. | Mentions stale-if-error, but assumes the library handles it. | Says the fail-closed rules need our code. Mentions the executor and the dependency cost. | All of 3, plus the timer-reset pitfall, the null-on-delete difference, shading, and a size bound. |

---

## 4. Design (Flow A): rotation-aware secret delivery at scale

### Hand it out (driver reads)

> "A large customer runs 20,000 pods in 40 Kubernetes clusters. Each pod reads about 30 secrets at startup. So a rolling deploy sends about 600,000 requests in a few minutes, and our API throttles them. They also rotate database passwords daily. After each rotation, their apps get authentication errors for up to the cache TTL. Please design the next version of secret delivery. You can use ideas such as a node-level agent or a sidecar cache, change notifications, versions or ETags, and rotation with overlapping credentials. The choice is yours. Cover the API, the data flow, consistency, failure modes, the security of any local cache, observability, and rollout with backward compatibility. For failure modes, tell us this too: if Secrets Manager is down, do we serve stale data, and for how long? You have about 35 minutes."

### Time box: 30-40 minutes

| At | Driver says | Observer checks |
|---|---|---|
| 0:00 | The hand-out lines. | Does she ask questions before she draws? |
| 0:05 | Nothing. | Has she stated assumptions and done the arithmetic? |
| 0:12 | "About 25 minutes left." | Is the main flow on screen? |
| 0:20 | "15 minutes left. We would like to hear about failure modes and security before the end." | Is rotation solved at the root, or only with a shorter TTL? |
| 0:25 | Ask one curveball. | Does she adapt the design, or start over? |
| 0:32 | "3 minutes left. Please summarise your trade-offs." | Does she name what she gave up? |
| 0:35 | Stop. Use 0:35-0:40 for follow-ups if the schedule allows. | |

### Provide if asked

All numbers are fictional facts for the exercise.

| Question | Answer |
|---|---|
| Pods per cluster, pods per node? | About 500 per cluster, about 30 per node. |
| How many services and distinct secrets? | About 200 services and 6,000 distinct secrets. A cluster uses about 1,500 of them. |
| Secret size? | 1 KB on average, at most 64 KB. |
| How fast do rollouts run? | A full rollout takes about 5 minutes. Deployments use the default `maxSurge` of 25%. Platform-wide changes roll many deployments at once. |
| What is our API rate limit? | 5,000 requests per second per tenant. |
| Do apps re-read their secrets after startup? | Yes. Most apps read again when the cache TTL ends, for example when they open new DB connections. |
| How many secrets rotate? | About 600 DB credentials a day. |
| Current settings? | Cache TTL 5 minutes. Access token lifetime 15 minutes. |
| Freshness target? | Every pod sees a new version within 1 minute (p99). |
| Outage targets? | Running pods keep working for 1 hour. New pods can start during a 15-minute outage. |
| Revocation target? | Revoked access stops within 5 minutes while Secrets Manager is reachable. |
| Audit? | Every read is audited: workload, secret, version, time. |
| Can we ship components to the customer? | Yes, one Helm chart per cluster. |
| Who uses the SDK? | 80% Java SDK 2.3. 20% Go or Python services that call the REST API. |
| Can the database have two users? | Yes, for the rotation of DB credentials. |

### Hint ladder

| Level | When to use it | What to say |
|---|---|---|
| 1 | 5 minutes in, with no questions and no numbers. | "Before the boxes: how many requests per second are we talking about?" |
| 2 | 15 minutes in, and only one of the two problems is addressed. | "There are two problems in the prompt. Which one have we not solved yet?" Or, if throttling is open: "How many requests would reach the server if each pod asked for its 30 secrets in one call?" |
| 3 | 25 minutes in, and rotation still causes errors. | "How does AWS rotate with alternating users, or CyberArk with dual accounts? How do they avoid a blackout window?" |

### Follow-ups and curveballs

| Question | A good answer includes |
|---|---|
| "Why not just lower the TTL?" | It raises the load (2,000 req/s already at 5 minutes) and still leaves an error window. Overlapping credentials remove the window. |
| "How does the shared cache know who may read what?" | It enforces the same policy as the central API, replicated per cluster. It denies when the policy is unknown or too old. |
| "Are cache hits audited?" | Yes. The cache aggregates, buffers and forwards audit events, and alerts before it drops one. |
| "When is it safe to retire the old password?" | After every edge acknowledges the new version and the grace period passes. Grace ≥ propagation + revalidation interval + `maxStale` + margin. |
| Curveball: "Revocation must work within 60 seconds, even during an outage." | Bounds of 60 s for those secrets, fail closed after 60 s without the change feed, and the availability cost said out loud. |
| Curveball: "The customer also runs 5,000 VMs and Lambda functions." | The same edge as a regional service. A Lambda extension on localhost with a token header. AWS IAM identity. |
| Curveball: "Now it is 200,000 pods." | Upstream load grows with clusters and changes, not pods. New limits: change-feed fan-out and audit volume. |
| Curveball: "Secrets Manager is down for 2 hours, during a rotation." | Edges serve within their bound. Retirement of the old credential waits. Alerts fire. New pods start from the edge. |

### Green flags

- She asks 3-5 sharp questions and writes her assumptions down.
- She does the arithmetic and sees that steady polling costs as much as a rollout.
- She separates the two problems: the startup storm and the rotation errors.
- A batch call as the cheapest first step.
- A shared cache (edge or agent) that checks authorization and audits hits.
- Overlapping credentials and a safe retirement rule.
- Stale bounds per layer, and who chooses them.
- Named metrics and alerts. A rollout plan that keeps 2.3 clients working.
- She ends with the trade-offs she chose.

### Red flags

- A big diagram before any question.
- "Raise the rate limit" or "lower the TTL" as the main fix.
- A cache that any pod can read.
- Secret values in events, logs or metrics.
- Secrets on disk without encryption.
- No answer to "for how long do we serve stale?"
- Kafka or Redis named without the property she needs.
- Security only when we ask.

### Score guide

| Dimension | 1 | 2 | 3 | 4 |
|---|---|---|---|---|
| Research quality / decision making | No questions and no numbers. She jumps to boxes. | Some questions. Numbers missing or wrong by 10 times. Only one problem solved. | Clarifies, does the arithmetic, and solves both problems with clear choices. | All of 3, plus a trade-off table, a clear first step (the batch call) with a reason, and what would change her mind. |
| Design depth / trade-offs / security | A cache without authorization. No failure modes. Security as an afterthought. | An edge or agent, but no stale bound, no rotation fix beyond the TTL, and thin security. | An edge or agent with authorization, stale bounds, overlapping credentials, a failure-mode table and named metrics. | All of 3, plus gated retirement with a grace formula, audit for cache hits, events without values, and backward compatibility through the v1 API. |

---

## 5. Design + implement B2: stale-if-error

### Hand it out (driver reads)

> "Customers ask us to keep serving secrets when Secrets Manager is briefly unreachable. Here is the feature. A transient error means HTTP 5xx, HTTP 429 or an I/O error. When a fetch fails with a transient error, and the cache holds a value that expired at most `maxStale` ago, return the stale value and log a WARN. `maxStale` is a new config, for example 15 minutes. Never serve stale after a 401, 403 or 404, because revoked access or a deleted secret must stop working. Count the stale serves. First design it: a behaviour table, the config and the security trade-off. Then implement a thin slice in `SecretCache` and `SecretsClient`, with tests that use `MutableClock` and `FakeSecretsServer.failNextSecretRequests`. You have about 40 minutes. Please spend about 15 of them on the design."

### Time box: 35-40 minutes

| At | Driver says | Observer checks |
|---|---|---|
| 0:00 | The hand-out lines. | Does she read `SecretCache`, `ApiClient` and the exception types first? |
| 0:10 | "We would like to see your behaviour table now." | Does the table have the 404, 401 and 403 rows and the boundary? |
| 0:15 | "Please start the implementation." | Does she write a failing test first, or code first? |
| 0:25 | "15 minutes left. Is a first test running?" | Does she run the tests often? |
| 0:30 | Ask one curveball. | |
| 0:35 | "5 minutes left. Please make sure what you have runs, and tell us your next step." | Does she leave a working, tested slice? |
| 0:40 | Stop. | |

### Provide if asked

| Question | Answer |
|---|---|
| Which `maxStale` value? | 15 minutes in the tests. Choose the default yourself. |
| Which errors are transient? | HTTP 5xx, HTTP 429 and I/O errors, as the prompt says. |
| How do I make the fake server fail? | `failNextSecretRequests(count, status)` makes the next `count` secret requests answer `status`. |
| Why does one injected 503 not fail my call? | The SDK retries. With the default `maxRetries` of 3, a call makes 4 attempts. |
| How do I fake a 401? | Inject two. The first 401 makes the SDK refresh its token once and retry. |
| How do I move time? | `MutableClock.advance(Duration)`. The fake server shares the clock. `RecordingSleeper` means retries do not really sleep. |
| May I add a new exception class? | Yes. |
| May I change the `SecretCache` constructor? | Yes. Keep the old one working. |
| May I add a dependency? | No. |
| Is a getter enough for the counter? | Yes. |
| What is the rotation grace period for DB passwords? | 30 minutes. |

### Hint ladder

| Level | When to use it | What to say |
|---|---|---|
| 1 | 8 minutes in, and the table treats all errors the same. | "Which failures prove the secret must stop working, and which only prove the server is unreachable?" |
| 2 | She starts coding the stale path, and the cache still removes expired entries. | "Look at `SecretCache.get`. What happens to an expired entry today?" |
| 3 | 25 minutes in, and she cannot tell a 503 from a 404 in `SecretsClient`. | "Look at what `ApiClient` throws for a 404 and for a 503. Can a catch block tell them apart?" |

### Follow-ups and curveballs

| Question | A good answer includes |
|---|---|
| "What happens to a later 503 after a 404?" | The 404 must evict the entry. Otherwise the next 503 serves the deleted secret. |
| "Why is the default zero?" | No behaviour change in a minor release. Customers opt in. |
| "How long does a stale serve take during an outage?" | Each call pays the full retry budget first. Fix it with a short per-id backoff window that serves stale at once. |
| Curveball: "Product says 1.4 seconds per call during an outage is too slow." | Serve stale at once during a per-id backoff window after a failure. Keep one background retry. |
| Curveball: "Security wants a cap per secret, set by admins." | The server sends a per-secret ceiling, such as `Cache-Control: stale-if-error`. The client uses the stricter value. |
| Curveball: "The WARN floods the log during a 30-minute outage." | Log the first stale serve per id, then once a minute with a count. The counter keeps the total. |

### Green flags

- She writes the behaviour table before any code, with the 401, 403 and 404 rows.
- She classifies errors by type, with an explicit allow-list.
- She changes `SecretCache.get` so it keeps expired entries inside the stale window.
- She evicts on 404, 401 and 403.
- She injects enough failures to get past the retries, and she knows why.
- The WARN line logs the id and the version, never the value.
- She runs the tests often and leaves a green slice.

### Red flags

- She codes without a design.
- A catch-all on `SecretsClientException` that serves stale.
- She reuses `RetryPolicy.isRetryable` without a test for 404.
- No test for the denial cases.
- The WARN logs the whole `Secret` or its value.
- She pastes the whole repo into an AI tool and asks for the feature.

### Score guide

| Dimension | 1 | 2 | 3 | 4 |
|---|---|---|---|---|
| Research quality / decision making | She starts coding without a behaviour table. | The table misses the 404, 401 and 403 rows, or the boundary. | A complete table, a default and validation for the config, and a clear security position. | All of 3, plus eviction on 404, an explicit allow-list, the outage latency with a fix, and a cap that the server owns. |
| Design depth / trade-offs / security | The code does not compile, or there are no tests. | Stale serving works, but it also serves after a 404, or no test covers the denials. | A working thin slice. 4-6 tests pass, including a 404 or 403 test. The WARN never logs the value. | All of 3, plus the 404-then-503 test, a conditional remove, an exclusive bound, and default-off compatibility. |

---

## Debrief (5-10 minutes, at the end of the session)

1. Driver: "If you had one more hour, what would you do next?"
2. Driver: "What would you change in your answers now?"
3. Observer: give the two scores for each part, with one sentence of evidence for each score.
4. Observer: give 3 strengths and 3 improvements. Use the "Senior signal" lines in `MODEL_ANSWERS.md` as the source for improvements.
5. Candidate: read the model answer for each part and mark what you missed.
