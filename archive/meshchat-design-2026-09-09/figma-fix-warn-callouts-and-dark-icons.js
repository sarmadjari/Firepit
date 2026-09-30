// MeshChat Figma fix — run via the Figma MCP `use_figma` tool (fileKey KhCa85jKBBx88JYrMs2wlX)
// once the Starter-plan MCP quota resets, or paste into Figma's plugin console (Plugins → Development → Open console).
//
// Fix 1: the warn callout on both "07 Share location" sheets rendered near-solid gold with theme-mismatched text.
//         → 14 % (light) / 18 % (dark) tint of `warn`, text bound to the theme's `text`.
// Fix 2: in the dark row, white icons inside primary-filled buttons (FAB +, send, Share link, Share my location,
//         Start sharing) stayed white while the label text switched to dark `on-primary`.
//         → recolour literal-white vectors inside dark-primary surfaces to #3B1400 (dark on-primary).

const hexToRgb = (h) => ({ r: parseInt(h.slice(1,3),16)/255, g: parseInt(h.slice(3,5),16)/255, b: parseInt(h.slice(5,7),16)/255 });
const rgbToHex = (c) => '#' + [c.r,c.g,c.b].map(v => Math.round(v*255).toString(16).padStart(2,'0')).join('').toUpperCase();
const colls = await figma.variables.getLocalVariableCollectionsAsync();
const varsOf = async (c) => { const vs = await Promise.all(c.variableIds.map(id => figma.variables.getVariableByIdAsync(id))); const m = {}; for (const v of vs) m[v.name] = v; return m; };
const L = await varsOf(colls.find(c => c.name === "MeshChat/Colors"));
const D = await varsOf(colls.find(c => c.name === "MeshChat/Colors · Dark"));
const bound = (v, opacity) => figma.variables.setBoundVariableForPaint({type:'SOLID', color:{r:0,g:0,b:0}, opacity: opacity === undefined ? 1 : opacity}, 'color', v);
const mutated = [];
const page = figma.currentPage;

// Fix 1
for (const [frameName, V, op] of [['07 Share location', L, 0.14], ['07 Share location · Dark', D, 0.18]]) {
  const f = page.children.find(n => n.name === frameName);
  const note = f.findOne(n => n.type === 'FRAME' && n.children.some(c => c.name === 'icon/info'));
  note.fills = [bound(V.warn, op)];
  for (const t of note.findAllWithCriteria({types:['TEXT']})) {
    const segs = t.getStyledTextSegments(['fontName']);
    await Promise.all(segs.map(s => figma.loadFontAsync(s.fontName)));
    t.fills = [bound(V.text)];
    mutated.push(t.id);
  }
  mutated.push(note.id);
}

// Fix 2
const darkPrimaryId = D.primary.id;
const isDarkPrimary = (n) => ('fills' in n) && n.fills !== figma.mixed && Array.isArray(n.fills) && n.fills.some(p => p.type === 'SOLID' && p.boundVariables && p.boundVariables.color && p.boundVariables.color.id === darkPrimaryId);
const onDark = hexToRgb('#3B1400');
let iconFixes = 0;
for (const f of page.children.filter(n => n.type === 'FRAME' && n.name.includes('· Dark'))) {
  for (const v of f.findAllWithCriteria({types:['VECTOR','BOOLEAN_OPERATION','ELLIPSE','RECTANGLE']})) {
    let p = v.parent, depth = 0, on = false;
    while (p && p !== f && depth < 6) { if (isDarkPrimary(p)) { on = true; break; } p = p.parent; depth++; }
    if (!on) continue;
    const fix = (arr) => { let ch = false; const out = arr.map(x => { if (x.type === 'SOLID' && !(x.boundVariables && x.boundVariables.color) && rgbToHex(x.color) === '#FFFFFF') { ch = true; return Object.assign({}, x, {color: onDark}); } return x; }); return ch ? out : null; };
    if (Array.isArray(v.fills) && v.fills.length) { const nf = fix(v.fills); if (nf) { v.fills = nf; iconFixes++; mutated.push(v.id); } }
    if (Array.isArray(v.strokes) && v.strokes.length) { const ns = fix(v.strokes); if (ns) { v.strokes = ns; iconFixes++; mutated.push(v.id); } }
  }
}
return { mutatedNodeIds: mutated, iconFixes };
