# MeshChat fix: local Figma development plugin

Applies scripted fixes to the Firepit Figma file when the Figma MCP call quota is used up.

- Install once: Figma desktop → Plugins → Development → Import plugin from manifest… → pick `manifest.json`.
- Use: put the Plugin-API script in `code.js`, then run Plugins → Development → MeshChat fix.

`code.js` is the payload and is edited by hand. The TypeScript template the plugin was created from (`code.ts`,
`package.json`, `tsconfig.json`) was left out on purpose: building it would overwrite `code.js` with Figma's
"five rectangles" sample.

Lesson recorded while using it: variable-bound fills with opacity < 1 flatten to grey on PDF export, so use solid
literal tints for translucent surfaces in mockups.
