# Exercise 2 (secret-sync) — interviewer script: research and design

This is for a practice partner playing the two interviewers of a CyberArk-style exercise. One of you is the **driver** (hands out the task, keeps time, gives hints). The other is the **observer** (takes notes, scores, asks a follow-up near the end). Swap roles between prompts if you like.

The model answers are in `MODEL_ANSWERS.md`. Read the matching section before you run a prompt, so you can judge the candidate's answer against it.

**How to run it.** Hand out one prompt. Start the timer. Let the candidate talk. Stay quiet for the first few minutes — silence is data. Give a hint only when the candidate is stuck or off the rails, and use the lowest hint that unblocks them. Near the end, the observer asks one follow-up. Then score the two axes.

**The two score axes (used on every prompt).**
- **Research quality / decision making** — do they find the right facts, check them in a primary source, and turn them into a clear decision with risks?
- **Design depth / trade-offs / security** — do they go deep on the hard part, pick trade-offs out loud, name failure modes, and treat security as a first requirement?

**The 1-4 scale (same shape on both axes).**
- **1 — below bar.** Vague, wrong, or copied an AI answer without checking. No trade-off. Missed the security angle.
- **2 — mid, with gaps.** Correct in places. Needed hints. Stated few trade-offs. Covered security only when asked.
- **3 — solid senior.** Correct, checked a primary source, made a clear decision with risks, named a failure mode unprompted.
- **4 — strong senior.** All of 3, plus stated the trust boundary or the deciding criterion first, adapted cleanly to a curveball, and verified a claim live (ran it, or opened the doc and read the number).

A note on AI and the internet. Both are allowed. Reward a candidate who uses AI for a first pass **and then confirms the key number in the official doc out loud**. Treat "the AI said so" with no check as a red flag.

---

# Research A — Azure Key Vault and GCP Secret Manager as new targets

### Lines to say when you hand it out

> "SecretSync syncs to an AWS-Secrets-Manager-like store today. Product wants to add Azure Key Vault and GCP Secret Manager as targets. Spend about twenty minutes. Research their constraints versus AWS — naming, value size, versioning, deletion, throttling, tags. Then tell me the three-to-five things that would break our current engine, and what you'd do about each. The internet and AI are open. Talk me through what you're reading."

### Time box and time checks

- Total **20-25 min**.
- **5 min:** should be reading docs and naming the dimensions (name, size, delete, throttle). If still thinking out loud with no doc open, nudge.
- **12 min:** should be comparing, not still gathering. Ask "which of these actually breaks our code?"
- **18 min:** should be on recommendations. Ask for the single riskiest one.

### What to provide if asked

- The engine hard-codes AWS rules: `TargetNameMapper` maps to `[A-Za-z0-9/_+=.@-]`, `FakeCloudSecretStore` rejects values over 65,536 bytes, the diff deletes orphans, one global rate limiter.
- If they ask for a freshness or scale number, say "not needed here — this is about per-store constraints."
- If they ask "can I assume Azure allows underscores?" say "check the doc." (It does not.)

### Hint ladder

1. **Gentle (they are listing facts but not connecting to our code):** "Which of these differences would make our current code misbehave, not just differ?"
2. **Firmer (they miss a whole category):** "Think about what happens on a delete, and what happens to a 40 KB secret."
3. **Direct (stuck):** "Our name mapper produces AWS names. Azure forbids slash and underscore and is case-insensitive. What breaks?"

### Follow-up and curveball questions

