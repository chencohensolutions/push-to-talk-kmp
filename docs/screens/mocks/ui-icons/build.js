// Builds the UI icon sheet: node build3.js -> ui-icons.html (static SVG and CSS, no script in the page).
// An icon is a list of layers on a 24-unit grid, all in ONE tint; only their opacity differs. Roles:
//   L line (full strength, 1.75)   l a finer line (1.5)   L2 a line of the layer behind (55%)
//   S soft fill (the tint at the soft alpha)              F solid part
//   H inner edge: a 1-unit line at 30%, inside a soft body; {hi} on a solid part is the same line, cut out of it
//   K key part (solid) and KL key line: the part that takes the second colour in the few two-colour icons
// `slash` cuts a gap through everything drawn before it and draws the bar.
const fs = require('fs');
const r2 = n => Math.round(n * 100) / 100;

// The back bubble of Chat: the part of a circle that shows past the front bubble, with a gap between them.
function backBubble(cx, cy, r, fx, fy, fr, gap) {
  const out = a => Math.hypot(cx + r * Math.cos(a) - fx, cy + r * Math.sin(a) - fy) >= fr + gap;
  const N = 720; let start = -1;
  for (let i = 0; i < N; i++) if (!out((i - 1) / N * 2 * Math.PI) && out(i / N * 2 * Math.PI)) start = i;
  let end = start; while (out(((end + 1) % N) / N * 2 * Math.PI) && end - start < N) end++;
  const a0 = start / N * 2 * Math.PI, a1 = end / N * 2 * Math.PI;
  const p = a => [cx + r * Math.cos(a), cy + r * Math.sin(a)];
  const [x0, y0] = p(a0), [x1, y1] = p(a1);
  const line = `M${r2(x0)} ${r2(y0)}A${r} ${r} 0 ${a1 - a0 > Math.PI ? 1 : 0} 1 ${r2(x1)} ${r2(y1)}`;
  // close along the front bubble's edge (pushed out by the gap), the short way back
  const fill = `${line}A${fr + gap} ${fr + gap} 0 0 0 ${r2(x0)} ${r2(y0)}Z`;
  return { line, fill };
}
const bb = backBubble(16.7, 8.3, 4.8, 10.5, 12.5, 7, 1.7);

const rr = (x, y, w, h, r) => `M${x + r} ${y}h${r2(w - 2 * r)}a${r} ${r} 0 0 1 ${r} ${r}v${r2(h - 2 * r)}a${r} ${r} 0 0 1-${r} ${r}h-${r2(w - 2 * r)}a${r} ${r} 0 0 1-${r}-${r}v-${r2(h - 2 * r)}a${r} ${r} 0 0 1 ${r}-${r}z`;
const circ = (cx, cy, r) => `M${cx} ${r2(cy - r)}a${r} ${r} 0 1 0 0 ${2 * r}a${r} ${r} 0 1 0 0-${2 * r}z`;

const P = {
  capsule: 'M12 2.25a3.25 3.25 0 0 1 3.25 3.25v5.5a3.25 3.25 0 0 1-6.5 0V5.5A3.25 3.25 0 0 1 12 2.25z',
  camBody: rr(2.5, 6, 13.5, 12, 2.5),
  camLens: 'M17.9 10.5l3.2-2.1a.6.6 0 0 1 .9.5v6.2a.6.6 0 0 1-.9.5l-3.2-2.1z',
  handset: 'M5 4h3.5l1.5 4.5-2.2 1.4a11 11 0 0 0 6.3 6.3l1.4-2.2L20 15.5V19a1.5 1.5 0 0 1-1.6 1.5A16.5 16.5 0 0 1 3.5 5.6 1.5 1.5 0 0 1 5 4z',
  bell: 'M18 8.5a6 6 0 0 0-12 0c0 5.6-2 7.4-2.6 8a.3.3 0 0 0 .2.5h16.8a.3.3 0 0 0 .2-.5c-.6-.6-2.6-2.4-2.6-8z',
  cone: 'M11 5.3L6.4 9H3.6a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h2.8l4.6 3.7a.6.6 0 0 0 1-.47V5.77a.6.6 0 0 0-1-.47z',
  shield: 'M12 2.75l7.75 2.9v5.85c0 4.9-3.3 8-7.75 9.25-4.45-1.25-7.75-4.35-7.75-9.25V5.65z',
  lockBody: rr(4, 10.5, 16, 10.5, 2.5),
  cloud: 'M7 19a5 5 0 0 1-.9-9.92A6 6 0 0 1 17.7 9.3 4.85 4.85 0 0 1 17.2 19z',
  frontBubble: 'M17.5 12.5a7 7 0 0 1-10.2 6.2L3 20l1.25-4.1A7 7 0 1 1 17.5 12.5z',
  personBody: 'M4.4 20.5c.9-3.7 3.9-5.9 7.6-5.9s6.7 2.2 7.6 5.9',
  can: 'M6.2 8.2l.75 10.5a2 2 0 0 0 2 1.8h6.1a2 2 0 0 0 2-1.8l.75-10.5',
  pencil: 'M16.2 3.9a2.05 2.05 0 0 1 2.9 2.9L7.4 18.5l-3.9 1 1-3.9z',
  tray: 'M4 16v2a2.5 2.5 0 0 0 2.5 2.5h11A2.5 2.5 0 0 0 20 18v-2',
};

