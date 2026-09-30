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
- `now_at`: the path in this repository, or where the file still sits outside it.
- `note`
- `bytes`, `modified` and `md5`: taken **before** the change, so a copy can be proven identical.

| fate | meaning |
|---|---|
| `kept` | same path in this repository; the note says if a path or comment in it was edited |
| `moved` | new path in this repository |
| `archived` | now under `archive/` |
| `merged` / `rewritten` | its content went into the file in `now_at`; the original is in `_old-workspace/` |
| `duplicate` | not carried, because an identical file (same md5) is at `now_at` |
| `untracked` | still on disk, no longer in git |
| `parked` | not carried, and kept in `~/Projects/Firepit/_old-workspace/` until the final cleanup (the note says why) |
| `not-carried` | left where it was, outside the repository, until the final cleanup |

## Outside the repository, until the final cleanup

- `~/Projects/Firepit/_old-workspace/` holds everything `parked`, at its original relative path: the old empty `.git`,
  old docs and protos, `refs/` (including the stale `mywork` snapshot), `.venv-tools`, the 13 iOS build logs, and the
  pre-change inventories in `_reorg-inventory/`. It is excluded from git through `.git/info/exclude`.
- `~/Projects/Meshtastic/Firepit` and `~/Projects/Meshtastic/design` are **untouched originals**.

Cleanup status: **pending**. Once the merge is verified, these folders are deleted. The one exception is
`refs/swift-protobuf-1.38.1` (the protoc tool build), which moves to `refs/`. After that, `md5` and this map are
the record of what was there.

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

`git-state-before.txt` records the refs, remote and index of both repositories before the change.
