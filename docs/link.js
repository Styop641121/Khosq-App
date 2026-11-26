// KHOSQ web: linking this page to the phone by QR code. The same steps as data/LinkLogic.kt on the phone.
//   The page makes a one-time key pair (the private half never leaves this page and cannot be read back out of it) and shows the
//   public half in a QR code: KHOSQ-LINK1/<session>/<public key>. The phone seals the person's identity with a key made from both
//   halves (ECDH P-256 -> HKDF-SHA256, salt = session id, info = "khosq-link-v1" -> AES-256-GCM, bound to the session) and leaves it
//   in the Worker's post box. Only this page can open it.

export const PREFIX = "KHOSQ-LINK1";
const INFO = "khosq-link-v1";
const enc = new TextEncoder();
const dec = new TextDecoder();
const CURVE = { name: "ECDH", namedCurve: "P-256" };

export const b64u = (bytes) => {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};
export const unb64u = (t) => {
  const s = String(t).replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(s + "=".repeat((4 - (s.length % 4)) % 4)), (c) => c.charCodeAt(0));
};

/** Four digits made from the session and the key; the phone shows the same four, so the person can tell which code is being linked. */
export async function sasCode(sid, webPub) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(sid + "|" + webPub)));
  const n = ((d[0] << 24) >>> 0) + (d[1] << 16) + (d[2] << 8) + d[3];
  return String(n % 10000).padStart(4, "0");
}

/** A new session: the QR text, the four digits and the private key that opens the phone's answer. */
export async function newSession() {
  const pair = await crypto.subtle.generateKey(CURVE, false, ["deriveBits"]);
  const webPub = b64u(new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey)));
  const sid = b64u(crypto.getRandomValues(new Uint8Array(16)));
  return { sid, webPub, privateKey: pair.privateKey, qr: PREFIX + "/" + sid + "/" + webPub, code: await sasCode(sid, webPub) };
}

/**
 * Opens the phone's packet {eph, iv, ct}. Returns {uid, pub, priv (PKCS#8 bytes), r} or null when it is not a good packet
 * (wrong key, changed on the way, another session, anything unexpected).
 */
export async function openPacket(session, p) {
  try {
    const eph = await crypto.subtle.importKey("raw", unb64u(p.eph), CURVE, false, []);
    const secret = await crypto.subtle.deriveBits({ name: "ECDH", public: eph }, session.privateKey, 256);
    const hk = await crypto.subtle.importKey("raw", secret, "HKDF", false, ["deriveKey"]);
    const aes = await crypto.subtle.deriveKey(
      { name: "HKDF", hash: "SHA-256", salt: unb64u(session.sid), info: enc.encode(INFO) },
      hk,
      { name: "AES-GCM", length: 256 },
      false,
      ["decrypt"],
    );
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: unb64u(p.iv), additionalData: enc.encode(INFO + "|" + session.sid), tagLength: 128 },
      aes,
      unb64u(p.ct),
    );
    const o = JSON.parse(dec.decode(plain));
    if (o.v !== 1 || typeof o.uid !== "string" || typeof o.pub !== "string" || typeof o.priv !== "string" || typeof o.r !== "string") return null;
    return { uid: o.uid, pub: o.pub, priv: unb64u(o.priv), r: o.r };
  } catch (e) {
    return null;
  }
}

/** My private key as a key this page can use but never read back out (ECDH, the same curve the phone uses). */
export const importIdentityKey = (pkcs8) => crypto.subtle.importKey("pkcs8", pkcs8, CURVE, false, ["deriveBits"]);
