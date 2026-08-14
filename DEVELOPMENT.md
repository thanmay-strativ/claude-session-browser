# Claude Session Browser — Development Notes

Everything needed to pick this project up cold: what it is, what it contains, how every
feature works, how to build/install/release it, what broke along the way and why, and the
traps that are specific to this codebase.

Current version: **1.1.19**. Target IDE: **PyCharm 2026.2** (built against IC 2025.2).

> Under git since `4a44699` ("initial commit of Claude Session Browser 1.1.19"), on `main`,
> tagged `v1.1.19`, published at
> <https://github.com/thanmay-strativ/claude-session-browser>. Every release is an annotated
> tag, so `git describe --tags` tells you which build a working tree corresponds to.

---

## 1. What this is

Two components shipped as one plugin:

| Component | Language | Where it lives | Role |
|---|---|---|---|
| IDE plugin | Kotlin 2.1, JVM 21 | `src/main/kotlin/` | Tool window, transcript viewer, stats, resume |
| `claude-session-cache` | Python 3.11+ | `mcp-server/` | SQLite+FTS5 index of every session, exposed to Claude over MCP |

The Python package is **bundled inside the plugin jar** at build time (`bundleMcpServer`
task in `build.gradle.kts`) and extracted to the user's home on first enable, so a shared
zip works on a machine that has never seen this checkout. Sources stay in `mcp-server/` —
one source of truth, copied in at build time.

### On-disk locations (all outside the IDE config)

| What | Path |
|---|---|
| Extracted MCP server | `~/.claude-session-browser/mcp-server` |
| Its venv entry point | `~/.claude-session-browser/mcp-server/.venv/bin/claude-session-cache` |
| Bundle fingerprint | `~/.claude-session-browser/mcp-server/.bundle-version` |
| Cache database | `~/.claude-session-cache/sessions.db` |
| Titles / pins / tags / environments | `~/.claude-session-browser/metadata.json` |
| Daily refresh agent | `~/Library/LaunchAgents/com.mahadi.claude-session-cache.plist` |
| Claude transcripts (read-only) | `<configDir>/projects/*/*.jsonl` |

Plain JSON for the metadata is deliberate: both the Kotlin plugin and the Python package
read the same file, so tags set in the UI become searchable dimensions in MCP.

---

## 2. Repo layout

```
build.gradle.kts                 version, IntelliJ platform deps, MCP bundling task
updatePlugins.xml                 custom repository descriptor for in-IDE auto-update (§5)
src/main/resources/META-INF/plugin.xml   description + change-notes (bump with the version)
src/main/kotlin/com/mahadi/claudesessions/
  ui/SessionBrowserPanel.kt      1057  tool window: toolbar, tree, painted row renderer, context menu
  ui/StatsView.kt                 664  stats dialog + all chart components
  ui/SessionSettingsDialog.kt     349  environment (account) editor
  ui/DesignSystem.kt              299  validated palette, Card, Chip, RoleBadge, StatTile, bar primitives
  ui/TranscriptView.kt            285  read-only conversation viewer
  McpRuntime.kt                   319  extract/install/upgrade the Python server, launchd agent
  SessionMetadataStore.kt         291  metadata.json read/write, environments, active account
  UsageStatsService.kt            189  per-account usage from transcripts (14-day window)
  SessionResumer.kt               182  opens claude --resume in a terminal tab
  ClaudeSessionScanner.kt         150  transcript -> ClaudeSession list
  ClaudeTranscriptReader.kt       136  transcript -> ordered TranscriptEntry list
  CacheStatsService.kt            108  reads the Python CLI's JSON stats
  McpRegistrationService.kt       105  claude mcp add/remove, per-account config file
  SessionTags.kt                   80  derived (automatic) tags
  SessionAutoTagger.kt             66  optional AI tag suggestions via claude -p
  JsonAccess.kt                    32  shared null-safe Gson accessors
mcp-server/src/claude_session_cache/
  queries.py                      450  all reads incl. cache_stats, account scoping
  ingest.py                       361  incremental indexing with watermarks
  parser.py                       276  transcript parsing
  server.py                       216  MCP tool definitions
  db.py                           182  schema, migrations, indexes
  metadata.py                      90  reads the shared metadata.json
  cli.py                           73  ingest / search / stats / serve
  redact.py                        52  secret redaction on ingest
```

