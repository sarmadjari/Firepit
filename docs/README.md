# Documentation guide

Which document answers which question, how current each one is, and where to start.

## Start here

| If you want to… | Read |
|---|---|
| Understand what Firepit is and how it works | [architecture.md](architecture.md) |
| Build and run the apps | [../README.md](../README.md) |
| Change code (people and coding agents) | [../CLAUDE.md](../CLAUDE.md) |

## All documents

| Document | What it answers | Status |
|---|---|---|
| [architecture.md](architecture.md) | How the system works end to end: radios and channels, keys, what goes on the air, joining a room, ticks, the map, both apps' layers, how they are kept identical, where Android and iOS differ | **Current.** The overview; start here |
| [security.md](security.md) | What Firepit protects, against whom, and how. Every claim names the file that implements it and the test that holds it | **Current** for both apps |
| [meshchat-ux-design.md](meshchat-ux-design.md) | Screens, flows, components, visual design (colours, type, icons, markers) and microcopy, on both platforms | **Current specification.** The wireframes are the original plan; where the built apps differ, the text says so (for example Settings, §6.8) |
| [meshchat-implementation-guide.md](meshchat-implementation-guide.md) | Meshtastic protocol and firmware facts, with citations into the firmware and reference apps: transport, sessions, rate limits, channels, PKI, every feature's packets | **Reference.** Verified against firmware 2.7.26 and 2.8 |
| [meshchat-app-design.md](meshchat-app-design.md) | The product: who it is for, the features, node roles, security strategy, constraints, and the decision log | **Product intent.** Decisions locked 2026-09-09; §11 records them |
| [meshchat-v1-scope.md](meshchat-v1-scope.md) | What v1 includes, the technical decisions in plain language, and the naming | **Decision record** from planning (2026-09-09) |
| [build-plan.md](build-plan.md) | The staged plan the apps were built by, with what each stage proved and what broke along the way | **History and status.** The status table at the top says what is built |
| [ios-ui-porting-rules.md](ios-ui-porting-rules.md) | Rules for porting an Android screen to SwiftUI: architecture, control mapping, look, text, accessibility, verification | **Process.** Use when adding or changing iOS screens |
| [wismesh-tag-buttons.md](wismesh-tag-buttons.md) | The RAK WisMesh Tag radio: buttons, LED and buzzer signals, sharing a location from the Tag alone | **Hardware guide** |

## Elsewhere in the repository

| File | What it is |
|---|---|
| [../README.md](../README.md) | The project, the folders, building, testing, scripts |
| [../CLAUDE.md](../CLAUDE.md) | Working rules for everyone who changes code: sources of truth, protocol rules, toolchains, workflow |
| [../protos/UPSTREAM.md](../protos/UPSTREAM.md) | Which Meshtastic protobufs are vendored, at which version, and the constants the apps depend on |
| [../design/README.md](../design/README.md) | The app icon, the one-colour mark, Figma exports and reference screenshots |
| [../archive/README.md](../archive/README.md) | Kept for the record: the first Android prototype, the MeshChat-era design, the first icon, the 2026-09-30 reorganisation |

## Reading paths

- **New to the project:** [architecture.md](architecture.md) → [meshchat-app-design.md](meshchat-app-design.md)
  §1–3 → [../README.md](../README.md), then build both apps and try demo mode on iOS.
- **Working on a feature:** [../CLAUDE.md](../CLAUDE.md) → the feature's flow in
  [meshchat-ux-design.md](meshchat-ux-design.md) §5–6 → its packets in
  [meshchat-implementation-guide.md](meshchat-implementation-guide.md) §6 → the matching rules in
  [security.md](security.md).
- **Reviewing security:** [security.md](security.md) from §1, then the files its §10 lists, in that order.
- **Designing screens:** [meshchat-ux-design.md](meshchat-ux-design.md) §9–10, then
  [../design/README.md](../design/README.md).

## Names used in these documents

**Firepit** is the product. **MeshChat** is the protocol's internal name, used in the older document titles,
on the radio's primary channel and in `protos/meshchat`. "The guide", "the UX doc" and "the scope doc" refer to
the implementation guide, the UX design and the v1 scope.

## Keeping documents true

A change in behaviour updates the relevant section in the same commit (see [../CLAUDE.md](../CLAUDE.md),
Workflow). When a document and the code disagree, the code wins and the document is fixed.