// name: [accent, layers, mirrored?]. The order is the order of the sheet's rows.
const fine = {
  // live
  Mic: ['teal', [['K', P.capsule, { hi: 'M10.7 5.4v3.8' }], ['L', 'M5.5 11.25a6.5 6.5 0 0 0 13 0'], ['L', 'M12 17.75v3.25'], ['L', 'M9 21h6']]],
  MicOff: [null, [['S', P.capsule], ['L', P.capsule], ['L', 'M5.5 11.25a6.5 6.5 0 0 0 13 0'], ['L', 'M12 17.75v3.25'], ['L', 'M9 21h6'], ['slash', 'M4 4l16 16']]],
  Video: ['teal', [['S', P.camBody], ['L', P.camBody], ['H', 'M4.5 10.6V9.4A1.4 1.4 0 0 1 5.9 8h2.5'], ['K', P.camLens, { round: 1 }]]],
  VideoOff: [null, [['S', P.camBody], ['L', P.camBody], ['L', P.camLens], ['slash', 'M3.5 3.5l17 17']]],
  Call: ['teal', [['S', P.handset], ['L', P.handset], ['KL', 'M14.6 3.6a5.8 5.8 0 0 1 5.8 5.8'], ['KL', 'M14.6 7.1a2.3 2.3 0 0 1 2.3 2.3']]],
  Talk: ['teal', [['S', rr(6, 7.5, 12, 14, 2.5)], ['L', rr(6, 7.5, 12, 14, 2.5)], ['L', 'M9.25 2.5v5'], ['K', rr(8.5, 10.1, 7, 3.6, 1.2)],
    ['l', 'M9.2 16.5h5.6'], ['l', 'M9.2 18.8h3.2']]],
  Speaker: [null, [['S', P.cone], ['L', P.cone], ['L', 'M15.6 8.9a4.6 4.6 0 0 1 0 6.2'], ['L', 'M18.6 6a8.6 8.6 0 0 1 0 12']]],
  SpeakerOff: [null, [['S', P.cone], ['L', P.cone], ['L', 'M16.2 9.4l5.2 5.2'], ['L', 'M21.4 9.4l-5.2 5.2']]],
  Play: [null, [['F', 'M8 5.2v13.6a1 1 0 0 0 1.53.85l10.6-6.8a1 1 0 0 0 0-1.7L9.53 4.35A1 1 0 0 0 8 5.2z', { hi: 'M10 8.3v4.6' }]]],
  Pause: [null, [['F', rr(6.5, 4.75, 3.75, 14.5, 1.4), { hi: 'M8 7.3v4.6' }], ['F', rr(13.75, 4.75, 3.75, 14.5, 1.4), { hi: 'M15.25 7.3v4.6' }]]],
  // people and chat
  Chat: [null, [['S', bb.fill], ['L2', bb.line], ['S', P.frontBubble], ['L', P.frontBubble], ['H', 'M5.6 11.6a5 5 0 0 1 2.9-3.9']]],
  Bell: ['coral', [['S', P.bell], ['L', P.bell], ['H', 'M8.2 8.5a3.9 3.9 0 0 1 2.2-3.4'], ['K', 'M9.6 19.3h4.8a2.45 2.45 0 0 1-4.8 0z', { round: 1 }]]],
  BellOff: [null, [['S', P.bell], ['L', P.bell], ['L', 'M10 19.6a2.2 2.2 0 0 0 4 0'], ['slash', 'M3.5 3.5l17 17']]],
  Person: [null, [['S', circ(12, 7.75, 3.9)], ['L', circ(12, 7.75, 3.9)], ['H', 'M9.9 7a2.4 2.4 0 0 1 1.5-1.4'], ['S', P.personBody + 'z'], ['L', P.personBody]]],
  Group: [null, [['L2', 'M15.6 4.5a3.6 3.6 0 0 1 0 7'], ['L2', 'M18.2 15.3c1.9.9 3 2.7 3.4 5.2'], ['S', circ(9, 8, 3.6)], ['L', circ(9, 8, 3.6)], ['H', 'M7.1 7.3a2.2 2.2 0 0 1 1.4-1.3'],
    ['S', 'M2.4 20.5c.7-3.6 3.3-5.6 6.6-5.6s5.9 2 6.6 5.6z'], ['L', 'M2.4 20.5c.7-3.6 3.3-5.6 6.6-5.6s5.9 2 6.6 5.6']]],
  Photo: [null, [['L', rr(3, 3.5, 18, 17, 2.5)], ['S', 'M3.3 17.7l5-5a1 1 0 0 1 1.4 0l3.8 3.8 2-2a1 1 0 0 1 1.4 0l3.8 3.8v.2a2.5 2.5 0 0 1-2.5 2.5h-13a2.5 2.5 0 0 1-2.2-1.3z'],
    ['L', 'M3.4 17.6l4.9-4.9a1 1 0 0 1 1.4 0l5.6 5.6'], ['L', 'M13.6 16.4l1.9-1.9a1 1 0 0 1 1.4 0l3.8 3.8'], ['F', circ(15.9, 8.3, 1.55)]]],
  Share: [null, [['L', 'M8.5 13.4l7 4.1'], ['L', 'M15.5 6.5l-7 4.1'], ['F', circ(6, 12, 2.9)], ['S', circ(18, 5, 2.75)], ['L', circ(18, 5, 2.75)], ['S', circ(18, 19, 2.75)], ['L', circ(18, 19, 2.75)]]],
  Edit: [null, [['S', P.pencil], ['L', P.pencil], ['L', 'M14.2 5.9l2.9 2.9'], ['F', 'M3.5 19.5l1-3.9 2.9 2.9z', { round: 1 }], ['L', 'M12.5 20.5h8']]],
  Copy: [null, [['L2', 'M5.2 15h-.7A1.5 1.5 0 0 1 3 13.5v-9A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5v.7'], ['S', rr(8, 8.5, 13, 12.5, 2)], ['L', rr(8, 8.5, 13, 12.5, 2)], ['H', 'M9.9 12.6v-1a1.2 1.2 0 0 1 1.2-1.2h2']]],
  Paste: [null, [['S', rr(5, 5.5, 14, 15.5, 2.5)], ['L', 'M8.5 5.5h-1A2.5 2.5 0 0 0 5 8v10.5A2.5 2.5 0 0 0 7.5 21h9a2.5 2.5 0 0 0 2.5-2.5V8a2.5 2.5 0 0 0-2.5-2.5h-1'],
    ['F', rr(8.5, 3, 7, 4, 1.2)], ['l', 'M9 12.3h6'], ['l', 'M9 16h3.6']]],
  // trust and system
  Lock: [null, [['L', 'M7.75 10.5V7.25a4.25 4.25 0 0 1 8.5 0v3.25'], ['S', P.lockBody], ['L', P.lockBody], ['H', 'M5.9 14.8v-1.1a1.3 1.3 0 0 1 1.3-1.3h2'], ['F', circ(12, 14.7, 1.3)], ['l', 'M12 15.6v2.1']]],
  Shield: [null, [['S', P.shield], ['L', P.shield], ['H', 'M6.2 10.2V7l2.9-1.1'], ['L', 'M8.6 11.9l2.5 2.5 4.4-4.9']]],
  Server: [null, [['S', rr(3, 3.5, 18, 7, 2.5)], ['L', rr(3, 3.5, 18, 7, 2.5)], ['S', rr(3, 13.5, 18, 7, 2.5)], ['L', rr(3, 13.5, 18, 7, 2.5)],
    ['F', circ(7, 7, 1.3)], ['F', circ(7, 17, 1.3)], ['l', 'M11.5 7h5.5'], ['l', 'M11.5 17h3.5']]],
  Scan: [null, [['L', 'M3 8V5.5A2.5 2.5 0 0 1 5.5 3H8'], ['L', 'M16 3h2.5A2.5 2.5 0 0 1 21 5.5V8'], ['L', 'M21 16v2.5a2.5 2.5 0 0 1-2.5 2.5H16'], ['L', 'M8 21H5.5A2.5 2.5 0 0 1 3 18.5V16'],
    ['S', 'M6.5 12h11v3.2a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1z'], ['L', 'M6 12h12']]],
  QrCode: [null, [['S', rr(3.5, 3.5, 6.5, 6.5, 1.6)], ['L', rr(3.5, 3.5, 6.5, 6.5, 1.6)], ['S', rr(14, 3.5, 6.5, 6.5, 1.6)], ['L', rr(14, 3.5, 6.5, 6.5, 1.6)], ['S', rr(3.5, 14, 6.5, 6.5, 1.6)], ['L', rr(3.5, 14, 6.5, 6.5, 1.6)],
    ['F', rr(5.85, 5.85, 1.8, 1.8, .5)], ['F', rr(16.35, 5.85, 1.8, 1.8, .5)], ['F', rr(5.85, 16.35, 1.8, 1.8, .5)],
    ['F', rr(13.4, 13.4, 3, 3, .8)], ['F', rr(18, 18, 3, 3, .8)], ['F', rr(18.3, 13.6, 2.2, 2.2, .7)], ['F', rr(13.6, 18.3, 2.2, 2.2, .7)]]],
  Info: [null, [['S', circ(12, 12, 9)], ['L', circ(12, 12, 9)], ['H', 'M5.9 10.4a6.4 6.4 0 0 1 3.4-4.1'], ['L', 'M12 11.2v5.2'], ['F', circ(12, 7.9, 1.15)]]],
  Error: [null, [['S', circ(12, 12, 9)], ['L', circ(12, 12, 9)], ['H', 'M5.9 10.4a6.4 6.4 0 0 1 3.4-4.1'], ['L', 'M12 7.4v5.6'], ['F', circ(12, 16.4, 1.15)]], false, 'red'],
  CloudOff: [null, [['S', P.cloud], ['L', P.cloud], ['slash', 'M3.5 3.5l17 17']], false, 'warn'],
  Trash: ['red', [['S', P.can + 'z'], ['L', P.can], ['l', 'M10.2 11.6v5.4'], ['l', 'M13.8 11.6v5.4'], ['KL', 'M4 6.8h16'], ['KL', 'M9.3 6.8V5.2A1.2 1.2 0 0 1 10.5 4h3a1.2 1.2 0 0 1 1.2 1.2v1.6']]],
  Download: [null, [['S', 'M4 16h16v2a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 18z'], ['L', P.tray], ['L', 'M12 3.5v11'], ['L', 'M7.5 10.5l4.5 4.5 4.5-4.5']]],
  // plain: these stay lines
  Flip: [null, [['L', 'M3.5 12a8.5 8.5 0 0 1 14.4-6.1L20.5 8.4'], ['L', 'M20.5 3.6v4.8h-4.8'], ['L', 'M20.5 12a8.5 8.5 0 0 1-14.4 6.1L3.5 15.6'], ['L', 'M3.5 20.4v-4.8h4.8']]],
  Expand: [null, [['L', 'M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9'], ['L', 'M20 9V5.5A1.5 1.5 0 0 0 18.5 4H15'], ['L', 'M4 15v3.5A1.5 1.5 0 0 0 5.5 20H9'], ['L', 'M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15']]],
  Plus: [null, [['L', 'M12 5v14'], ['L', 'M5 12h14']]],
  Close: [null, [['L', 'M6 6l12 12'], ['L', 'M18 6L6 18']]],
  Check: [null, [['L', 'M5 12.5l4.5 4.5L19 7']]],
  Back: [null, [['L', 'M19 12H5'], ['L', 'M12 19l-7-7 7-7']], true],
  ArrowBack: [null, [['L', 'M19 12H5'], ['L', 'M11 6l-6 6 6 6']], true],
  ArrowUp: [null, [['L', 'M12 19V5'], ['L', 'M6 11l6-6 6 6']]],
  ChevronRight: [null, [['L', 'M9 6l6 6-6 6']], true],
  ChevronDown: [null, [['L', 'M6 9l6 6 6-6']]],
};

