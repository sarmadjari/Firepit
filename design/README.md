# design/

Everything visual for Firepit. No build reads this folder: the apps carry their own theme tokens and icons, and
this is the reference they were drawn from.

| Path | What it is | Status |
|---|---|---|
| `brand/firepit-icon.svg` | App icon: ember gradient, cream flame with a hollow, pit rim. 512-unit canvas | **In use.** Android draws it as `android/app/src/main/res/drawable/ic_launcher_{background,foreground}.xml`; iOS renders `AppIcon*.png` from the same paths with `scripts/render-ios-app-icon.swift` |
| `brand/firepit-mark.svg` | One-colour mark | **In use.** Android monochrome/notification layer; iOS `DesignSystem/Components/FirepitMark.swift` |
| `artwork/icon-app.afdesign`, `icon-app.svg` | New icon drawing (Affinity Designer, 2026-09-29): larger three-tongue flame, brighter gradient | Draft, **not in use** |
| `ios/firepit-ios-ui.pdf` | Figma export of the iOS mockups (2026-09-10), 16 pages: light 00–07, then dark 00–07 | Reference renders (UX doc §9.6, §12) |
| `ios/frames/*.png` | Those 16 pages at 144 dpi, one PNG per frame (`00-tokens-light.png` … `07-share-location-dark.png`) | Reference renders |
| `android/firepit-android-ui.pdf` | The Android design gallery as a PDF (2026-09-10) | Reference renders |
| `android/screens/*.png` | Screenshots of that gallery, light and dark (made by the archived prototype's `capture-design.sh`) | Reference renders |
| `android/contact-sheet.png`, `emulator-shell-*.png` | Contact sheet of the screens; empty emulator frames | Reference |
| `figma-plugin/` | Local Figma development plugin ("MeshChat fix") for scripted fixes when the Figma MCP quota runs out | Tool, see its README |

The live source is the Figma file https://www.figma.com/design/KhCa85jKBBx88JYrMs2wlX (its variable collections are
still named `MeshChat/Colors` and `MeshChat/Colors · Dark`).

## Re-exporting the iOS frames

1. Figma → File → Export frames to PDF → save as `design/ios/firepit-ios-ui.pdf`.
2. `pdftoppm -png -r 144 design/ios/firepit-ios-ui.pdf /tmp/frame` and rename pages 1–8 to
   `00-tokens-light` … `07-share-location-light` and 9–16 to the same names with `-dark`.
3. File → Save local copy… for a `.fig` backup, saved as `design/ios/firepit-ios-ui.fig`. The only `.fig` backup so far is the
   pre-rename one in `archive/meshchat-design-2026-09-09/`.

Older MeshChat-era files (the design before the app was called Firepit) are in `archive/meshchat-design-2026-09-09/`.
