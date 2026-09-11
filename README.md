# BOSS Run Ledger

A sidebar panel that records what produced each result.

Launch a command from the panel and the ledger stores the command, the commit it ran against, the
uncommitted diff it actually used, the environment variables you named, the exit code, and the
output files, copied out of scratch directories before anything cleans them.

Built for the person who runs the same script forty times with different numbers and has to be able
to say, weeks later, which run produced the figure in the write up.

## The problem

A research checkout is almost never clean. You edit a learning rate, run the script, get a number,
edit it again. The outputs land under `outputs/` or `/tmp` or a scratch volume. Some time later the
machine reboots, the scratch directory is cleaned, and the number in your notes has nothing behind
it. You know roughly what you ran. You cannot prove it.

Recording a commit hash does not fix this. On a dirty tree the commit describes source that the run
never saw, so a ledger that stores only the hash quietly misattributes every result produced from
uncommitted work, which is most of them.

## What it records

For each run, under `<project>/.boss/run-ledger/`:

| What | Where |
|---|---|
| Command, cwd, start and finish, exit code | `runs.jsonl` |
| Commit, branch, and whether the tree was dirty | `runs.jsonl` |
| The uncommitted diff the run actually used | `<run-id>/working-tree.patch` |
| Named environment variables | `runs.jsonl` |
| stdout and stderr | `<run-id>/console.log` |
| Declared output files | `<run-id>/<their relative path>` |

`git checkout <sha> && git apply working-tree.patch` reconstructs the source a run used. The patch
carries tracked files only, which is a real limit of `git diff`, so untracked paths present at
launch are listed by name in the patch header rather than being silently missing.

## Design notes

**The ledger is append only.** A run is written once when it starts and once when it ends, and the
two lines are folded by id with the last write winning. A host killed mid run therefore leaves a row
marked `running`, which is true, instead of losing the run. A line truncated by a hard kill can only
ever be the last line, and the reader skips unreadable lines and counts them in the panel rather
than failing to load.

**Runs are child processes, not commands typed into a terminal.** A terminal is nicer to watch, but
it cannot report the exit code or the moment a command finished, and without both there is no
instant at which to rescue artifacts and nothing honest to put in the status column. Console output
is teed to `console.log`, so nothing is lost to the choice.

**Artifact rescue is built for the failure cases.** There is a per run byte budget, so one forgotten
checkpoint directory cannot fill the disk, and an over budget file is reported rather than dropped
in silence. Copies land on a temporary name and are then moved, so an interrupted rescue never
leaves a truncated file that reads as a real artifact. Symbolic links are not followed, so a link
pointing outside the project cannot pull unrelated files into the ledger.

**Only the environment variables you name are stored.** Capturing the whole environment would put
credentials into a file that tends to end up committed.

## Build

Needs a JDK 17 toolchain.

```bash
./gradlew test buildPluginJar
```

That produces `build/libs/boss-plugin-run-ledger-<version>.jar`. Locally the build compiles against
the sibling `../boss-plugin-api` checkout. CI sets `CI=true` and downloads the released API jar
instead.

## Install for local testing

```bash
cp build/libs/boss-plugin-run-ledger-0.1.0.jar ~/.boss_debug/plugins/
rm -rf ~/.boss_debug/plugin-cache/ai.rever.boss.plugin.dynamic.runledger
```

Then restart the dev host. Plugins load at startup.

## Access and data

Worth stating plainly, because this plugin runs commands and reads the environment.

| What it touches | Detail |
|---|---|
| Runs | A child process per launch, with the command you typed, in the project directory |
| Writes | `<project>/.boss/run-ledger/` only. Nothing outside the open project |
| Reads | Only the environment variables you name per run. Never the whole environment |
| Network | None. No external service, no telemetry, nothing leaves the machine |
| Permissions | None declared. It uses no host provider beyond the project path and the plugin scope |

The environment rule is deliberate. Capturing everything would be the easy default and would put
credentials into a file that tends to get committed, so the plugin records only what you ask for by
name, and the panel says so above the field.

The working-tree patch it stores is a `git diff` of the project. If your uncommitted work contains a
secret, the patch will too, exactly as the repository would.

## Compatibility

| | |
|---|---|
| Built and tested against | `boss-plugin-api` 1.0.88 locally, 1.0.89 in CI |
| Declared `minApiVersion` | 1.0.73 |
| Declared `minBossVersion` | 9.4.2 |
| Operating systems exercised | macOS only |
| JDK | 17 |

The Windows branch of the launcher (`cmd.exe /c`) is written but never run. Linux is untested.

## Verification

Forty tests, green locally and in CI on Ubuntu. They cover the ledger format, provenance capture,
artifact rescue, the launcher and the panel state, including crash-truncated ledger lines, forward
compatible reads, budget exhaustion across globs, a symlink pointing outside the project, a detached
HEAD, and a real subprocess whose output is then rescued.

**Not verified, and worth knowing before reviewing:**

- **It has never been loaded into a running BOSS host.** `minBossVersion` is declared from the
  manifest of a current plugin, not from a launch I performed. The panel compiles against the API
  and follows the shape of `git-status` and `run-configurations`, but its theme, slot and layout are
  unseen.
- **No demo recording.** I can build and test it but cannot run the desktop host here, so there are
  no screenshots. I would rather say that than stage something.
- **Windows and Linux are untested**, as above.

## Ownership

Written by Arjun Singla (`arjun28115`). No other collaborators. No third-party code is vendored; the
dependencies are the ones the plugin template declares, plus `kotlinx-serialization-json` for the
ledger format.

## Status

Version 0.1.0.

Not yet done, and deliberately left out of the first version: comparing two runs side by side, and
an MCP tool so an agent can read the ledger.

## Licence

Apache 2.0, matching BOSS Console.
