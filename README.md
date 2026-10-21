# Secrets interview practice

Two mock exercises for a 2-hour technical interview with a research part, a design part and a Java debugging part, in the secrets-management domain.

Each exercise is a small, realistic Java codebase with bugs to find, bug tickets, a demo app that reproduces each ticket, and research and design prompts. Model answers, every fix, and a guide for a practice partner are on the `solutions` branch.

The format this practices: two interviewers, about two hours, on a remote Windows machine with IntelliJ, internet and AI tools allowed. The debugging part is in Java, but it tests how you debug, not Java trivia.

## Interview tomorrow morning: the plan for tonight

| When | What | Time |
|---|---|---|
| Now | Setup (below), including the keyboard setup | 15 min |
| Now | Skim `cheatsheets/intellij-debugging.md` and `cheatsheets/ai-in-the-interview.md` | 10 min |
| Tonight | Option 1 in **easy mode** (see its README): 3 easy bugs, a short research, a spoken design | 60 min |
| Tonight | Compare your work with the `solutions` branch | 15 min |
| Morning | `PREP_GUIDE.md` sections 1-3 and the glossary | 15 min |
| Morning | SYNC-201 and SYNC-202 from Option 2 as a warm-up | 20 min |
| Before the call | "First five minutes" in `cheatsheets/remote-windows-setup.md` | 10 min |

Each exercise holds more than two hours of material on purpose. You are not expected to finish it. Every ticket is tagged easy, medium or hard. The real interview most likely has fewer and simpler bugs than the full set.

Then sleep. A rested brain debugs faster than a crammed one.

## Setup (15 minutes)

1. Install IntelliJ IDEA if you do not have it: https://www.jetbrains.com/idea/download/
2. Clone the repo:
   ```
   git clone https://github.com/OFTK/secrets-interview-practice.git
   ```
3. In IntelliJ, select File > Open and choose the folder `exercise-1-secrets-client` (the exercise folder, not the repo root). Trust the project when asked.
4. If IntelliJ asks for a JDK, select Download JDK and choose version 17 or newer.
5. Open the Run configurations list (top right) and run **All tests**. Some tests fail on purpose. They are part of the debugging part.
6. Do the 10-minute keyboard setup in `cheatsheets/remote-windows-setup.md`. It covers Windows App, function keys and macOS shortcuts that collide with IntelliJ.

Terminal alternative: `./mvnw test` on macOS or Linux, `mvnw.cmd test` on Windows. The wrapper downloads Maven on the first run.

## The two options

| | Option 1: secrets client SDK | Option 2: secret sync service |
|---|---|---|
| Folder | `exercise-1-secrets-client` | `exercise-2-secret-sync` |
| The code | A Java library that apps embed: token authentication, REST fetch, TTL cache, retries | An engine that copies vault secrets to cloud secret stores: paging, glob policies, diffing, rate limiting, parallel workers |
| Research | Remove the static API key ("secret zero") with workload identity. Alternate: should secrets be `String` or `char[]`? | Add Azure Key Vault and GCP Secret Manager as targets. Alternate: triage a critical CVE in a dependency |
| Design | Rotation-aware secret delivery for 20,000 pods | Near-real-time multi-tenant sync that can never mass-delete |
| Debugging | 6 tickets, from a log leak to a race condition | 6 tickets, from paging to silently swallowed errors |

Start with Option 1. Use Option 2 for a second round, or as a morning warm-up.

## How to run a session

- **Alone:** follow the timeline in the exercise README. Set a timer. Speak out loud as if two interviewers were listening. Write short notes. Then compare with the solutions.
- **With a partner:** the partner opens `INTERVIEWER_GUIDE.md` in the exercise folder on the `solutions` branch and plays both interviewers.

## Solutions

Do not look before you try. After your attempt:

```
git checkout solutions
git diff main solutions -- exercise-1-secrets-client
```

The exercise folder on the `solutions` branch also has `SOLUTIONS.md` (root causes, the debugging path, model answers) and `INTERVIEWER_GUIDE.md`.

## What else is here

- `PREP_GUIDE.md`: what to expect, the company and its product area, how to approach each part, and the best free and paid resources.
- `cheatsheets/`: IntelliJ debugging (Windows and macOS keys), Windows App setup, using AI in the interview, and a design checklist.

The exercises are fictional ("Acme"). They are not any company's real interview exercise. Exercise 2 pins an old SnakeYAML version on purpose, for a research task. Do not copy that dependency into real projects.
