// A small QR code maker (byte mode, error correction L or M, versions 1 to 10), so the page needs no outside script.
// The method is the standard one (ISO/IEC 18004): data -> Reed-Solomon blocks -> interleave -> modules -> best of 8 masks.

const ECC = { L: { bits: 1, ec: [7, 10, 15, 20, 26, 18, 20, 24, 30, 18], blocks: [[1, 19], [1, 34], [1, 55], [1, 80], [1, 108], [2, 68], [2, 78], [2, 97], [2, 116], [2, 68, 2, 69]] },
  M: { bits: 0, ec: [10, 16, 26, 18, 24, 16, 18, 22, 22, 26], blocks: [[1, 16], [1, 28], [1, 44], [2, 32], [2, 43], [4, 27], [4, 31], [2, 38, 2, 39], [3, 36, 2, 37], [4, 43, 1, 44]] } };
const ALIGN = [[], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50]];

// ---- Reed-Solomon over GF(256), primitive polynomial 0x11D
const EXP = new Uint8Array(512);
const LOG = new Uint8Array(256);
(() => {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    EXP[i] = x;
    LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
})();
const mul = (a, b) => (a === 0 || b === 0 ? 0 : EXP[LOG[a] + LOG[b]]);

function generator(n) {
  let g = [1];
  for (let i = 0; i < n; i++) {
    const next = new Array(g.length + 1).fill(0);
    for (let j = 0; j < g.length; j++) {
      next[j] ^= g[j];
      next[j + 1] ^= mul(g[j], EXP[i]);
    }
    g = next;
  }
  return g;
}

function remainder(data, n) {
  const g = generator(n);
  const r = new Array(n).fill(0);
  for (const d of data) {
    const f = d ^ r.shift();
    r.push(0);
    if (f !== 0) for (let i = 0; i < n; i++) r[i] ^= mul(g[i + 1], f);
  }
  return r;
}

// ---- the codewords
function layout(version, level) {
  const e = ECC[level];
  const b = e.blocks[version - 1];
  const sizes = [];
  for (let i = 0; i < b.length; i += 2) for (let k = 0; k < b[i]; k++) sizes.push(b[i + 1]);
  return { ecPer: e.ec[version - 1], sizes, data: sizes.reduce((a, c) => a + c, 0) };
}

/** The most text (bytes) a code of this level can carry. */
export function capacity(level) {
  let best = 0;
  for (let v = 1; v <= 10; v++) best = Math.max(best, layout(v, level).data - 2 - (v >= 10 ? 1 : 0));
  return best;
}

function codewords(bytes, version, level) {
  const { ecPer, sizes, data } = layout(version, level);
  const bits = [];
  const put = (v, n) => {
    for (let i = n - 1; i >= 0; i--) bits.push((v >>> i) & 1);
  };
  put(4, 4); // byte mode
  put(bytes.length, version >= 10 ? 16 : 8);
  for (const b of bytes) put(b, 8);
  const cap = data * 8;
  put(0, Math.min(4, cap - bits.length));
  while (bits.length % 8) bits.push(0);
  const cw = [];
  for (let i = 0; i < bits.length; i += 8) cw.push(parseInt(bits.slice(i, i + 8).join(""), 2));
  for (let pad = 0xec; cw.length < data; pad ^= 0xec ^ 0x11) cw.push(pad);
  // split into blocks, add the error correction of each, and interleave
  const blocks = [];
  let at = 0;
  for (const s of sizes) {
    const d = cw.slice(at, at + s);
    at += s;
    blocks.push({ d, e: remainder(d, ecPer) });
  }
  const out = [];
  const longest = Math.max(...sizes);
  for (let i = 0; i < longest; i++) for (const b of blocks) if (i < b.d.length) out.push(b.d[i]);
  for (let i = 0; i < ecPer; i++) for (const b of blocks) out.push(b.e[i]);
  return out;
}

// ---- the picture
function bch(value, poly, bitsLen) {
  let r = value;
  const top = (n) => 31 - Math.clz32(n);
  while (top(r) >= bitsLen) r ^= poly << (top(r) - bitsLen);
  return r;
}

