# Prep guide

Research for this guide was done on 2026-10-21 from public sources: candidate reports, job postings, official docs, and news. Facts that change over time carry a date.

## 1. What to expect

**The format, as the recruiter described it**
- Two hours on Zoom with two developers from the team or group.
- Interactive. It simulates day-to-day work.
- Three parts: research, design, and debugging in Java. Java expertise is not what they test.
- You work on a remote Windows machine on AWS, over Remote Desktop, with IntelliJ preinstalled. On a Mac you connect with Microsoft's Windows App.
- AI tools and the internet are allowed.
- The recruiter said the exercise "changed a bit", so older reports may not match exactly.

**What candidates reported (closest public matches)**

| Report | What happened | What it tells you |
|---|---|---|
| Petah Tikva, intern level, about March 2025 ([Glassdoor](https://www.glassdoor.co.in/Interview/CyberArk-Software-Engineer-Intern-Interview-Questions-EI_IE30042.0,8_KO9,33.htm)) | 1. Find a bug in a Java client-server system. 2. Research two tools on the internet and pick the better one for a new feature. 3. Design how to add that feature, then implement it. | The same skeleton as your format. Expect a higher bar on trade-offs, scale and security at your level. |
| Senior developer, date unknown ([Glassdoor](https://www.glassdoor.com/Interview/CyberArk-Senior-Software-Developer-Interview-Questions-EI_IE30042.0,8_KO9,34.htm)) | A 2-hour Zoom test: writing code, and reviewing Java code. The review code had two planted problems: a get-all endpoint without pagination, and sensitive data written to the log. | Security hygiene is checked. Look for secrets in logs and unbounded queries. |
| January 2026 ([Glassdoor](https://fr.glassdoor.ca/Entretien/CyberArk-Entretien-E30042-RVW99580235.htm)) | The technical exercise ran as two interviews, late in the process, after the group-manager round. | The exercise is a major gate. |
| Several reports, 2025-2026 | Interviewers dig into your own projects, then add a new constraint and ask how you would handle it. | Prepare 2-3 project deep-dives with trade-offs. |

These come from search-engine snippets of anonymous reviews. Treat each one as a single data point.

**What is not public:** the scoring rubric, and the exact AI rules. Ask about the AI rules in the first minutes (see section 3).

**What they most likely evaluate**
- How fast you get productive in unfamiliar code. The postings ask for "rapid self-learning" of new languages.
- How you research and decide. The postings list "Research new technologies and platforms" as a duty.
- Design and analysis: trade-offs, scale, failure modes.
- A security mindset. It is a security company.
- Communication: they can only score what they hear and see over Zoom.
- Judgement with AI. The team's postings ask for AI tools in the development process.

## 2. The company and the team in five minutes

**The names**
- Palo Alto Networks (PANW) completed its acquisition of CyberArk on 11 February 2026, for about $25 billion.
- On 12 May 2026, PANW launched **Idira**: one identity-security platform built on CyberArk technology, for human, machine and AI-agent identities. "idra" is almost certainly Idira. Say "Idira" in the interview.
- cyberark.com now redirects to paloaltonetworks.com/idira. The docs site is now called "Idira Docs".
- Inside Idira there is a **Secrets Management** business unit. Its product groups are Secrets Manager, Secrets Hub and Credential Providers.

| Older name | Name today (October 2026) |
|---|---|
| Conjur Cloud | Idira Secrets Manager, SaaS |
| Conjur Enterprise | Idira Secrets Manager, Self-Hosted |
| Conjur Open Source | Conjur Open Source (unchanged) |
| Secrets Hub | Idira Secrets Hub |
| Credential Providers (CP, CCP, ASCP; older names AIM / AAM) | Idira Credential Providers |

**The products in one line each**
- **Secrets Manager, SaaS:** secrets for workloads in cloud and hybrid environments. It targets the "secret zero" problem. Workload access tokens are JWTs valid for 8 minutes (60 minutes for users). A batch API returns up to 250 secrets per call. **Edge** is a component that sits near workloads, serves secrets locally and keeps working offline. **Secure Workload Access** (GA May 2026) issues short-lived SPIFFE identities to workloads.
- **Secrets Manager, Self-Hosted:** the same for customers who run it themselves. One Leader takes all writes, Standbys can be promoted, read-only Followers scale reads.
- **Secrets Hub:** keeps secrets in the PAM vault (Privilege Cloud) as the source of truth and syncs them, by sync policy, into AWS Secrets Manager, Azure Key Vault, GCP Secret Manager and HashiCorp Vault. Apps read them with the cloud's native API. Its team works in Python on AWS serverless.
- **Credential Providers:** remove hard-coded credentials from apps. CP is an agent with a local cache, CCP is a web service on IIS, ASCP plugs into app servers. Its team works in C++ plus Java, C# or Go.
- **Open source (Go):** Secrets Provider for Kubernetes, Secretless Broker, Summon. The Conjur server itself is Ruby on Rails.

**How Conjur / Secrets Manager authenticates workloads**

| Authenticator | How it works |
|---|---|
| `authn` | API key. This stored key is the "secret zero". |
| `authn-jwt` | The workload presents a signed JWT, for example a Kubernetes service-account token or a SPIFFE JWT-SVID. Conjur checks it against the issuer's keys (JWKS) and maps claims to an identity. |
| `authn-k8s` | Certificate-based mutual TLS for Kubernetes resources. |
| `authn-iam` | An AWS workload proves its IAM role with a signed AWS STS `GetCallerIdentity` request. |
| `authn-azure`, `authn-gcp` | The cloud's managed identity or signed identity token. |
| `authn-oidc` | Now only for humans signing in to the UI or CLI. |

**Policy as code:** YAML that declares workloads, secrets (variables) and permissions. Access is denied by default. Policy never holds secret values.

**The tech stack in the Secrets Manager postings (Aug-Sep 2026):** Go, Java or Python required. Ruby on Rails, AWS microservices, Docker and Kubernetes are a plus. Levels: Staff 3+ years, Senior Staff 5+, Principal 8+.

**A real incident worth knowing:** CVE-2025-49827 (July 2025). A badly written regex in Conjur's AWS IAM authenticator let an attacker point the validation request at their own server and bypass authentication. CVSS 9.1 (v4) / 9.8 (v3.1). It was part of Cyata's "Vault Fault" research. It is a good talking point for input validation and for anchoring a regex.

## 3. How to approach each part

**The first five minutes**
- Ask: "Which AI tools can I use? Is one set up on this machine? Is the internet open? Do you want me to talk through my prompts?"
- Open the README. Run the tests. Run the app. Then explain the main request flow out loud, in two minutes.

**Debugging:** use the loop in `cheatsheets/intellij-debugging.md`. Read the code as a security reviewer too: secrets in logs or `toString()`, missing pagination, missing timeouts, missing authorization checks, input that is not encoded or validated, an error treated as "no data", swallowed exceptions.

**Research:** make the decision visible.
1. State the requirement in one sentence.
2. List the criteria: fit for the requirement, API, Java and Maven compatibility, license, maintenance activity, security record, performance, operational cost.
3. Compare the options against the criteria. A small table is ideal.
4. Recommend one, with its risks and a fallback.
5. Use AI for the first pass. Then confirm the key claims in the official docs, and say that you did.

**Design:** use `cheatsheets/design-checklist.md`. Expect them to add a new constraint halfway through. If they ask you to implement, build a thin vertical slice with a test, not a half-finished big one.

**Throughout:** about every 20 minutes, summarize what you know, your current hypothesis and your next step. Correct, tested and explained beats "finished everything".

## 4. Bug patterns to know (read after your first mock, not before)

- Off-by-one and integer division, for example in page counts.
- A flipped comparison or a wrong boundary (`<` versus `<=`).
- A unit mismatch: milliseconds versus seconds, bytes versus characters.
- Time zones: `LocalDateTime` where `Instant` is needed.
- An error treated as "empty" (fail-open). It is dangerous when something deletes "what is missing".
- Swallowed exceptions: catch-and-log-only, or futures nobody calls `get()` on.
- Check-then-act races, missing locks, missing `volatile`.
- Stale state reused, for example a request built once outside a retry loop.
- Missing encoding or escaping: URLs, regex, SQL, JSON, file paths.
- `equals`/`hashCode` problems, mutable map keys, `==` on objects.
- Changing a collection while iterating over it.
- Resource leaks: streams, connections, thread pools.
- No timeout on a network call.
- Unbounded queries: no pagination, no limit.
- Secrets in logs, exception messages or `toString()`.

## 5. Glossary

| Term | Meaning |
|---|---|
| Secret | Any credential a person or machine uses: API key, password, token, SSH key, certificate. |
| Secret zero | The first credential a workload needs before it can reach the vault. Workload identity removes it. |
| Machine (non-human) identity | The identity of a service, host, workload or AI agent. |
| Workload identity | A short-lived identity a workload gets from verified attributes, instead of a stored credential. |
| SPIFFE / SVID | An open standard for workload identity. An SVID is the signed document (X.509 or JWT) that carries a SPIFFE ID. |
| Attestation | Checking a node's or workload's properties before giving it an identity. |
| JWT / JWKS | A signed token with claims / the published set of public keys used to verify it. |
| OIDC | An identity layer on top of OAuth 2.0 that issues ID tokens. |
| mTLS | TLS where the client also proves its identity with a certificate. |
| Policy as code | Access rules in versioned files (YAML in Conjur), reviewed like code. |
| RBAC / least privilege | Access by role, and only the access a task needs. |
| Rotation | Replacing a secret regularly so a stolen copy expires quickly. |
| Dual accounts | Two accounts that take turns being active, so rotation never breaks running apps. |
| Dynamic secret | A credential created on demand with a short lease, then revoked. |
| JIT access / Zero Standing Privileges | Access granted only for a task and removed afterwards / no permanent privileges at all. |
| PAM | Privileged Access Management: protecting and monitoring privileged accounts and sessions. |
| Vault / Safe | The hardened store for privileged credentials / a container of accounts in it with per-member permissions. |
| CPM / SRS | The component that rotates and verifies passwords / its SaaS successor (Secrets Rotation Service). |
| Envelope encryption | Data encrypted with a data key, and the data key encrypted with a master key held in a KMS or HSM. |
| KMS / HSM | A managed key service / a hardware device that guards keys. |
| BYOK | The customer controls the key material. |
| Audit trail | A tamper-resistant record of who accessed or changed what, and when. |
| Break-glass | An emergency access path, used rarely and monitored closely. |
| Secretless pattern | A local broker injects credentials into connections, so the app never holds them. |
| Vault / secret sprawl | Secrets spread across many stores with no central oversight. Secrets Hub exists for this. |

## 6. Resources

There is no public mock that copies this exact format. That is why this repo exists. These are the best supporting resources.

**Tonight, free, in this order (about 1.5 hours if you do all of them)**
1. [IntelliJ: Debug your first Java application](https://www.jetbrains.com/help/idea/debugging-your-first-java-application.html), 30 min. If you already use the debugger daily, skip it and only rehearse the Windows keys.
2. [Canva: "Yes, you can use AI in our interviews"](https://canva.dev/blog/engineering/yes-you-can-use-ai-in-our-interviews) and its follow-up [AI interview success](https://www.canva.dev/blog/engineering/ai-interview-success/), 15 min. They list what AI-assisted interviewers assess and the three ways candidates fail.
3. [Hello Interview: AI-enabled coding guide](https://www.hellointerview.com/learn/ai-coding/overview/introduction), 20 min. Read the format and codebase-orientation pages.
4. [Jake Zimmerman: "Bug Squash"](https://blog.jez.io/bugsquash/), 15 min. What a debugging interviewer scores.
5. [Idira Secrets Manager, SaaS docs](https://docs.cyberark.com/secrets-manager-saas/latest/en/content/resources/_topnav/cc_home.htm), 15 min. Read the core concepts page.
6. [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html), 15 min skim. The best single checklist for the design part.

**Later, free**
- [Defects4J](https://github.com/rjust/defects4j): 854 real bugs from real Java projects, each with a failing test.
- [QuixBugs](https://github.com/jkoppel/QuixBugs): 40 small programs with a one-line bug each, for warm-ups.
- [cyberark/conjur-api-java](https://github.com/cyberark/conjur-api-java): the real Java client for the Secrets Manager API. Read it to see the team's style.
- [conjur-quickstart](https://github.com/cyberark/conjur-quickstart): run Conjur locally with Docker.
- HashiCorp Vault [Architecture](https://developer.hashicorp.com/vault/docs/internals/architecture) and [Security Model](https://developer.hashicorp.com/vault/docs/internals/security).
- AWS Secrets Manager [best practices](https://docs.aws.amazon.com/secretsmanager/latest/userguide/best-practices.html) and [rotation](https://docs.aws.amazon.com/secretsmanager/latest/userguide/rotating-secrets.html).
- [Hello Interview: System Design in a Hurry](https://www.hellointerview.com/learn/system-design/in-a-hurry/introduction) and its [Distributed Rate Limiter](https://www.hellointerview.com/learn/system-design/problem-breakdowns/distributed-rate-limiter) breakdown.
- [System Design Primer](https://github.com/donnemartin/system-design-primer).

**Paid, only if you want a human or AI mock (prices seen on 2026-10-21)**
- [Hello Interview Premium](https://www.hellointerview.com/pricing): about $47 for one month, with AI mock interviews for system design and AI-assisted coding.
- [interviewing.io](https://interviewing.io/): anonymous mocks with senior engineers, from $179 per session.
- [Aced](https://www.aced.io/) (formerly Exponent, which now includes Pramp): free peer mocks with a monthly allowance, paid plans for more.
- [Prepfully](https://prepfully.com/): independent coaches, roughly $79-$249 per hour.
- [Baot](https://www.baot.org/): a free Israeli community for women in software, with volunteer mock interviews.

Skip the many "CyberArk interview questions" pages for PAM administrators. They are about operating the products, not about R&D interviews.

## 7. Questions to ask them at the end

- "What is the hardest technical problem the team is working on this quarter?"
- "How does the move to Idira change the Secrets Manager roadmap?"
- "How does the team use AI tools day to day?"
- "What does on-call look like, and what was the last interesting incident?"
- "What would success look like for me after six months?"

## 8. Morning checklist

- Set the Mac input language to English before you connect. The remote keyboard follows the Mac's input source.
- Turn on standard function keys (see `cheatsheets/remote-windows-setup.md`).
- Update Windows App. Test the connection details as soon as they arrive.
- Ask whether to share the whole screen or only the Remote Desktop window.
- Have water, paper, a charger and a quiet room. Turn off notifications.
- Prepare three stories: a project deep-dive with trade-offs, a hard bug you found, and how you use AI in your daily work.
