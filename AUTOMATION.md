# EasyGUI Automation

A file-driven automation channel that lets scripts — and especially AI agents — drive EasyGUI screens, simulate input, and capture screenshots plus widget-tree dumps, closing the visual feedback loop without a human at the keyboard.

## Enabling

Automation is active automatically in **development environments** (`gradlew :fabric:runClient` / `:neoforge:runClient`). In a production launch it stays off unless the JVM flag `-Deasygui.automation=true` is set.

While active, the game watches `<gameDir>/easygui-automation/` (created on startup; in dev runs the game dir is `fabric/run/` or `neoforge/run/`).

## Protocol

1. Write commands (one per line) to `easygui-automation/script.txt`.
2. The game picks the file up within a tick, deletes it, and executes **one command per client tick** — so every state change is rendered for at least one frame before the next command runs.
3. When the script finishes (and all screenshots have flushed to disk), `result.json` is written with a per-command status. Poll for that file.

Outputs land in the same folder: screenshots under `easygui-automation/screenshots/`, dumps as `easygui-automation/<name>.json`.

## Commands

| Command | Effect |
|---|---|
| `open <name>` | Open a registered screen (the library registers `demo`) |
| `close` | Close the current screen via its normal close path |
| `theme dark` / `theme light` | Swap the default theme (applies to the open EasyScreen too) |
| `move <x> <y>` | Move the virtual mouse (drives hover states on EasyScreens) |
| `click [x y] [button]` | Click at coordinates (or at the virtual mouse if omitted); button 0/1/2 |
| `click #<id> [button]` | Click the center of the widget with that `setId(...)` id |
| `drag <x1> <y1> <x2> <y2> [button]` | Press, drag in 8 steps, release (sliders, reorder lists) |
| `scroll <amount>` | Scroll at the virtual mouse position (positive = up) |
| `key <name>` | Press a key: `enter`, `escape`, `tab`, `backspace`, `delete`, `space`, arrows, `home`, `end`, `pageup`, `pagedown`, single characters, or a raw GLFW code |
| `type <text>` | Type literal text into the focused widget |
| `wait <ticks>` | Wait N client ticks (20 ticks = 1 second) — use after anything animated |
| `guiscale <n>` | Set the GUI scale and resize (deterministic output across machines) |
| `screenshot <name>` | Capture the last rendered frame to `screenshots/<name>.png` |
| `dump <name>` | Write the current widget tree to `<name>.json` |

Lines starting with `#` are comments.

## Example

```
# Capture the demo's Type tab in both themes
theme dark
open demo
wait 15
dump tree
screenshot demo-dark
theme light
open demo
wait 15
screenshot demo-light
close
```

The `dump` output is the key to precise interaction: it lists every widget's type, id, bounds, visibility, enabled/focused/hovered state, and content props (text, values, selection). An agent's loop is typically: `open` → `dump` → read the JSON to find the target's bounds → `click x y` → `wait` → `screenshot`, then read the PNG.

## Registering your own screens

```java
EasyAutomation.registerScreen("settings", MySettingsScreen::new);
```

Give important widgets stable ids so scripts survive layout changes:

```java
panel.add(new Button("Save").setId("save"));
// script: click #save
```

## Notes and limitations

- The virtual mouse only affects **EasyScreens** (hover rendering); clicks/keys/scroll dispatch through the vanilla `Screen` interface and work on any screen.
- `theme` swaps the default theme and re-themes the open EasyScreen; screens built with a custom `setTheme(...)` keep their own.
- Screenshots capture the previous completed frame; after `open` or anything animated, `wait 10`–`15` first (the screen pop-in animation takes ~6 ticks).
- Screenshot writing is asynchronous; `result.json` is only written after all pending screenshots are flushed, so its presence means all files are ready.
- One command executes per tick, so a 10-command script takes ~0.5s plus explicit waits.