function build(cw, version, level, mask) {
  const n = version * 4 + 17;
  const m = Array.from({ length: n }, () => new Array(n).fill(false));
  const fn = Array.from({ length: n }, () => new Array(n).fill(false)); // function patterns: not data
  const set = (x, y, v) => {
    m[y][x] = v;
    fn[y][x] = true;
  };
  const finder = (cx, cy) => {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const x = cx + dx;
        const y = cy + dy;
        if (x < 0 || y < 0 || x >= n || y >= n) continue;
        const d = Math.max(Math.abs(dx), Math.abs(dy));
        set(x, y, d !== 2 && d !== 4);
      }
    }
  };
  finder(3, 3);
  finder(n - 4, 3);
  finder(3, n - 4);
  for (let i = 8; i < n - 8; i++) {
    set(i, 6, i % 2 === 0);
    set(6, i, i % 2 === 0);
  }
  const pos = ALIGN[version - 1];
  for (const cy of pos) {
    for (const cx of pos) {
      if ((cx === 6 && cy === 6) || (cx === 6 && cy === n - 7) || (cx === n - 7 && cy === 6)) continue;
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
  }
  // format information (15 bits) twice, and the dark module
  const fmt = ((((ECC[level].bits << 3) | mask) << 10) | bch((ECC[level].bits << 3 | mask) << 10, 0x537, 10)) ^ 0x5412;
  const fbit = (i) => ((fmt >>> i) & 1) === 1;
  for (let i = 0; i <= 5; i++) set(8, i, fbit(i));
  set(8, 7, fbit(6));
  set(8, 8, fbit(7));
  set(7, 8, fbit(8));
  for (let i = 9; i < 15; i++) set(14 - i, 8, fbit(i));
  for (let i = 0; i < 8; i++) set(n - 1 - i, 8, fbit(i));
  for (let i = 8; i < 15; i++) set(8, n - 15 + i, fbit(i));
  set(8, n - 8, true);
  if (version >= 7) {
    const v = (version << 12) | bch(version << 12, 0x1f25, 12);
    for (let i = 0; i < 18; i++) {
      const bit = ((v >>> i) & 1) === 1;
      const a = n - 11 + (i % 3);
      const b = Math.floor(i / 3);
      set(a, b, bit);
      set(b, a, bit);
    }
  }
  // the data, in the zig-zag order, with the mask
  const total = cw.length * 8;
  let bit = 0;
  for (let right = n - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let v = 0; v < n; v++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        const upward = ((right + 1) & 2) === 0;
        const y = upward ? n - 1 - v : v;
        if (fn[y][x]) continue;
        let dark = bit < total ? ((cw[bit >>> 3] >>> (7 - (bit & 7))) & 1) === 1 : false;
        bit++;
        const inv = [(x + y) % 2 === 0, y % 2 === 0, x % 3 === 0, (x + y) % 3 === 0, (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
          ((x * y) % 2) + ((x * y) % 3) === 0, (((x * y) % 2) + ((x * y) % 3)) % 2 === 0, (((x + y) % 2) + ((x * y) % 3)) % 2 === 0][mask];
        m[y][x] = inv ? !dark : dark;
      }
    }
  }
  return m;
}

function penalty(m) {
  const n = m.length;
  let p = 0;
  const run = (get) => {
    for (let a = 0; a < n; a++) {
      let len = 1;
      for (let b = 1; b < n; b++) {
        if (get(a, b) === get(a, b - 1)) {
          len++;
          if (len === 5) p += 3;
          else if (len > 5) p++;
        } else len = 1;
      }
    }
  };
  run((a, b) => m[a][b]);
  run((a, b) => m[b][a]);
  for (let y = 0; y < n - 1; y++) for (let x = 0; x < n - 1; x++) if (m[y][x] === m[y][x + 1] && m[y][x] === m[y + 1][x] && m[y][x] === m[y + 1][x + 1]) p += 3;
  const pat = [true, false, true, true, true, false, true, false, false, false, false];
  const hit = (get) => {
    for (let a = 0; a < n; a++) {
      for (let b = 0; b + 11 <= n; b++) {
        let f = true;
        let r = true;
        for (let k = 0; k < 11; k++) {
          if (get(a, b + k) !== pat[k]) f = false;
          if (get(a, b + k) !== pat[10 - k]) r = false;
        }
        if (f) p += 40;
        if (r) p += 40;
      }
    }
  };
  hit((a, b) => m[a][b]);
  hit((a, b) => m[b][a]);
  let dark = 0;
  for (const row of m) for (const c of row) if (c) dark++;
  p += Math.floor(Math.abs((dark * 100) / (n * n) - 50) / 5) * 10;
  return p;
}

/** The modules of a QR code for [text] (UTF-8): a square array of booleans (true = dark). Throws when the text is too long. */
export function makeQr(text, level = "M") {
  const bytes = new TextEncoder().encode(text);
  for (let version = 1; version <= 10; version++) {
    const l = layout(version, level);
    if (bytes.length > l.data - 2 - (version >= 10 ? 1 : 0)) continue;
    const cw = codewords(bytes, version, level);
    let best = null;
    let bestScore = Infinity;
    for (let mask = 0; mask < 8; mask++) {
      const m = build(cw, version, level, mask);
      const score = penalty(m);
      if (score < bestScore) {
        best = m;
        bestScore = score;
      }
    }
    return best;
  }
  throw new Error("text too long for a code");
}

/** The code as an SVG picture: dark squares on white, with the quiet border a scanner needs. */
export function qrSvg(text, level = "M", quiet = 4) {
  const m = makeQr(text, level);
  const n = m.length + quiet * 2;
  let d = "";
  for (let y = 0; y < m.length; y++) {
    let x = 0;
    while (x < m.length) {
      if (!m[y][x]) {
        x++;
        continue;
      }
      let w = 1;
      while (x + w < m.length && m[y][x + w]) w++;
      d += "M" + (x + quiet) + " " + (y + quiet) + "h" + w + "v1h-" + w + "z";
      x += w;
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${n} ${n}" shape-rendering="crispEdges" role="img" aria-label="QR"><rect width="${n}" height="${n}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
}