- "A secret is 40 KB. It syncs to AWS but not Azure. What do you do?" (Good: fail that one write, keep AWS, alert, no truncation.)
- "On Azure a rename leaves the old name soft-deleted and the new cycle wants it back." (Good: 409, recover or purge, extra permission.)
- Curveball: "Detect a value change on Azure without downloading every secret." (Good: you can't from the list — it has no value. Compare source versions, store last-synced version in a tag.)
- Curveball: "Which store do you add first?" (Good: AWS/GCP parity first, Azure last, as a risk-ordering.)

### Green flags

- Opens the real docs and reads the number out (value size, delete model).
- Proposes a **target adapter** interface instead of scattered `if (azure)` branches.
- Says deletes are the dangerous direction and the three stores punish a wrong delete differently.
- Notices Azure case-insensitivity causing collisions, and Azure list lacking the value.

### Red flags

- Quotes AWS limits as universal.
- Treats delete as "has a recovery window everywhere" (GCP does not).
- Never checks a primary source — all from memory or an unverified AI answer.
- Lists facts but never says what breaks in our code.

### Score guide

- **Research quality / decision making:** 4 = checked each store's doc, built the comparison, picked a per-store recommendation with the riskiest one named. 3 = correct comparison, light on verification. 2 = mostly from memory, some wrong limits. 1 = vague or AWS-only.
- **Design depth / trade-offs / security:** 4 = adapter design, deletes-are-dangerous, the Azure value-change cost named. 3 = the 3-5 breaks with fixes. 2 = found breaks only with hints. 1 = no link to the engine.

---

# Research B — CVE-2022-1471 in snakeyaml 1.33

### Lines to say when you hand it out

> "A scanner flagged CVE-2022-1471 in snakeyaml 1.33. Here's the relevant code: `PolicyLoader` parses tenant-uploaded policy YAML with `new Yaml().load(...)`. Spend about twenty minutes. Is it exploitable **here**? How bad is it? What's the fix, how do you verify it, and who do you tell? Internet and AI are open."

### Time box and time checks

- Total **20 min**.
- **4 min:** should know it is unsafe deserialization and have opened NVD or the GHSA.
- **10 min:** should be judging exploitability **for our code**, not just reading the CVE. Ask "is it exploitable in our case?"
- **16 min:** should be on the fix, verify and who-to-tell.

### What to provide if asked

- "Tenant admins upload the policy YAML in the web console." (This is the key fact. Give it if they ask who controls the input.)
- "`new Yaml()` uses the default `Constructor`." (Give if they ask which parser path.)
- If they ask for the scores: NVD 9.8, vendor 8.3. Let them find it, but confirm if they are close.
- If they want to run a proof, let them — a local canary (load a global-tag payload) is a strong move.

### Hint ladder

1. **Gentle:** "The score is one thing. What makes it exploitable in *our* service specifically?"
2. **Firmer:** "Who controls the YAML that reaches `new Yaml().load`?"
3. **Direct:** "The input is tenant-uploaded and reaches the unsafe `Constructor`. That's the trust boundary. Now — fix and verify?"

### Follow-up and curveball questions

- "We have no internet egress. Does that change your rating?" (Good: lowers the URL/JNDI risk, not to zero. In-process gadgets, and egress rules change. Still fix.)
- "Can you mitigate without upgrading?" (Good: `SafeConstructor` on 1.33 plus `LoaderOptions` passed to the `Yaml`. But other 1.x CVEs remain.)
- "Prove to an auditor it's fixed." (Good: dependency:tree, pinned version, a regression test that rejects a malicious payload, clean re-scan, a VEX record.)
- Curveball: "Why 2.7 and not 2.0?" (Good: 2.7 also closes the Compact Object Notation bypass, issue 1115.)

### Green flags

- States the **trust boundary** (tenant-uploaded input) before quoting the score.
- Re-scores for our deployment instead of reading the NVD number.
- Proposes a **regression test** as the proof, not a version string.
- Knows 2.x blocks global tags by default and that `SafeConstructor` is defence in depth.

### Red flags

- "Just bump the version" with no mention of the trust boundary.
- "We have no gadget, so we're safe" as a final answer.
- Confuses it with the SnakeYAML denial-of-service CVEs.
- Quotes one score and cannot say why NVD and the vendor differ.

### Score guide

- **Research quality / decision making:** 4 = found the CVE, judged exploitability from the trust boundary, proposed the fix with a verify step and a who-to-tell. 3 = correct triage, lighter on verification. 2 = read the CVE but weak on "exploitable here". 1 = score-only, no judgment.
- **Design depth / trade-offs / security:** 4 = regression-test proof, SafeConstructor plus LoaderOptions, named the 2.x API break as a risk. 3 = upgrade plus SafeConstructor. 2 = upgrade only. 1 = no concrete fix or verify.

---

# Research B2 — Pick a rate-limiting library

### Lines to say when you hand it out

> "Our hand-rolled token-bucket limiter keeps causing trouble. We want a library. Each target needs its own limit with bursts, and we may need a shared quota across several worker instances. Spend about twenty minutes. Compare Bucket4j and Resilience4j's RateLimiter — and Guava's RateLimiter as a third option — then recommend one, with risks. A comparison table is ideal. Internet and AI are open."

### Time box and time checks

- Total **20 min**.
- **5 min:** should have the comparison dimensions (algorithm, blocking timeout, per-key, distributed, metrics, Java, license, maintenance).
- **12 min:** should be filling the table from docs and Maven Central, not guessing versions.
- **17 min:** should be on the recommendation and its risks.

### What to provide if asked

- The limiter needs a **blocking acquire with a timeout** (our `acquire(Duration)` contract), **one limiter per target**, and maybe a **shared quota across instances**.
- If they ask for versions, let them check Maven Central. For reference: Bucket4j 8.21.0 (2026-10-02), Resilience4j 2.4.0 (2026-03-14), Guava 33.7.2-jre (2026-09-29).
- If they ask "does Resilience4j do distributed?" say "check — what did you find?" (It does not.)

### Hint ladder

1. **Gentle:** "What are the hard requirements here, the ones that eliminate options?"
2. **Firmer:** "Which of the three can share a quota across worker instances?"
3. **Direct:** "Only one of them has distributed backends. Which, and does that settle it given the requirement?"

### Follow-up and curveball questions

- "We only run one instance today. Does your answer change?" (Good: keep-our-own or Resilience4j would do, but the design asks for shared quotas, so pick the one that doesn't need a rewrite, and hide it behind an interface.)
- "How do you test a rate limiter without real time?" (Good: inject the clock — Bucket4j `TimeMeter`, Resilience4j nanoTime supplier, our own `Clock`+`Sleeper`.)
- "Bucket4j's maintainer stops. What then?" (Good: behind our interface, vendor the small token-bucket class or switch.)
- Curveball: "Is Resilience4j's RateLimiter a token bucket?" (Good: no, it's a fixed window that resets permits each cycle.)

### Green flags

- Separates the **three hard requirements** and scores each option against them.
- Names **shared-quota-across-instances** as the deciding criterion that only Bucket4j meets.
- Checks versions and dates on Maven Central, not from memory.
- Calls out Bucket4j's one-maintainer risk and Guava's @Beta status, and wraps the choice behind an interface.

### Red flags

- Calls Resilience4j's limiter a token bucket.
- Reaches for Guava without noticing @Beta or the 3 MB jar.
- Ignores the shared-quota requirement (the whole deciding factor).
- Picks on stars alone, with no risk named.

### Score guide

- **Research quality / decision making:** 4 = table filled from primary sources, versions checked, recommendation tied to the deciding criterion with risks. 3 = good table, clear pick, light on risk. 2 = partial table, some guessed facts. 1 = no real comparison.
- **Design depth / trade-offs / security:** 4 = three-requirement framing, interface wrapper, bus-factor and @Beta named. 3 = clear pick with trade-offs. 2 = pick with weak justification. 1 = feature list, no decision.

---

# Design (Flow A) — near-real-time multi-tenant sync that can never mass-delete

### Lines to say when you hand it out

> "Today SecretSync polls every five minutes. Product wants near-real-time. Scale: three thousand tenants, up to a hundred thousand secrets each, up to twenty targets per tenant. A change must show up in the targets within sixty seconds at p99. And a hard rule: a source glitch must **never** mass-delete target secrets. Spend about thirty-five minutes. Start with questions and assumptions, then design it. Draw as you go — a text file or boxes are fine."

### Time box and time checks

- Total **30-40 min**. Follow `cheatsheets/design-checklist.md`'s plan.
- **0-5 min:** clarifying questions and written assumptions. If they start drawing a big diagram first, stop them: "What would you check before drawing?"
- **5-8 min:** back-of-envelope numbers. Prompt if they skip it: "Roughly how many changes a second? Can polling meet sixty seconds?"
- **8-12 min:** the few API operations and events.
- **12-22 min:** the main-flow architecture.
- **22-30 min:** deep dives they pick (push them to change capture, fairness, deletion safety).
- **30-35 min:** failure, security, observability, rollout, trade-offs. Add a curveball here.

### What to provide if asked

- Freshness: 60 s p99 for a create or update. A delete may be slower by design.
- Change rate: "assume a secret rotates about once a month, with bursts." Fan-out: "assume about two targets per secret on average, twenty is the ceiling."
- Events: "the source can emit change events. The providers differ — Azure has no delete event."
- Deletes: "assume a wrong delete is expensive and sometimes irreversible."
- If they ask for the day-in-seconds: 86,400.

### Hint ladder

1. **Gentle (no numbers):** "Before the design — can five-minute polling even meet sixty seconds at this scale? Show me roughly."
2. **Firmer (events only, no correctness backstop):** "Events give you latency. What catches a missed event or a delete that had no event?"
3. **Direct (no deletion safety):** "The hard rule is no mass delete. Give me two independent layers that guarantee it."

### Follow-up and curveball questions (add one mid-design)

- Curveball: "Azure emits no delete event." (Good: events drive upserts, the reconcile loop owns deletes.)
- Curveball: "One tenant uploads a hundred thousand secrets at once." (Good: fairness throttle, backlog, bounded per-tenant concurrency, other tenants stay fast.)
- Curveball: "Make it exactly-once." (Good: you can't across systems — idempotent plus ordered-per-key by version is effectively once.)
- "Where do secret values live?" (Good: never in the queue or events — pass a reference, fetch at apply time.)
- "What do you alert on?" (Good: lag p99 over 60 s, any DLQ message, deletes-blocked, sustained throttle.)

### Green flags

- Asks sharp questions and writes assumptions down before drawing.
- Does the arithmetic and concludes polling cannot meet 60 s, so events plus a reconcile backstop.
- Uses a `(timestamp, id)` watermark advanced only after apply.
- Separates per-tenant fairness from per-target rate limits.
- Gives **two** independent deletion-safety layers (propagate-on-error **and** a blast-radius cap).
- Keeps secret values out of the queue, logs and metrics.

### Red flags

- Big diagram before any question or number.
- Names Kafka or Redis without saying which property they need.
- Events only, no correctness backstop, no delete coverage.
- One shared worker pool that lets a big tenant starve the others.
- Deletes from a possibly-empty or stale list (the SYNC-203 trap).
- Security as an afterthought.

### Score guide

- **Research quality / decision making:** here read as **problem framing and numbers** — 4 = clarifies, quantifies, and shows polling fails the target, so the architecture follows from the math. 3 = good assumptions and rough numbers. 2 = some numbers, thin reasoning. 1 = jumps to a diagram.
- **Design depth / trade-offs / security:** 4 = deep on change-capture correctness, fairness and deletion safety, trade-offs stated, secrets kept out of transit, adapts to a curveball in a sentence. 3 = covers the deep dives and failure modes. 2 = a reasonable design, shallow on the hard parts. 1 = no failure or security thinking.

---

# Design + implement B2 — Deletion guard (design, then a thin slice)

### Lines to say when you hand it out

> "A sync cycle must never delete more than a configured fraction — say ten percent — or a configured count of a target's managed secrets in one cycle. When the guard trips: skip **all** the deletes that cycle, still apply the creates and updates, set a distinct report status, and log an error an operator can alert on. Also add a per-policy dry-run flag that logs the plan and changes nothing. Spend about thirty-five minutes. Design it first — where the check lives, the defaults, how an operator overrides it, how it relates to the fail-open bug we just fixed. Then implement a thin slice with tests. Internet and AI are open."

### Time box and time checks

- Total **35-40 min**.
- **0-10 min:** design — where the check lives, defaults, the fraction-or-count rule, override, dry-run, relation to SYNC-203. If they start coding at once, pull them back: "Design it first — where does the check live?"
- **10-12 min:** a behaviour table (input → expected).
- **12-30 min:** the thin slice — the guard component and the wiring, plus tests.
- **30-35 min:** the security trade-off and loose ends.

### What to provide if asked

- The flow is scan → `listManagedSecrets()` → `DiffCalculator.plan` → `SyncExecutor.execute`. The guard goes between plan and execute.
- `SyncReport` already has `status()` (SUCCESS / PARTIAL_FAILURE) and an `aborted(...)` factory. They add a `DELETES_BLOCKED` status.
- The fraction-or-count phrasing is **ambiguous on purpose**. If they ask, say "you decide, but state your interpretation." (The model answer uses `allowed = max(count, floor(fraction × managed))`.)
- Test utilities exist: fakes, `MutableClock`, `FakeSleeper`.

### Hint ladder

1. **Gentle:** "Where in the cycle does this check belong, and what does it need to see?"
2. **Firmer:** "How does this relate to the SYNC-203 fix? Is the guard a replacement or a second layer?"
3. **Direct:** "Put a pure `DeletionGuard.decide(plan, managedCount, config)` between plan and execute. It returns either the plan or an upserts-only plan plus a blocked flag. Now the tests?"

### Follow-up and curveball questions

- "Small target, three secrets, one orphan. Does the guard block it?" (Good: not if the count floor allows it — explain the `max` rule.)
- "How does an operator override it, safely?" (Good: raise the threshold, or a time-boxed, audited one-shot override, like Entra Connect's `Disable-ADSyncExportDeletionThreshold`, never a silent permanent off.)
- "A cycle is blocked. What happens to a secret whose value changed?" (Good: updates still apply — only deletes defer.)
- Curveball: "Is the guard enough on its own?" (Good: no — still fix the fail-open scan, the guard is the second layer.)
- "Where do you test time?" (Good: the guard is pure, no clock needed, and the engine wires time.)

### Green flags

- Puts the check **between plan and execute** as a **pure** component.
- States the fraction-or-count interpretation out loud and justifies `max` over `min`.
- Keeps creates and updates applying when deletes are blocked.
- Ties it to SYNC-203 as a **second** layer, not a replacement.
- Writes tests with clear `method_scenario_expected` names, pure guard tests with no clock.
- Names the security trade: a lingering secret vs a mass delete, resolved with a loud alert and an audited override.

### Red flags

- Codes before designing, or buries the check inside `SyncExecutor` where it is hard to test.
- Makes the guard impure (reads the vault or the clock) so the tests must sleep.
- Blocks creates and updates too, not just deletes.
- Treats the guard as a replacement for the fail-open fix.
- No test for the blocked path or for dry-run.
- Silent permanent override with no audit.

### Score guide

- **Research quality / decision making:** here read as **problem analysis and defaults** — 4 = clear interpretation of the ambiguous rule, sane defaults, a safe override model (cites a real precedent), relation to SYNC-203 stated. 3 = reasonable defaults and placement. 2 = works but shaky on the rule or the override. 1 = misreads the requirement (blocks upserts, or guards nothing).
- **Design depth / trade-offs / security:** 4 = pure testable guard, upserts kept, two-layer safety, the lingering-secret trade named and resolved, tests cover blocked and dry-run. 3 = correct slice with tests. 2 = partial slice, thin tests. 1 = no working slice or no security thought.

---

## Running the whole research-and-design block as one mock

If you run all five in one sitting, keep to this order and budget. It mirrors the real exercise shape (research, design, implement) and totals about two hours with breaks.

| Order | Prompt | Budget |
|---|---|---|
| 1 | Research A (primary) **or** Research B — pick one | 20-25 min |
| 2 | Research B2 (library pick) | 20 min |
| 3 | Design (Flow A) | 35-40 min |
| 4 | Design + implement B2 (deletion guard) | 35-40 min |

Between prompts, give a one-line verdict ("good on the trade-off, light on verification") and move on. Save the full scoring for the end so the candidate keeps momentum. The debugging part (the six tickets) is separate and has its own guide.