// Today's icons (LibraryStyle.kt, EnrollmentWizard.kt): stroke 2, one tint. Only the ones the strip and the mock screens show.
const now = {
  Video: ['M4 6h11a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z', 'M17 10l5-3v10l-5-3'],
  Mic: ['M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z', 'M5 11a7 7 0 0 0 14 0', 'M12 18v3'],
  Call: ['M5 4h3.5l1.5 4.5-2.2 1.4a11 11 0 0 0 6.3 6.3l1.4-2.2L20 15.5V19a1.5 1.5 0 0 1-1.6 1.5A16.5 16.5 0 0 1 3.5 5.6 1.5 1.5 0 0 1 5 4z'],
  Talk: ['M8 7h8a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z', 'M9 2v5', 'M9 11h6', 'M9 14h6', 'M12 18h0.01'],
  Chat: ['M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-4.6A8 8 0 1 1 21 12z'],
  Bell: ['M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9', 'M13.73 21a2 2 0 0 1-3.46 0'],
  Lock: ['M5 11h14a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z', 'M7 11V7a5 5 0 0 1 10 0v4'],
  Shield: ['M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z', 'M8.5 12l2.5 2.5 4.5-5'],
  Server: ['M5 4h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z', 'M5 13h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z', 'M7 7.5h0.01', 'M7 16.5h0.01'],
  Person: ['M12 4a4 4 0 1 0 0.01 0', 'M4 20c1.5-3.5 4.5-5 8-5s6.5 1.5 8 5'],
  Trash: ['M4 7h16', 'M9 7V4h6v3', 'M6 7l1 13h10l1-13', 'M10 11v6', 'M14 11v6'],
  Scan: ['M3 8V5a2 2 0 0 1 2-2h3', 'M16 3h3a2 2 0 0 1 2 2v3', 'M21 16v3a2 2 0 0 1-2 2h-3', 'M8 21H5a2 2 0 0 1-2-2v-3', 'M7 12h10'],
  Back: ['M19 12H5', 'M12 19l-7-7 7-7'],
  ChevronRight: ['M9 6l6 6-6 6'],
};