Total: ~4,700 lines Kotlin, ~1,700 lines Python.

---

## 3. Features

### 3.1 Session list (tool window, left)

- Scans the **active account's** session directory, groups by project, pins the currently
  open project to the top. Pinned sessions sort above the rest, then newest first.
- **Two-line painted rows** (`SessionRowRenderer`, a hand-painted `TreeCellRenderer`):
  - Line 1: play button · pin dot (if pinned) · title (ellipsised)
  - Line 2: relative time · model · message count · **branch chip** · tag chips
- **Play button at the row start** resumes the session without opening it. Its hit target
  is the leading 24px strip. See §7 for why it is leading and not trailing.
- The **branch is never dropped**: it is drawn before tags and shortens
  (`feature/PROJ-1234-new-thing` → `PROJ-1234-new-thing` → ellipsis) rather than
  disappearing. Tags fall off the end instead. Detached `HEAD` is shown, not hidden.
- Row tooltip: first prompt (XML-escaped) + full branch + interaction hint.
- Search matches **title, first prompt, project name, project path, git branch, and all
  tags** (manual + derived). Tick **Content** to also grep message text (slower, threaded,
  generation-counted so stale results are discarded).
- Filters: All / Today / This week / Pinned / Untagged.
- Context menu: open, continue, rename, reset title, edit tags, suggest tags (AI), pin,
  export Markdown, copy id, copy resume command, reveal in Finder, delete (single or
  multi-selection).
- Auto-refresh every 30s (toggleable); stops when the panel is not displayable.

### 3.2 Transcript viewer (editor tab, on double-click)

