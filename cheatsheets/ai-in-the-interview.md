# Using AI in the interview

The interview allows AI tools and the internet. The interviewers watch how you use them. Using AI well is a skill they score, so practice it tonight the same way.

## The rules you follow

1. **Say what you ask, before you ask it.** "I'll ask the AI to summarize how Kubernetes service-account tokens work, then check the official docs." Silent typing looks like outsourcing.
2. **Ask narrow questions.** "What does `URLEncoder.encode` do with a slash?" beats "find the bug in this project".
3. **Verify every answer.** Run the code, read the official doc, or write a 3-line test. Say how you verified it.
4. **Never paste a secret, token, key or password into an AI tool or a website.** Not even a fake-looking one from the exercise. Say it out loud once: "I won't paste the token value, I'll describe it." In a secrets company this is a strong signal.
5. **Keep the thinking yours.** Use AI for speed (summaries, syntax, boilerplate, test scaffolding, brainstorming edge cases). Make the decisions yourself, and explain why.
6. **When the AI is wrong, say so.** "It says X, but the Javadoc says Y, so I go with Y." Catching a wrong answer is worth more than a right answer you copied.
7. **Do not paste the whole repository and ask for the bugs.** It is slow, often wrong, and it shows nothing about you.

## Good uses, by interview part

**Research**
- "Give me the 5 key steps of <mechanism>, with links to the official docs." Then open the links.
- "Make a comparison table of A, B and C on <attributes>." Then spot-check two cells against the docs.
- "What are the security risks of <approach>?" Then pick the top 3 and say why.

**Design**
- "What failure modes am I missing in this design: <short summary>?"
- "Estimate requests per second for <numbers>." Then redo the math yourself in one line.
- Do not ask it for the whole design. They want your design.

**Debugging**
- "What does this stack trace mean: <trace>?"
- "Explain what this method does: <15 lines>." Then confirm with the debugger.
- "Write a JUnit 5 test that calls X with Y and asserts Z." Then read it before you run it.
- "Is `java.time.Instant.plusSeconds` given seconds or milliseconds?" Quick facts like this are fine.

## Prompt templates

```
I'm debugging a Java service. Symptom: <one sentence>. I suspect <component>.
Here is the method (no secrets inside): <code>.
What could cause this symptom? List hypotheses only, ranked. Don't rewrite the code.
```

```
Summarize how <technology> works in 8 bullet points for a backend engineer.
For each bullet, give the official documentation link.
Mark anything you are not sure about.
```

```
Here is my design summary: <10 lines>.
List the 5 most likely ways it fails in production, and one mitigation each.
```

## Before the interview

- Ask at the start: "Which AI tools can I use here? Is one already set up on this machine?" They prepared the machine, so they may have one ready.
- The remote machine is fresh, so any login there costs time and means typing your password on their machine. Know one browser AI tool that you can use without logging in. Check tonight that it still works logged out.
- Practice tonight's mock with the AI open, under the same rules.
