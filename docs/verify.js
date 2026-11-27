// KHOSQ Web, stage 12 (6l): verifying a contact. The same arithmetic as the phone's data/Verify.kt (VerifyLogic.digest / numberText / emojis /
// state), so the web page and the phone show the SAME safety number and the SAME eight pictures for a pair of people, as long as both hold the same
// two public keys: SHA-256 of the two public key texts (the smaller text first, UTF-8), the safety number is the first 16 bytes in groups of four hex
// digits, and the eight pictures are the first 6 bytes cut into eight 6-bit pieces, each one a picture out of 64.

/** 64 pictures, each from one code point (the phone's list, in the phone's order). */
export const EMOJIS = [
  0x1f436, 0x1f431, 0x1f42d, 0x1f439, 0x1f430, 0x1f98a, 0x1f43b, 0x1f43c, 0x1f428, 0x1f42f, 0x1f981, 0x1f42e,
  0x1f437, 0x1f438, 0x1f435, 0x1f414, 0x1f427, 0x1f422, 0x1f40d, 0x1f419, 0x1f420, 0x1f42c, 0x1f433, 0x1f98b,
  0x1f34e, 0x1f34c, 0x1f347, 0x1f353, 0x1f352, 0x1f351, 0x1f34b, 0x1f349, 0x1f355, 0x1f369, 0x1f36a, 0x1f33d,
  0x1f332, 0x1f335, 0x1f33b, 0x1f339, 0x1f344, 0x1f319, 0x2b50, 0x1f525, 0x26a1, 0x2614,
  0x1f388, 0x1f381, 0x1f3b5, 0x1f514, 0x1f511, 0x1f680, 0x1f6b2, 0x26bd, 0x1f3c0, 0x1f4f7, 0x1f4a1, 0x23f0,
  0x1f3a9, 0x1f451, 0x1f48e, 0x1f697, 0x26f5, 0x1f3b8,
].map((cp) => String.fromCodePoint(cp));

/** How many pictures make the code: 8 of 64 = 48 bits. */
export const CODE_LENGTH = 8;

/** SHA-256 of the two public key texts, the smaller text first (compared like the phone's String compareTo: by UTF-16 code units). */
export async function digestOf(a, b, subtle = globalThis.crypto.subtle) {
  const x = String(a);
  const y = String(b);
  const [first, second] = x <= y ? [x, y] : [y, x];
  const enc = new TextEncoder();
  const f = enc.encode(first);
  const s = enc.encode(second);
  const all = new Uint8Array(f.length + s.length);
  all.set(f, 0);
  all.set(s, f.length);
  return new Uint8Array(await subtle.digest("SHA-256", all));
}

/** The digest in lower-case hex, 64 characters (what the phone saves when somebody is verified). */
export const hex = (d) => Array.from(d, (x) => (x & 0xff).toString(16).padStart(2, "0")).join("");

/** The long safety number: the first 16 bytes in groups of four hex digits, upper case, like "A1B2 C3D4 ...". */
export function numberText(d) {
  let s = "";
  for (let i = 0; i < 16; i++) s += (d[i] & 0xff).toString(16).toUpperCase().padStart(2, "0");
  return s.match(/.{4}/g).join(" ");
}

/** The short code: the first 6 bytes cut into eight 6-bit pieces, each a picture. */
export function emojis(d) {
  let bits = 0n;
  for (let i = 0; i < 6; i++) bits = (bits << 8n) | BigInt(d[i] & 0xff);
  const out = [];
  for (let i = 0; i < CODE_LENGTH; i++) out.push(EMOJIS[Number((bits >> BigInt(42 - 6 * i)) & 63n)]);
  return out;
}

/** "none" (never confirmed, or the key is not known), "verified", or "changed" (confirmed once, the keys are not the same any more). */
export const verifyState = (stored, current) => (!stored || !current ? "none" : stored === current ? "verified" : "changed");
