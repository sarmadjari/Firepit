// MeshChat fix v2 — callouts only. Solid literal tints (translucent variable-bound fills flatten to grey on export).
(async () => {
  try {
    const hexToRgb = (h) => ({ r: parseInt(h.slice(1,3),16)/255, g: parseInt(h.slice(3,5),16)/255, b: parseInt(h.slice(5,7),16)/255 });
    const colls = await figma.variables.getLocalVariableCollectionsAsync();
    const varsOf = async (c) => { const vs = await Promise.all(c.variableIds.map(id => figma.variables.getVariableByIdAsync(id))); const m = {}; for (const v of vs) m[v.name] = v; return m; };
    const L = await varsOf(colls.find(c => c.name === "MeshChat/Colors"));
    const D = await varsOf(colls.find(c => c.name === "MeshChat/Colors · Dark"));
    const bound = (v) => figma.variables.setBoundVariableForPaint({ type: 'SOLID', color: { r: 0, g: 0, b: 0 } }, 'color', v);
    const page = figma.currentPage;
    let fixed = 0;
    // light: warn #9A6B00 @14% over white = #F1EADB ; dark: warn #F2C94C @18% over #1E1B18 = #443A21
    for (const [frameName, V, tint, stroke] of [
      ['07 Share location', L, '#F1EADB', '#E3D5B0'],
      ['07 Share location · Dark', D, '#443A21', '#5A4C2A'],
    ]) {
      const f = page.children.find(n => n.name === frameName);
      if (!f) continue;
      const note = f.findOne(n => n.type === 'FRAME' && n.children.some(c => c.name === 'icon/info'));
      if (!note) continue;
      note.fills = [{ type: 'SOLID', color: hexToRgb(tint) }];
      note.strokes = [{ type: 'SOLID', color: hexToRgb(stroke) }];
      note.strokeWeight = 1;
      for (const t of note.findAllWithCriteria({ types: ['TEXT'] })) {
        const segs = t.getStyledTextSegments(['fontName']);
        await Promise.all(segs.map(s => figma.loadFontAsync(s.fontName)));
        t.fills = [bound(V.text)];
      }
      fixed++;
    }
    figma.notify(`MeshChat fix v2: ${fixed} callouts re-tinted`);
  } catch (e) {
    console.error(e);
    figma.notify("MeshChat fix failed: " + e.message, { error: true, timeout: 8000 });
  }
  figma.closePlugin();
})();
