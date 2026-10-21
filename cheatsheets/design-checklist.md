# Design part: a 35-minute plan and a checklist

Use this plan for any "design X" prompt. Talk the whole time. Draw as you talk: a whiteboard tool, a plain text file, or boxes in a Markdown file all work.

## The plan

| Minutes | Step | What you say or produce |
|---|---|---|
| 0-5 | Clarify | 3-5 sharp questions: who uses it, which use cases matter, scale, latency/freshness targets, what is out of scope. Write your assumptions down where they can see them. |
| 5-8 | Numbers | Back-of-envelope: requests per second at peak, data size, fan-out. Round hard. 1M requests a day is about 12 per second. 86,400 seconds is a day. |
| 8-12 | API | The 2-4 operations or events that matter, with inputs, outputs and who is allowed to call them. |
| 12-22 | Architecture | Boxes and arrows for the main flow, end to end. Say where state lives and why. |
| 22-30 | Deep dives | Pick the 2-3 hardest parts yourself, before they ask: consistency, caching, concurrency, rate limits, idempotency, ordering. |
| 30-35 | Failure, security, operations | What happens when each box is slow or down. How you keep secrets safe. What you monitor. How you roll it out. End with the trade-offs you chose. |

When they push on one area, follow them. The plan is a default, not a script.

## Always cover, in this domain

**Security**
- Authentication of every caller. Prefer workload identity (Kubernetes service-account tokens, cloud IAM roles) over stored API keys. Name the "secret zero" problem.
- Authorization with least privilege. Say who can read which secret, and how you enforce it.
- Secrets in transit: TLS everywhere, mTLS between internal services.
- Secrets at rest: encryption with a key hierarchy (envelope encryption: data keys encrypted by a master key in a KMS or HSM).
- No secret values in logs, metrics, traces, queues, error messages or crash dumps. Pass references (secret id + version), fetch the value at the last moment.
- Audit trail: who read or changed what, when, from where. Make it tamper-evident.
- Tenant isolation and blast radius: one tenant's bug or load must not reach another tenant.
- Break-glass access and how you detect its use.

**Freshness and rotation**
- Rotation with overlap (two valid credentials during the switch), so clients that still hold the old value keep working.
- How clients learn about a change: TTL expiry, push notification, version checks (ETag / If-None-Match), or retry on an auth failure.
- What a cache serves when the source is down, and for how long ("serve stale up to N minutes, then fail closed").

**Reliability**
- Timeouts on every network call. Retries only for transient errors, with exponential backoff and jitter, and a retry budget.
- Idempotency: the same request twice must not do the work twice. Use versions or idempotency keys.
- Rate limits and quotas per tenant and per downstream system. Back-pressure instead of dropping work.
- Safe defaults: when unsure, fail closed for access, and do nothing destructive. A source outage must never look like "the source is empty".

**Operations**
- Metrics that match the promise: lag, error rate, p99 latency, queue depth, per tenant.
- Alerts on symptoms users feel, not on every error.
- Rollout: feature flag, a canary tenant, backward-compatible APIs, a rollback plan, a data migration plan.

## Phrases that show seniority

- "Before I draw anything, let me check my assumptions about scale and freshness."
- "The hardest part here is X, so I'll spend most of the time there."
- "We have two options. A gives us P, B gives us Q. Given the requirement R, I pick A, and I accept the cost C."
- "What happens if this component is down for 10 minutes?"
- "How would we know this is broken in production?"
- "I'd ship a first version with X and add Y only when we see Z."

## Common traps

- Drawing a large diagram before you ask any question.
- Naming technologies (Kafka, Redis) without saying what property you need from them.
- Ignoring failure modes until the interviewer asks.
- Forgetting the human: who operates this at 3 a.m., and what do they see?
- Treating security as a last slide. In a secrets product it is the first requirement.
