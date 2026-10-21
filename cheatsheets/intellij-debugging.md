# IntelliJ for the debugging part

The remote machine runs Windows, so IntelliJ there uses the **Windows keymap**. Learn the Windows column below. IntelliJ on a Mac hides the "Windows" keymap; the closest option is "IntelliJ IDEA Classic" (see `remote-windows-setup.md`). Over Remote Desktop from a Mac, press the physical **Control** key for every "Ctrl" shortcut and the **left** Option key for "Alt".

## Shortcuts

| Action | Windows keymap (remote machine) | macOS keymap |
|---|---|---|
| Search everywhere (classes, files, actions) | Shift Shift | Shift Shift |
| Find action by name | Ctrl+Shift+A | Cmd+Shift+A |
| Find in files | Ctrl+Shift+F | Cmd+Shift+F |
| Go to class | Ctrl+N | Cmd+O |
| Go to file | Ctrl+Shift+N | Cmd+Shift+O |
| Go to symbol | Ctrl+Alt+Shift+N | Cmd+Option+O |
| Go to declaration | Ctrl+B or Ctrl+Click | Cmd+B or Cmd+Click |
| Go to implementation | Ctrl+Alt+B | Cmd+Option+B |
| Find usages | Alt+F7 | Option+F7 |
| Call hierarchy | Ctrl+Alt+H | Ctrl+Option+H |
| Recent files | Ctrl+E | Cmd+E |
| Navigate back / forward | Ctrl+Alt+Left / Right | Cmd+[ / Cmd+] |
| File structure | Ctrl+F12 | Cmd+F12 |
| Show fixes (intention actions) | Alt+Enter | Option+Enter |
| Rename | Shift+F6 | Shift+F6 |
| Reformat code | Ctrl+Alt+L | Cmd+Option+L |
| Comment line | Ctrl+/ | Cmd+/ |
| Terminal | Alt+F12 | Option+F12 |
| Run anything | Ctrl Ctrl | Ctrl Ctrl |
| Run the test or main under the cursor | Ctrl+Shift+F10 | Ctrl+Shift+R |
| Debug the test or main under the cursor | no default: gutter arrow > Debug | Ctrl+Shift+D |
| Run / Debug the selected configuration | Shift+F10 / Shift+F9 | Ctrl+R / Ctrl+D |
| Rerun last | Ctrl+F5 | Cmd+R |
| Toggle line breakpoint | Ctrl+F8 | Cmd+F8 |
| View all breakpoints | Ctrl+Shift+F8 | Cmd+Shift+F8 |
| Step over | F8 | F8 |
| Step into | F7 | F7 |
| Smart step into (choose the call) | Shift+F7 | Shift+F7 |
| Step out | Shift+F8 | Shift+F8 |
| Run to cursor | Alt+F9 | Option+F9 |
| Resume | F9 | Cmd+Option+R |
| Evaluate expression | Alt+F8 | Option+F8 |

On a Mac laptop, F7, F8 and F9 need the Fn key unless you set "Use F1, F2, etc. keys as standard function keys" in System Settings > Keyboard. Set it tonight, because stepping is the core of this part. See `remote-windows-setup.md`.

When a shortcut fails, use Find Action (Ctrl+Shift+A) and type the action name. It always works.

You can also click the gutter: the green arrow next to a test or `main` runs or debugs it, and a click in the gutter sets a breakpoint.

## Debugger features worth knowing

- **Conditional breakpoint.** Right-click a breakpoint, add a condition such as `secretId.contains("#")`. It stops only on the interesting call.
- **Exception breakpoint.** View Breakpoints (Ctrl+Shift+F8), add "Java Exception Breakpoint", pick a class or any exception. It stops where the exception is thrown, not where it is logged.
- **Logging breakpoint.** Shift+click the gutter, or clear "Suspend" in the breakpoint settings and enable "Evaluate and log". It prints a value without stopping. Use it for concurrency bugs, where stopping changes the timing.
- **Evaluate expression (Alt+F8).** Run any Java expression in the paused frame, such as `expiresAt.minus(Duration.ofMinutes(5))`.
- **Watches and inline values.** Add a watch for a value you track across steps.
- **Threads view.** In the Debug tool window, the Threads tab shows every thread and its stack. A breakpoint can suspend "All" threads or only "Thread" (set it in the breakpoint settings).
- **Reset frame** (formerly "Drop frame"). In the Threads tab, hover over a frame and click Reset Frame. It re-enters the method so you can step again. It resets local variables only; fields and side effects stay.
- **Force return / throw exception.** Right-click a frame. It shows how callers react to a value or an exception without reproducing the cause.
- **Run to cursor (Alt+F9).** It skips the steps in between.

## A debugging loop that works under time pressure

1. **Read the ticket twice.** Write the symptom in one sentence: what is expected, and what happens instead.
2. **Reproduce it.** Run the failing test or the demo. No reproduction, no fix.
3. **Read the stack trace or the log from the top of your code.** Find the first frame in the project's own packages.
4. **Form one hypothesis and say it out loud.** "I think the cache entry never expires."
5. **Test the hypothesis with the debugger.** Set one breakpoint where the hypothesis would show, and inspect the values.
6. **Fix the cause, not the symptom.** Search for other callers with Find Usages. The same bug often sits in a sibling method.
7. **Prove it.** Add or run a test that failed before the fix and passes after it. Run the whole suite.
8. **Explain it in two sentences:** the root cause, and why the fix is right.

If you are stuck for 5 minutes, say so, state what you have ruled out, and choose a new angle. Interviewers expect this, and it scores well.