const st = {
  L: 'fill:none;stroke:var(--m);stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round',
  l: 'fill:none;stroke:var(--m);stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round',
  L2: 'fill:none;stroke:var(--m);stroke-opacity:.55;stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round',
  S: 'fill:var(--m);fill-opacity:var(--soft)',
  F: 'fill:var(--m)',
  K: 'fill:var(--k)',
  KL: 'fill:none;stroke:var(--k);stroke-width:1.75;stroke-linecap:round;stroke-linejoin:round',
  H: 'fill:none;stroke:var(--m);stroke-opacity:var(--edge);stroke-width:1;stroke-linecap:round;stroke-linejoin:round',
};
function fineSymbol(name) {
  const [, layers] = fine[name];
  let out = '', pre = '', n = 0;
  for (const [role, d, o = {}] of layers) {
    if (role === 'slash') {
      // everything so far gets a gap where the bar passes
      pre += `<mask id="cut-${name}" maskUnits="userSpaceOnUse" x="0" y="0" width="24" height="24"><rect width="24" height="24" fill="#fff"/><path d="${d}" stroke="#000" stroke-width="5" stroke-linecap="round"/></mask>`;
      out = `<g mask="url(#cut-${name})">${out}</g><path d="${d}" style="${st.L}"/>`;
      continue;
    }
    const paint = role === 'K' ? 'var(--k)' : 'var(--m)';
    let style = st[role] + (o.round ? `;stroke:${paint};stroke-width:1;stroke-linejoin:round` : '');
    if (o.hi) {
      // the inner edge of a solid part: cut out of it, then drawn at the edge's strength
      const id = `hi-${name}-${n++}`;
      pre += `<mask id="${id}" maskUnits="userSpaceOnUse" x="0" y="0" width="24" height="24"><rect width="24" height="24" fill="#fff"/><path d="${o.hi}" stroke="#000" stroke-width="1" stroke-linecap="round"/></mask>`;
      out += `<path d="${d}" style="${style}" mask="url(#${id})"/><path d="${o.hi}" style="fill:none;stroke:${paint};stroke-opacity:var(--edge);stroke-width:1;stroke-linecap:round"/>`;
    } else out += `<path d="${d}" style="${style}"/>`;
  }
  return `<symbol id="f-${name}" viewBox="0 0 24 24">${pre}${out}</symbol>`;
}
const nowSymbol = name => `<symbol id="n-${name}" viewBox="0 0 24 24">${now[name].map(d => `<path d="${d}" style="fill:none;stroke:var(--m);stroke-width:2;stroke-linecap:round;stroke-linejoin:round"/>`).join('')}</symbol>`;

