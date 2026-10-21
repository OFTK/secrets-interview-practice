# Remote Windows machine: setup and dry run

You will work on their Windows machine on AWS, through Remote Desktop. On a Mac, the client is Microsoft's **Windows App**. These facts come from Microsoft, Apple and JetBrains docs (checked 2026-10-21).

## Tonight (10 minutes)

1. Install **Windows App** from the Mac App Store (https://aka.ms/macOSWindowsApp). Current versions need macOS 14 or later. It replaced "Microsoft Remote Desktop" in September 2024.
2. Turn on standard function keys: System Settings > Keyboard > Keyboard Shortcuts > Function Keys > "Use F1, F2, etc. keys as standard function keys". The debugger keys (F7, F8, F9) then work without Fn.
3. Stop macOS from taking keys that IntelliJ needs. In System Settings > Keyboard > Keyboard Shortcuts:
   - Input Sources: clear Control+Space (IntelliJ uses it for completion). Switch languages with the Globe key instead.
   - Mission Control: clear Control+Left/Right/Up/Down.
   - Keyboard: clear any enabled Control+F2 to Control+F8 shortcuts (IntelliJ uses Ctrl+F2 Stop, Ctrl+F5 Rerun, Ctrl+F8 breakpoint).
4. Learn the keys in `intellij-debugging.md` (Windows column).

## How a Mac keyboard behaves in the session

| You press on the Mac | Windows receives | What it means for you |
|---|---|---|
| Control | Ctrl | Use Control for every IntelliJ "Ctrl+..." shortcut. |
| Command | The Windows key | Command+E opens File Explorer, Command+D shows the desktop, Command+R opens Run. Do not press Command out of habit. |
| Command + C, X, V, A, Z, F, W | Ctrl + the same letter | Only these seven are translated for you. |
| Left Option | Alt | Use the left Option key for Alt+Enter, Alt+F7, Alt+F8. |
| Right Option | AltGr | Do not use it for shortcuts. |
| Fn + F1..F12 | F1..F12 | Not needed if you turned on standard function keys. |
| Command+Q | Quits Windows App | Never press it. |

- **The session takes its keyboard language from the Mac when you connect.** Shortcuts work only with English-like layouts. Switch the Mac input source to English (ABC or U.S.) before you connect. If you switch later, reconnect.
- If Alt+Enter or other shortcuts misbehave, switch the keyboard mode with Control+Command+K (scancode) or update Windows App.
- The Mac has no Insert key. For IntelliJ actions that use Insert (Generate, New Scratch File), use the menu or Find Action (Ctrl+Shift+A).

## Connecting

1. Open Windows App. On the Devices tab, click **+** > **Add PC**.
2. Enter the host name or IP address they give you. Add the user name and password when asked.
3. A certificate warning is normal for an AWS Windows machine (self-signed certificate). Continue.
4. Stay on one network. AWS machines usually allow Remote Desktop only from your IP address, so a VPN or a change of Wi-Fi can block you.
5. If you get disconnected, reconnect from the Devices tab. The Windows session normally keeps running, so IntelliJ should still be open.

Display: use a maximized window, not macOS full screen, so you can still see Zoom. If text is too small, zoom IntelliJ itself: Alt+Shift+= (zoom in), Alt+Shift+- (zoom out). Turn on View > Appearance > Presentation Assistant: it shows the name of each action you use, so the interviewers can follow you.

Clipboard: copy and paste inside the remote machine always works. Copying between your Mac and the remote machine depends on their settings. Do not plan to paste anything from your Mac.

## IntelliJ on Windows: small differences

- The main menu may be hidden behind the hamburger button. Alt+\ opens it.
- The terminal (Alt+F12) is PowerShell. Run the wrapper as `.\mvnw.cmd test`.
- "Debug the test under the cursor" has no default shortcut on Windows. Use the green arrow in the gutter > Debug.

## Rehearsing the keys on a Mac

IntelliJ on macOS hides the "Windows" keymap. Two options:
- Switch to the **IntelliJ IDEA Classic** keymap (Settings > Keymap). Its F-keys match Windows (Shift+F10 Run, F8 Step over, Alt+F8 Evaluate), but it uses Command where Windows uses Ctrl.
- Or keep your keymap and drill the Windows column of the table in `intellij-debugging.md` for 10 minutes.

Switch back after the interview.

## First five minutes of the session

1. Type `@ # { } [ ] | \ < > / ;` in IntelliJ. If any character is wrong, check the Mac input source and reconnect.
2. Press Ctrl+Shift+A, Ctrl+B and F8 once each, and Alt+Enter with the left Option key.
3. Ask: "Which AI tools can I use? Is the internet open? Is the clipboard enabled? Should I share the whole screen or only this window?"
4. Check the project: the JDK is set, Maven is synced, one test runs.

## If something goes wrong

- A shortcut does nothing: use Find Action (Ctrl+Shift+A) or the menu. Do not fight the keyboard in front of them.
- Wrong characters: check the Mac input source, then reconnect.
- Disconnected: say so on Zoom and reconnect.