- Header: title (capped width so it can't slide under the button), metadata chips
  (project, date, message count, model, branch, up to 4 tags), **Continue session**.
- **Files touched** is a searchable dropdown (`Files touched (12)`): file name in front,
  directory greyed behind it. Enter/click copies the **full path**; ⌘-click several to copy
  them newline-separated; type to filter.
- Messages as cards. Roles are distinguished by surface, rail and badge:
  - **YOU** — blue badge + user icon, accent-washed card, 3px accent rail
  - **CLAUDE** — green badge + lightning icon, plain card surface
  - thinking / tool / result — grey chip, code surface, monospace
- Colour is the badge fill and the icon, never the label text — small text in a mid-tone
  hue misses 4.5:1 contrast on every surface it sits on.

### 3.3 Stats dialog

| Section | Contents | Source |
|---|---|---|
| Overview | Sessions · Messages · Active days /30, account name | in-memory session list |
| Activity — last 14 days | daily column chart, peak labelled, blank days keep their slot; caption with busiest day, days worked, week-on-week change | transcript scan |
| Usage — last 7 days, per account | one card per account: meter, sessions/messages, change vs previous 7 days, prompt/output/cache-read tokens | transcript scan |
| Claude memory (MCP) | coverage meter, commits · files touched · sessions with commits, cache freshness, registration state, awaiting-ingest, tagged/subagent/redacted counts | Python CLI |
| Heaviest sessions | top 5 by message count | in-memory |
| By project / By model / Top branches / Top tags | bar charts; **By model** collapses to a one-line share under 3 models | in-memory |
| Files you return to | top files by number of sessions touching them | Python CLI |
| Tool use | tool calls, **MCP tools grouped by server** | Python CLI |
| Housekeeping | sessions with ≤2 messages (bulk-deletable), never-tagged count | in-memory |

Everything except the usage/activity sections describes the **selected account only**. Usage
reads every account and keeps them in separate cards — there is no combined total.

Charts carry hover tooltips. Bars use one hue: each chart is a single series, so colour
carries no identity and every row is directly labelled instead.

### 3.4 Multiple Claude accounts ("environments")

An environment is `{name, sessionRoot, claudeBinary?, configDir?}`. The dropdown switches
between them; the gear button edits them.

The key insight: a second account is normally **not** a second binary. `claude-work` on this
machine is a **zsh function** that sets `CLAUDE_CONFIG_DIR` — a shell function can never be
launched through `ProcessBuilder` (no `.zshrc` is sourced). So an account is modelled as a
**config directory**, and `CLAUDE_CONFIG_DIR` is threaded into every subprocess: resume,
AI tagging, and `claude mcp add`.

The active environment's `sessionRoot`/`claudeBinary` are **mirrored onto the legacy
top-level keys** in `metadata.json` on every save. That is what lets the Python side read
only those two keys and stay ignorant of environments entirely.

### 3.5 MCP session search

One tick of **MCP** does everything: extract the bundled server, find a suitable Python
(probed, not assumed — macOS ships 3.9 which is too old), create a venv, install the
package, index the history, register with Claude Code, and schedule a daily refresh at
03:00 via a launchd agent generated from *this* machine's paths (never a checked-in plist
carrying someone's username). Un-ticking unregisters and removes the agent.

Both accounts' sessions accumulate in one database, but **every query is scoped to the
selected account** via each session's `source_root` column. Pass `all_accounts=true` to
reach across them deliberately. Secrets are redacted **on ingest**, not on read — a secret
that never enters the database cannot leak out of it.

### 3.6 Automatic tags

Two tiers, and the free one is the default:

**Derived (free, instant, no AI).** Computed on read, never written to `metadata.json` —
persisting them would freeze a branch's ticket id into a session that later moves, and mix
machine guesses into a curated list. So "Untagged" still means *you* haven't tagged it.

- **Ticket id** from the branch, then title, then the first 60 chars of the prompt
  (`feature/PROJ-1234-new-thing` → `#proj-1234`)
- **Topics** from the **title only**: bugfix, tests, migration, refactor, review, deploy,
  ui, api, docs, performance, setup, debug

**AI (paid, manual).** *Suggest tags (AI)* in the context menu — one cheap Haiku call with
only the title and first prompt, never the transcript.

### 3.7 Resume in a terminal

`SessionResumer` opens `claude --resume <id>` in the **reworked** terminal, because that is
what the `+` button creates and it draws with its own colour scheme. Everything reachable
from `TerminalToolWindowManager` — including `createShellWidget` and the no-argument
`createNewSession` — hardcodes `TerminalEngine.CLASSIC`, which paints from the console
colour scheme and therefore looks unlike every other tab (the "blue terminal" bug).

The reworked API lives in a **plugin content module** (`intellij.terminal.frontend`) with
its own classloader, so it is reached reflectively via a classloader borrowed from one of
that module's actions. A `<module>` dependency would be mandatory and would stop the plugin
loading on IDEs without it. Classic remains the fallback; clipboard copy is the last resort.

---

## 4. Design system (`ui/DesignSystem.kt`)

### The palette is computed, not chosen

Both sets pass an OKLCH validator on the lightness band for their own surface, the chroma
floor, colour-vision separation, and 3:1 contrast.

| Role | Light | Dark |
|---|---|---|
| Data / accent | `#3B82F6` | `#3D7DD6` |
| Good (status) | `#0E9F6E` | `#35A382` |
| Attention (status) | `#B45309` | `#BE8B1E` |

Recorded evidence, so this can be re-checked rather than re-argued:

- The **previous** palette failed hard: `#3B82F6` vs `#8B5CF6` was **ΔE 1.3 under
  deuteranopia** — the same colour to a red-green colourblind reader.
- Dark mode has its **own band** (OKLCH L **0.48–0.67**), narrower and darker than light's
  0.43–0.77. Every Darcula-bright value (`#589DF6`, `#4EC9B0`, `#E8B339`) sits outside it.
  Dark steps are *selected*, never an automatic lightening of the light ones.
- Blue↔green is a validated adjacent pair (worst case ΔE 10.6 protan, 18.3 normal), which
  is why the transcript can use them for the two speakers.

**One data hue.** Every chart here plots a single series, so hue variety would be pure
decoration. Green/amber are reserved for state and always ship next to a word. The one
deliberate exception: green marks Claude's turns in the transcript, where nothing is plotted
and there is no status mark to confuse it with.

### Mark specs

- Bars: **rounded data end, square foot on the baseline** (`fillBarFromLeft` /
  `fillColumn`). Rounding both ends detaches short bars from the origin.
- Columns capped at 24px with a 2px surface gap; blank days keep their slot and show a stub.
- Only the **extreme** is labelled directly; the rest live in tooltips.
- Text always wears ink tokens, never a series colour.
- Components: `Card` (rounded surface, optional rail), `StatTile` (figure + accent rail),
  `Chip` (pill, optional colour dot), `RoleBadge` (filled pill + icon), `MeterBar`,
  `HorizontalBarChart`, `DailyActivityChart`.

---

## 5. Build, install, release

### Every iteration

```bash
cd ~/Documents/claude-session-browser
export JAVA_HOME=/opt/homebrew/opt/openjdk@21     # JVM 21 is required

./gradlew compileKotlin                            # fast feedback loop
./gradlew clean buildPlugin                        # -> build/distributions/claude-session-browser-<version>.zip
```

### Install into the running IDE

```bash
PLUGINS="$HOME/Library/Application Support/JetBrains/PyCharm2026.2/plugins"
rm -rf "$PLUGINS/claude-session-browser"
unzip -q -o build/distributions/claude-session-browser-<version>.zip -d "$PLUGINS"
ls "$PLUGINS/claude-session-browser/lib/"          # confirm the new version's jar is there
```

Then **restart the IDE** — plugin classes are loaded once at startup, so an install without
a restart changes nothing. `rm -rf` first is not optional: leaving the old directory in
place means two versioned jars in `lib/` and undefined class loading.

### Release checklist

Work top to bottom. Steps 1–2 must land in the same edit, and step 7 must not be skipped —
an unreleased tag is worse than none, because `git describe` then lies about what a tree is.

**1. Bump the version.** `build.gradle.kts` → `version = "1.1.X"`. This names the zip, so
nothing else needs to know the number.

**2. Write the change-notes.** `src/main/resources/META-INF/plugin.xml` → a new
`<h4>1.1.X</h4>` block at the *top* of `change-notes`, four to eight short bullets in the
voice of the existing entries (what changed for the user, not which class moved). Update
`description` too if a user-visible feature was added or reworded.

**3. Build clean.**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew clean buildPlugin        # -> build/distributions/claude-session-browser-1.1.X.zip
```

`clean` is not optional: without it a stale jar from the previous version can survive in the
distribution directory and end up shipped alongside the new one.

**4. Verify the artifact.** Run the block in *Verifying an artifact before sharing* below —
`.py` count, no `__pycache__`, no dev paths, correct version stamp, control assertion passing.

**5. Install and restart**, then actually exercise what changed. If the change touched
`mcp-server/`, confirm the self-update landed in all three places (source copy, venv copy,
live CLI — see *The Python is self-updating*).

**6. Update the docs if behaviour changed.** `README.md` for anything a user would notice,
this file for anything a maintainer would trip over. Add a row to *Version history* (§6).

**7. Commit and tag.**

```bash
git add -A
git commit -m "feat: <what changed>"        # or fix:/chore:, one line + bullets
git tag -a v1.1.X -F -                      # annotated: carries message, tagger, date
git describe --tags                          # must print v1.1.X
git show v1.1.X:build.gradle.kts | grep '^version'   # must print the same 1.1.X
git push -u origin main --follow-tags       # plain `git push` leaves tags behind
```

The tag message should say what the release contains **and** name the zip it matches — that
line is the only thing connecting a distributed build to its source.

**8. Publish the release and point the update-site at it.**

```bash
gh release create v1.1.X build/distributions/claude-session-browser-1.1.X.zip \
  --title "v1.1.X" --notes "<match the change-notes bullets>"
```

Then bump `version` and `url` in `updatePlugins.xml` (repo root) to match, and commit that
change to `main`. This is the file anyone's PyCharm reads for **Check for Updates** — see
*Automatic updates* below. Skipping this step means the release exists but nobody's IDE will
ever be offered it.

**9. Hand over** `build/distributions/claude-session-browser-1.1.X.zip`. First-time
installers use *Settings → Plugins → ⚙ → Install Plugin from Disk…* and restart; anyone who
already added the update-site repository (README, *Get automatic updates*) gets this release
through PyCharm's own updater instead.

### Verifying an artifact before sharing

Always include a **control assertion** — a string known to be present must match — so a
silently failing grep cannot read as "clean":

```bash
ZIP=build/distributions/claude-session-browser-1.2.0.zip
unzip -p "$ZIP" "claude-session-browser/lib/claude-session-browser-1.2.0.jar" > /tmp/csb.jar

unzip -l /tmp/csb.jar | grep -c "mcp-server.*\.py"      # expect 10 (sync.py since 1.2.0)
unzip -l /tmp/csb.jar | grep -c "__pycache__"           # expect 0
mkdir -p /tmp/csbcheck && unzip -q -o /tmp/csb.jar "mcp-server/*" -d /tmp/csbcheck
grep -rn "/Users/" /tmp/csbcheck | wc -l                # expect 0 — no dev paths shipped
grep -rl "claude_session_cache" /tmp/csbcheck | wc -l   # CONTROL: must be > 0
unzip -p /tmp/csb.jar META-INF/plugin.xml | grep -m1 "<h4>"   # version stamp
```

### The Python is self-updating (important)

`McpRuntime.install()` is idempotent and does `pip install --upgrade`, but for a long time
**nothing re-ran it**: the enable path only calls it when the server is *not* installed. The
package installs non-editable, so copying files into the install dir does not update the
venv either. Result: a server installed once stayed at that version through every later
plugin update, and any new query the plugin asked for silently returned nothing.

Now `McpRuntime.isStale()` compares a **SHA-256 over the bundled Python's contents** (not
the manifest — editing a query changes no filenames) against `.bundle-version` on disk, and
`SessionBrowserPanel.upgradeMcpIfStale()` re-runs the install in the background when the
panel opens. Silent unless it fails.

**So: after changing anything in `mcp-server/`, a plugin reinstall + IDE restart is enough.**
Verify it landed:

```bash
MCP="$HOME/.claude-session-browser/mcp-server"
grep -c "<new symbol>" "$MCP/src/claude_session_cache/queries.py"
grep -c "<new symbol>" "$MCP"/.venv/lib/python*/site-packages/claude_session_cache/queries.py
"$MCP/.venv/bin/claude-session-cache" stats | python3 -m json.tool | head -20
```

All three must agree — the source copy, the venv copy, and the live CLI.

### Automatic updates (custom plugin repository)

The plugin does not check the network for its own updates in code — no custom downloader,
no reflection into internal Plugin Manager classes (the terminal saga in §7.1 is exactly the
kind of trap that would invite). Instead it rides PyCharm's own update machinery via a
**custom plugin repository**: a small XML descriptor, `updatePlugins.xml` at the repo root,
hosted at

```
https://raw.githubusercontent.com/thanmay-strativ/claude-session-browser/main/updatePlugins.xml
```

Anyone who adds that URL once under *Settings → Plugins → ⚙ → Manage Plugin Repositories*
(README, *Get automatic updates*) gets this plugin folded into PyCharm's normal **Check for
Updates** — found, downloaded, verified and installed on restart, same as a Marketplace
plugin.

Keeping it working after every release is step 8 of the checklist above: bump `version` and
`url` in `updatePlugins.xml` to the new tag and zip, and make sure that exact zip is attached
to the matching GitHub Release — `url` must point at a release asset, not the repository
itself. `since-build`/`until-build` in the descriptor should track `build.gradle.kts`'
`ideaVersion` block.

---

## 6. Version history

| Version | Change |
|---|---|
| 1.1.3 | Environment dropdown: switch Claude accounts, add/remove, per-account binary |
| 1.1.4 | `CLAUDE_CONFIG_DIR` per account; resume/tag/MCP all act as that account; per-account MCP toggle |
| 1.1.5 | Daily refresh agent auto-installed; per-account search scoping; account breakdown |
| 1.1.6–1.1.11 | The terminal-appearance arc (see §7.1) — ended with the reworked terminal reachable via a borrowed classloader |
| 1.1.12 | Redesigned session list + transcript; 7-day usage per account; token counts; top branches; colour-blind-safe palette |
| 1.1.13 | Automatic tags; ticket id from branch; files-touched dropdown; copy paths; search matches branches |
| 1.1.14 | Resume arrow clears the scrollbar; files-touched button layout; YOU/CLAUDE badges; stronger YOU background |
| 1.1.15 | Search field states its own scope |
| 1.1.16 | Play button moved to the row start — clear of the scrollbar, bigger, and actually working |
| 1.1.17 | Daily activity chart; week-on-week change; active days; heaviest sessions; files you return to; commits + cache freshness; housekeeping; filler tiles dropped; **session cache self-updates** |
| 1.1.18 | Tool use split, MCP tools grouped by server |
| 1.1.19 | Branch always visible on a row; shortens instead of disappearing; detached `HEAD` shown |
| 1.2.0 | **Team knowledge base**: schema v4 `owner` column; `export`/`import`/`sync` CLI (per-session JSONL in a private git repo, tombstones retract Mark-private sessions); `scope` on the MCP search tools; twice-daily launchd sync agent; tabbed settings with Team Sync; Health tab (launchd state, per-account MCP, tools); MCP registration covers every account; auto-tagger stdin/stderr fix |

---

## 7. Bugs found, and their actual root causes

Kept because each one cost real time and none of them was the obvious explanation.

### 7.1 The "blue terminal" (1.1.6 → 1.1.11)

Resumed sessions opened in a differently-coloured terminal with fewer features. Four wrong
fixes preceded the right one. The real chain, established from bytecode:

- `TerminalOptionsProvider$State` defaults to `REWORKED`, but `createShellWidget` and the
  no-arg `createNewSession()` **hardcode CLASSIC**.
- Classic paints from the console colour scheme; reworked has `ReworkedTerminalColors`.
- The reworked implementation ships in
  `plugins/terminal/lib/modules/intellij.terminal.frontend.jar` — a content module with its
  **own classloader**, so the Terminal plugin's main loader raises `ClassNotFoundException`.

Two methodological lessons, both mine: I inferred from API shapes instead of reading the
working example on the machine, and I twice concluded "this build has no reworked terminal"
without searching `plugins/terminal/lib/**modules**/`. The failure reason was in `idea.log`
the whole time.

### 7.2 Resume arrow: overlapping *and* not working (1.1.14 → 1.1.16)

Two independent bugs wearing one symptom:

- The IDE draws **overlay scrollbars on top of the viewport**, so `visibleRect.width` still
  includes the strip the scrollbar covers. An arrow at the right edge sat under it.
- The painter used a width computed fresh on each render; the click handler used
  `bounds.width` from the tree's **cached layout**. Those update at different times, so
  after a resize the target could sit somewhere other than the arrow. "Both read the same
  row width" was wrong — they read it at different *moments*.

Fixed by anchoring to the row's **start** (`bounds.x`, the indent), which both the painter
and the hit test get from the same place and no resize can shift.

### 7.3 `claude-work` is not a binary

The environment editor rejected `claude-work` as "not an executable file". Correct: it is a
**zsh function** in `~/.zshrc`. The whole "second account = second binary" model was wrong;
see §3.4. This also surfaced a latent bug — `claude mcp add` and the MCP checkbox both read
`~/.claude.json`, so on the work account the toggle was reading and writing the *personal*
account's config.

### 7.4 `no such column: source_root`

A new `CREATE INDEX` was placed in `_SCHEMA`, which runs **before** the `ALTER TABLE`
migrations. It passed on a fresh database and broke every existing one. Indexes now live in
`_INDEXES`, executed *after* `_apply_version_migrations`.

### 7.5 Tags fired on the wrong sessions

Keyword matching by substring missed `"how to fix?"` (`"fix "` needs the trailing space to
avoid false hits, which fails at end-of-string) → switched to whole-word patterns. Then
scanning the opening prompt as well lifted coverage 34% → 42% but tagged **"Write standup
update"** and **"Plan remaining work for today"** as `#api`, off a passing mention. Dropped
prompt-scanning: one wrong tag makes filtering by tag untrustworthy, while a missing one
costs nothing you can't add by hand.

### 7.6 Sandbox tooling lied

`ps`/`pgrep` failed to see a running PyCharm (osascript/System Events worked); `lsof` showed
jars from a dead process holding deleted files; log rotation made a version check look
empty; the shell rewrites `grep` to ripgrep so `\|` alternation breaks (use `-E`).

---

## 8. How things were verified

Since the UI could not be screenshotted from the working environment, logic was validated
against **real data** instead of assumed:

- **Palette** — run through an OKLCH validator, not eyeballed. Numbers in §4.
- **Per-account scoping** — proven on a copy of the real 73MB cache: migration attributed
  640/640 sessions with 0 unattributed; after ingesting the work account, 0 rows leaked into
  the personal account's queries, while `all_accounts=true` returned 50 hits.
- **Tag rules** — replicated in Python over 60 real sessions before being written in Kotlin;
  this is what caught the `#api` false positives.
- **Activity/delta** — replicated over the real transcripts: 6,277 messages this week vs
  7,382 the previous week (−15%), 13 of 14 days worked, peak 3,167 on Aug 2, one blank day.
- **Branch availability** — 34/40 recent sessions have a real branch, 5 detached `HEAD`,
  1 absent. This is what revealed that hiding `HEAD` looked like a bug.
- **New SQL** — run through the real `cache_stats()` function against a copy of the live
  88MB database, not just as raw SQL.
- **Shipped artifacts** — every release grepped for dev-path leaks with a passing control
  assertion.

Representative current numbers: 462 primary sessions · 70,518 messages indexed ·
2,211 files touched · Bash 10,441 / Read 7,644 / Edit 5,999 calls ·
the most-revisited single file appeared in 57 sessions.

---

## 9. Gotchas for future work

- **`JBUI.scale()` everything.** Raw pixel values break on HiDPI.
- **Variable tree row heights** need `tree.rowHeight = 0`, and the tree **caches** row
  sizes. A `ComponentListener` re-sets `rowHeight` on resize to invalidate that cache
  without disturbing expansion state.
- **`getPathBounds().width` is the cached layout width**, not what the renderer just
  painted with. Never derive a hit target from it — anchor to `bounds.x`.
- **Overlay scrollbars** overlap content; reserve `verticalScrollBar.width` with a floor.
- **`BorderLayout.WEST` keeps its full preferred width**, so unbounded content there slides
  under an `EAST` component. Cap `maximumSize` on labels that can grow.
- **`FlowLayout` under-reports height when it wraps** — bound the item count instead of
  relying on wrapping.
- **Non-public bridge classes**: `TerminalWidget` is implemented by
  `JBTerminalWidget$TerminalWidgetBridge`; reflecting on the impl fails with an access
  error. Dispatch through the public interface.
- **Gson + Kotlin**: a data class with *all* parameters defaulted gets a synthetic no-arg
  constructor, so Gson applies defaults instead of Unsafe-allocating. Verify with `javap -p`
  when adding fields to a persisted class.
- **`uv`-created venvs have no `pip`** — use `uv pip install --python .venv/bin/python .`.
- **Never write `AllIcons` names from memory** — compile to confirm they exist.
- **Adding a Python query** means: `queries.py` → `cache_stats` dict key → Kotlin parse
  (tolerate absence) → UI section → reinstall → restart → verify all three copies (§5).

---

## 10. Known limitations / open items

- **No visual verification.** Geometry throughout was reasoned about, not observed —
  `screencapture` was unavailable in the working environment. Colour and contrast are
  validated numerically; label collisions and spacing are not.
- **Derived vs manual tags look identical** in the row chips. Distinguishing them (e.g. a
  dot on manual ones) is unbuilt.
- **AI tagging is per-session only.** No bulk "suggest tags for selection" — deliberately,
  since running it across hundreds of sessions spends real money silently.
- **No cost estimate in stats.** Tokens are collected, but hardcoded prices go stale and on
  a subscription token counts are not dollars. A confidently wrong money figure is worse
  than none.
- **Tool-use split is all-time**, not windowed — that is what the cache gives cheaply.
- **Sessions with no `gitBranch` field at all** show nothing in that slot (1 of 40 recent).
- **The repo is public**, so anything committed is published. Examples in these docs use
  placeholders (`/Users/YOUR_USERNAME`, `PROJ-1234`) rather than real paths or ticket ids —
  keep it that way. Commits are authored under a GitHub noreply alias, not a real address.
- The 30s auto-refresh re-scans every transcript; fine at ~460 sessions, unmeasured at
  several thousand.