// An icon on the page. kind: f (fine) or n (now). cls: extra classes (two, muted, on-coral, flat).
// A fine icon is one tint unless cls has `two`: then its key part takes the icon's second colour.
function ic(kind, name, size, cls = '') {
  const meta = kind === 'f' ? fine[name] : null;
  const second = meta && meta[0] && /\btwo\b/.test(cls) ? ` k-${meta[0]}` : '';
  const main = meta && meta[3] && !/on-coral|muted/.test(cls) ? ` m-${meta[3]}` : '';
  return `<svg class="ic${second}${main} ${cls}" width="${size}" height="${size}" viewBox="0 0 24 24" role="img" aria-label="${name}"><use href="#${kind}-${name}"/></svg>`;
}
const twoColour = Object.keys(fine).filter(n => fine[n][0]);

// ---- the sheet's parts -------------------------------------------------------------------------------------------
const names = Object.keys(fine);
const themeName = t => t === 'light' ? 'Light' : 'Dark';
const setPanel = theme => `<div class="panel ${theme}"><div class="tag">${themeName(theme)} · one tint</div>
  <div class="set">${names.map(n => `<div class="cell">${ic('f', n, 48)}<span>${n}</span></div>`).join('')}</div></div>`;

const why = { Mic: 'live: teal head', Video: 'live: teal lens', Talk: 'live: teal screen', Call: 'in a call: teal waves', Bell: 'urgent push: coral clapper', Trash: 'removing: red lid' };
const twoPanel = theme => `<div class="panel ${theme}"><div class="tag">${themeName(theme)} · the six with a second colour</div>
  <div class="pairs">${twoColour.map(n => `<div class="pair"><div class="duo">${ic('f', n, 40)}<span class="to">›</span>${ic('f', n, 48, 'two')}</div><b>${n}</b><span>${why[n]}</span></div>`).join('')}</div></div>`;

const key = ['Mic', 'Video', 'Call', 'Talk', 'Chat', 'Bell', 'Lock', 'Shield', 'Server', 'Person', 'Trash', 'Scan'];
const stripPanel = theme => {
  const row = (label, f) => `<tr><th>${label}</th>${key.map(n => `<td>${f(n)}</td>`).join('')}</tr>`;
  return `<div class="panel ${theme}"><div class="tag">${themeName(theme)}</div><table class="strip">
    <tr class="head"><th></th>${key.map(n => `<td>${n}</td>`).join('')}</tr>
    ${row('Now, 24', n => ic('n', n, 24))}
    ${row('Fine, one tint, 24', n => ic('f', n, 24))}
    ${row('Fine, one tint, 18', n => ic('f', n, 18))}
    ${row('Second colour, 24', n => fine[n][0] ? ic('f', n, 24, 'two') : '<span class="none">·</span>')}
    ${row('On a coral button', n => `<span class="btn">${ic('f', n, 24, 'on-coral')}</span>`)}
    ${row('Muted, 18', n => ic('f', n, 18, 'muted'))}
  </table></div>`;
};

