# The 2026-09-30 reorganisation

Before this date, Firepit was spread over three folders:

| Folder | What it held |
|---|---|
| `~/Projects/Meshtastic/Firepit` | **The git repository** (github.com/sarmadjari/Firepit, 93 commits): the Android app, docs, protos, brand, design |
| `~/Projects/Firepit` | **The iOS app, never committed**, plus the iOS scripts and stale copies of the Android repo from 9 Sep: docs, protos with an old primary key, and an Android design prototype. Also a git repo with no commits |
| `~/Projects/Meshtastic/design` | Design files, mostly duplicates, plus a few found nowhere else |

Since then, everything lives in this one repository at `~/Projects/Firepit`, with the full Android history.

## Finding a file that seems to be missing

`file-map.tsv` has one row for **every file that existed in the three folders before the change** (999 rows).
Large cache folders appear as one row each. Search it by name:

```bash
grep -i "room-info" archive/reorg-2026-09-30/file-map.tsv
column -t -s $'\t' archive/reorg-2026-09-30/file-map.tsv | less -S
```

Columns:
- `original`: the old path.
- `fate`: what happened to the file (see below).
- `now_at`: the path in this repository, or its place in the local backup.
- `note`
- `bytes`, `modified` and `md5`: taken **before** the change, so a copy can be proven identical.

| fate | meaning |
|---|---|
| `kept` | same path in this repository; the note says if a path or comment in it was edited |
| `moved` | new path in this repository |
| `archived` | now under `archive/` |
| `merged` / `rewritten` | its content went into the file in `now_at`; the original is in the local backup (below) |
| `duplicate` | not carried, because an identical file (same md5) is at `now_at` |
| `untracked` | still on disk, no longer in git |
| `backed-up` | not carried and deleted from disk; a copy is in the local backup at the path in `now_at` |
| `deleted` | not carried and deleted from disk: caches, the stale `mywork` snapshot, the empty `.git`, upstream files fetchable from GitHub (the note says why) |

## Cleanup (done 2026-09-30, after the merge)

- The merge commit on `main` is `a5372a8`, and GitHub shows the PR as merged. The ten reorganisation commits kept their IDs.
- `~/Projects/Firepit/_old-workspace/` (the parked files) and everything in `~/Projects/Meshtastic/` were deleted. That was about 2.5 GB, mostly build caches and the stale `refs/mywork` snapshot.
- **Local backup of the small files that were not carried** (70 files, 0.5 MB):
  `refs/reorg-backup-2026-09-30.tar.gz`, in the git-ignored `refs/`, so it lives on this Mac only.
  It holds:
  - the old workspace README, CLAUDE.md, .gitignore and .gitmodules
  - the 9 Sep docs and the prototype's protos, including its old primary key
  - `refs/ui-agent-rules.md`, the agent scratch files, `apptest.log` and the 13 iOS build logs
  - the Figma plugin's TypeScript template and the Copilot leftovers
  - the pre-change inventories
  List it with `tar -tzf refs/reorg-backup-2026-09-30.tar.gz`. Extract one file with
  `tar -xzf refs/reorg-backup-2026-09-30.tar.gz -O docs/meshchat-ux-design.md`.
- `refs/swift-protobuf-1.38.1/`, the protoc tool build that `scripts/gen-swift-protos.sh` uses, was moved to `refs/`.
- The agent checkpoint refs (`refs/agents/…`) in `.git` were left alone. They are local only and are never pushed.

## Commits on branch `reorg/monorepo`

| Commit | Subject |
|---|---|
| `052dfc4` | chore(repo): ignore iOS builds, local tooling and editor state |
| `d41fafc` | feat(ios): add the iOS app |
| `ca26f29` | build(scripts): add iOS and cross-platform tooling, pointed at this repository |
| `2fc7340` | chore(design): one design folder for both apps, MeshChat-era files archived |
| `cdff7b5` | chore(archive): keep the Android design-gallery prototype |
| `9b133d5` | docs: one README and one CLAUDE.md for both apps |
| `c094b8b` | ci: check that the iOS copy of the protos matches protos/ |
| `8c130d3` | chore(repo): shared VS Code settings and extensions |
| `a1f429e` | docs(archive): record where every file went in the 2026-09-30 reorganisation |
| `d63f123` | fix(scripts): run the Android build in verify-all on JDK 25 |
| `d711670` | fix(ios): find the shared primary key from the test's own source path |
| `71eb0d4` | docs(archive): record the verification run |
| `a5372a8` | Merge branch 'reorg/monorepo': one repository for both apps |

`git-state-before.txt` records the refs, remote and index of both repositories before the change.

## Verified

`scripts/verify-all.sh` passed on the Mac on 2026-09-30, with every check green:
- protos in sync
- Android build, JVM tests and lint
- FirepitKit (350 + 264 + 94 Swift tests)
- the Xcode app build with every test bundle
- crypto interop in both directions
- DAO parity

The first run turned up two problems:
- A stale `org.gradle.java.home` in `~/.gradle/gradle.properties`. verify-all now pins JDK 25.
- A test that looked for `protos/` from its working directory, which fails in the Simulator. It now looks from its own source path.