// How the detail goes away, on three icons: one column per step.
const steps = [
  ['Second colour', 'only while it means something', n => ic('f', n, 56, 'two')],
  ['One tint, the default', 'line, 8% fill, 30% inner edge', n => ic('f', n, 56)],
  ['24 and 18', 'the inner edge fades by itself', n => `<span class="sizes">${ic('f', n, 24)}${ic('f', n, 18)}</span>`],
  ['Any tint', 'the fill is the tint’s own alpha', n => `<span class="btn">${ic('f', n, 24, 'on-coral')}</span>`],
  ['Status bar', 'no fill: lines and the solid part', n => ic('f', n, 24, 'flat')],
];
const degrade = theme => `<div class="panel ${theme}"><div class="tag">${themeName(theme)}</div><table class="steps">
  <tr class="head">${steps.map(([t, c]) => `<td><b>${t}</b><span>${c}</span></td>`).join('')}</tr>
  ${['Mic', 'Video', 'Bell'].map(n => `<tr>${steps.map(([, , f]) => `<td>${f(n)}</td>`).join('')}</tr>`).join('')}
</table></div>`;

// Mock screens. v: 'n' (now) or 'f' (fine).
const face = (letter, cls = '') => `<span class="face ${cls}">${letter}</span>`;
const header = v => `<div class="phone">
  <div class="hdr"><span class="hit">${ic(v, 'Back', 24)}</span>${face('D')}<div class="who"><b>Dana</b><i class="live">Dana is talking</i></div>
    <span class="acts">${ic(v, 'Video', 24)}${ic(v, 'Call', 24)}${ic(v, 'Talk', 24, v === 'f' ? 'two' : '')}</span></div>
  <div class="chat"><div class="b theirs">Are you coming to dinner?</div><div class="b mine">On my way, ten minutes</div></div>
</div>`;
const push = v => `<div class="phone">
  <div class="chat short"><div class="b theirs">Call me when you park</div></div>
  <div class="strip-bar"><div class="field">Message</div><span class="round">${ic(v, 'Video', 24)}</span><span class="pushbtn">${ic(v, 'Mic', 28, 'on-coral')}</span></div>
</div>`;
const rows = [['Bell', 'Notifications', 'Message sound, urgent push'], ['Lock', 'Screen lock', 'Ask for the phone’s lock to open Talk'], ['Shield', 'Safety numbers', '3 of 5 verified'], ['Server', 'Your server', 'Connected · 192.168.50.2:56400']];
const settings = v => `<div class="phone">
  <div class="sect">Privacy and server</div>
  <div class="card">${rows.map(([n, t, c]) => `<div class="row">${ic(v, n, 24)}<div class="txt"><b>${t}</b><i>${c}</i></div>${ic(v, 'ChevronRight', 20, 'muted')}</div>`).join('')}</div>
</div>`;
const context = (title, f) => `<div class="ctx"><h3>${title}</h3><div class="four">
  ${[['light', 'n', 'Now'], ['light', 'f', 'Fine'], ['dark', 'n', 'Now'], ['dark', 'f', 'Fine']].map(([t, v, l]) => `<div class="shot ${t}"><div class="tag">${l} · ${t}</div>${f(v)}</div>`).join('')}
</div></div>`;

// The app icon the style comes from (the One roof drawing of the second sheet).
const sheet2 = fs.readFileSync('sheet2.html', 'utf8');
const appDefs = sheet2.match(/<defs>[\s\S]*?<\/defs>/)[0];
const appIcon = sheet2.match(/<symbol id="i-oneroof"[\s\S]*?<\/symbol>/)[0];

const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>UI icons with the app icon's detail</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 24px; width: 1550px; background: #0d1110; color: #f3f1ec;
         font-family: "Inter", "Helvetica Neue", Helvetica, Arial, "Liberation Sans", sans-serif; }
  h1 { margin: 0 0 4px; font-size: 27px; letter-spacing: -0.2px; }
  h2 { margin: 30px 0 4px; font-size: 19px; }
  h3 { margin: 0 0 8px; font-size: 14px; color: #c9cfcc; font-weight: 600; }
  .sub { margin: 0 0 14px; color: #a9b0ad; font-size: 14.5px; line-height: 1.35; max-width: 150ch; }
  .top { display: flex; gap: 22px; align-items: flex-start; }
  .app { flex: none; width: 96px; height: 96px; border-radius: 27.78%; overflow: hidden; display: block; }
  .rules { margin: 6px 0 0; padding: 0; list-style: none; color: #c9cfcc; font-size: 14px; line-height: 1.45; columns: 2; column-gap: 40px; max-width: 1380px; }
  .rules b { color: #fff; font-weight: 600; }
  .rules li { break-inside: avoid; margin-bottom: 4px; }

  /* the app's two palettes (LibraryStyle.kt), and the icon paints made from them */
  .light { --bg: #F4F5F3; --raised: #FFFFFF; --ink: #17201F; --muted: #5E6866; --line: rgba(94,104,102,.3); --edge-line: transparent;
           --m: #17201F; --teal: #1F5F5B; --coral: #E9504F; --red: #C2410C; --warn: #B45309; --soft: .08; --edge: .3; }
  .dark  { --bg: #0F1413; --raised: #1E2725; --ink: #FFFFFF; --muted: #A9B4B1; --line: rgba(169,180,177,.3); --edge-line: #26302e;
           --m: #FFFFFF; --teal: #5CC1B5; --coral: #F0625E; --red: #E8703A; --warn: #F2B84B; --soft: .1; --edge: .35; }
  /* one tint: the key part is the tint too. The class two gives it the icon's second colour. */
  .ic { display: block; flex: none; --k: var(--m); }
  .ic.m-red { --m: var(--red); } .ic.m-warn { --m: var(--warn); }
  .ic.muted { --m: var(--muted); }
  .ic.on-coral { --m: #FFFFFF; --soft: .14; --edge: .4; }
  .ic.flat { --soft: 0; --edge: 0; }
  .ic.k-teal { --k: var(--teal); } .ic.k-coral { --k: var(--coral); } .ic.k-red { --k: var(--red); }

  .panel, .shot { position: relative; background: var(--bg); color: var(--ink); border-radius: 18px; padding: 30px 14px 14px; box-shadow: inset 0 0 0 1px var(--edge-line); }
  .tag { position: absolute; top: 9px; left: 14px; font-size: 11px; letter-spacing: .4px; text-transform: uppercase; color: var(--muted); font-weight: 600; }
  .two { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
  .set { display: grid; grid-template-columns: repeat(10, 1fr); row-gap: 14px; }
  .cell { display: flex; flex-direction: column; align-items: center; gap: 6px; font-size: 10px; color: var(--muted); }
  .pairs { display: grid; grid-template-columns: repeat(6, 1fr); }
  .pair { display: flex; flex-direction: column; align-items: center; gap: 3px; font-size: 10.5px; color: var(--muted); text-align: center; }
  .pair b { color: var(--ink); font-size: 12px; font-weight: 600; margin-top: 4px; }
  .duo { display: flex; align-items: center; gap: 6px; height: 50px; }
  .to { color: var(--muted); font-size: 16px; }
  .none { color: var(--muted); opacity: .5; }

  table.strip { border-collapse: collapse; width: 100%; }
  .strip th { text-align: left; font-weight: 500; font-size: 11.5px; color: var(--muted); padding-right: 6px; white-space: nowrap; width: 132px; }
  .strip td { text-align: center; height: 46px; }
  .strip td .ic { margin: 0 auto; }
  .strip .head td { height: 22px; font-size: 10.5px; color: var(--muted); }
  .btn { display: inline-flex; width: 40px; height: 40px; border-radius: 50%; background: #E9504F; align-items: center; justify-content: center; }
  .btn.big { width: 56px; height: 56px; }

  table.steps { border-collapse: collapse; width: 100%; table-layout: fixed; }
  .steps td { text-align: center; height: 66px; vertical-align: middle; }
  .steps td > .ic, .steps td > .btn { margin: 0 auto; }
  .steps .head td { height: auto; padding: 0 6px 8px; vertical-align: top; font-size: 11px; color: var(--muted); line-height: 1.25; }
  .steps .head b { display: block; color: var(--ink); font-size: 12px; font-weight: 600; margin-bottom: 2px; }
  .steps td + td { border-left: 1px solid var(--line); }
  .sizes { display: inline-flex; align-items: center; gap: 14px; }

  .ctx { margin-top: 16px; }
  .four { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
  .shot { padding: 30px 0 0; overflow: hidden; }
  .phone { font-size: 14px; }
  .hdr { height: 52px; display: flex; align-items: center; gap: 10px; padding: 0 14px 0 10px; border-bottom: 1px solid var(--line); }
  .hit { display: flex; width: 32px; justify-content: center; }
  .face { width: 32px; height: 32px; border-radius: 50%; background: #E9504F; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 600; font-size: 15px; flex: none; }
  .who { flex: 1; display: flex; flex-direction: column; line-height: 1.15; }
  .who b { font-size: 16px; font-weight: 600; } .who i { font-style: normal; font-size: 12px; color: var(--muted); } .who i.live { color: var(--teal); font-weight: 600; }
  .acts { display: flex; gap: 18px; }
  .chat { padding: 12px 12px 14px; display: flex; flex-direction: column; gap: 6px; }
  .chat.short { padding-bottom: 8px; }
  .b { max-width: 78%; padding: 8px 12px; border-radius: 16px; font-size: 14px; }
  .theirs { background: var(--raised); align-self: flex-start; }
  .mine { background: #23774A; color: #F2FBF5; align-self: flex-end; }
  .strip-bar { display: flex; align-items: center; gap: 10px; padding: 8px 12px 14px; }
  .field { flex: 1; height: 44px; border-radius: 22px; border: 1.5px solid #E9504F; display: flex; align-items: center; padding: 0 16px; color: var(--muted); }
  .round { width: 44px; height: 44px; border-radius: 50%; background: var(--raised); display: inline-flex; align-items: center; justify-content: center; flex: none; }
  .pushbtn { width: 60px; height: 60px; border-radius: 50%; background: #E9504F; display: inline-flex; align-items: center; justify-content: center; flex: none; box-shadow: 0 2px 8px rgba(233,80,79,.35); }
  .sect { font-size: 13px; font-weight: 600; color: var(--muted); padding: 4px 28px 8px; }
  .card { background: var(--raised); border-radius: 16px; margin: 0 12px 14px; }
  .row { display: flex; align-items: center; gap: 14px; padding: 11px 14px; }
  .row + .row { border-top: 1px solid var(--line); }
  .txt { flex: 1; display: flex; flex-direction: column; line-height: 1.25; min-width: 0; }
  .txt b { font-size: 15.5px; font-weight: 600; } .txt i { font-style: normal; font-size: 12.5px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
</head>
<body>
<svg width="0" height="0" style="position:absolute" aria-hidden="true">
${appDefs}
${appIcon}
${names.map(fineSymbol).join('\n')}
${Object.keys(now).map(nowSymbol).join('\n')}
</svg>

<div class="top">
  <svg class="app" viewBox="0 0 108 108" role="img" aria-label="The app icon"><use href="#i-oneroof"/></svg>
  <div>
    <h1>UI icons with the app icon's detail</h1>
    <p class="sub">The app icon is built from a front shape, a softer shape behind it, a thin light edge and one teal part. The small icons take the first three as opacities of a single tint, so every icon still takes one colour from where it is used, exactly as today. A second colour is the exception: six icons, and only where the colour means something.</p>
    <ul class="rules">
      <li><b>Line.</b> Full strength, 1.75 wide (2 today), round ends, corners 2.5. Today's shapes on today's 24-unit grid, a little rounder.</li>
      <li><b>Soft fill.</b> Every closed body is filled with the same tint at 8% (10% in dark): a hint of a second tone, never a grey shape.</li>
      <li><b>Solid part and inner edge.</b> One small part may be solid (the mic's head, the camera's lens, the bell's clapper, the server's lights), and a 1-unit line at 30% runs inside the body, top left. It reads at 32 and up and fades by itself at 24 and 18.</li>
      <li><b>Off.</b> A crossed-out icon has no solid part, and a gap around its bar.</li>
      <li><b>Second colour, six icons.</b> The solid part alone takes it: teal while live (Mic, Video, Talk, Call), coral for an urgent push (Bell), red for removing (Trash). Everywhere else these six are one tint like the rest.</li>
      <li><b>Plain ones stay plain.</b> Arrows, chevrons, Close, Plus, Check, Expand and Flip have no body to fill: only their weight changes.</li>
    </ul>
  </div>
</div>

<h2>The full set, 48 px, one tint</h2>
<p class="sub">Each icon is one colour with three strengths. Rows: live, people and chat, trust and system, plain. Error and CloudOff are shown in the colours the app gives them (red, the warning orange) to show the tint is still the screen's choice.</p>
<div class="two">${setPanel('light')}${setPanel('dark')}</div>
<h3 style="margin-top:16px">The exception: a second colour on the solid part</h3>
<div class="two">${twoPanel('light')}${twoPanel('dark')}</div>

<h2>At real size</h2>
<p class="sub">Twelve of the most used: as they are now, redrawn at 24 and 18, with the second colour where there is one, white on a coral button, and muted. In dark the teal is a brighter tint (#5CC1B5); the deep teal doesn't read as a small part on the dark page.</p>
<div class="two">${stripPanel('light')}${stripPanel('dark')}</div>
<h3 style="margin-top:16px">How the detail goes away</h3>
<div class="two">${degrade('light')}${degrade('dark')}</div>

<h2>In the app</h2>
<p class="sub">Mock screens, drawn here (not screenshots): today's icons beside the redrawn ones. Everything is one tint except the Talk icon in the header, which is teal because Dana is talking.</p>
${context('A chat’s header: Back, Video call, Voice call, and Talk while someone is talking', header)}
${context('The Push strip: the message line, Video, and the Push button (white on coral)', push)}
${context('A settings list: Bell, Lock, Shield, Server', settings)}
</body>
</html>
`;
fs.writeFileSync('ui-icons.html', html);
