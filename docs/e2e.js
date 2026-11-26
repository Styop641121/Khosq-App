// KHOSQ Web, stage 2: opening what the phones sealed. This is the same arithmetic as crypto/E2E.kt (and the test of this page makes the Kotlin
// code seal messages and then opens them here, so the two cannot drift apart without a test failing).
//
// A one-to-one chat: ECDH (my private key, their public key) -> HKDF-SHA256 (salt = chat id, info "khosq-e2e-v1") -> AES-256-GCM key.
// Saved messages: the same with my own public key (salt "self"). A group message has its own key, wrapped for every member: the wrap is
// opened with the key I share with the sender (salt "wrap|<smaller id>|<larger id>"; "wrap|self" for my own messages).
// Every sealed piece is  iv(12) + ciphertext + tag(16)  in standard base64, bound to "<chat>|<sender>|<field>" (wraps: "w|<chat>|<from>|<to>").
//
// The private key here is the page's non-extractable key: this file can use it, never read it. Only reading is done in this stage.
const te = new TextEncoder();
const td = new TextDecoder();
const CURVE = { name: "ECDH", namedCurve: "P-256" };

export function fromB64(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function toB64(u8) {
  let bin = "";
  for (let i = 0; i < u8.length; i += 0x8000) bin += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(bin);
}

const importPublic = (text) => crypto.subtle.importKey("spki", fromB64(text), CURVE, true, []);
const aesKey = (raw) => crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["decrypt"]);

/** ECDH with my key, then HKDF-SHA256 with the salt, 32 bytes, as an AES key that can only decrypt. */
async function derive(myKey, theirPublic, salt) {
  const secret = await crypto.subtle.deriveBits({ name: "ECDH", public: theirPublic }, myKey, 256);
  const base = await crypto.subtle.importKey("raw", secret, "HKDF", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: te.encode(salt), info: te.encode("khosq-e2e-v1") }, base, 256);
  return aesKey(bits);
}

async function openRaw(key, aad, blob) {
  if (!blob || blob.length < 12 + 16) return null;
  try {
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: blob.subarray(0, 12), additionalData: te.encode(aad), tagLength: 128 }, key, blob.subarray(12));
    return new Uint8Array(plain);
  } catch (e) {
    return null;
  }
}

/**
 * @param {object} o
 * @param {string} o.uid  my account id
 * @param {CryptoKey} o.key  my private key (non-extractable, deriveBits)
 * @param {string} o.pub  my public key as the phone publishes it (standard base64 of the X.509 form)
 * @param {(uid: string) => Promise<string|null>} o.publicOf  the public key text the server holds for somebody
 * @param {{get: (uid: string) => Promise<string|undefined>, set: (uid: string, text: string) => Promise<void>}} o.pins  the keys seen first, kept in this browser
 */
export function makeE2E({ uid, key, pub, publicOf, pins }) {
  const chatKeys = new Map();
  const pairKeys = new Map();
  const peerKeys = new Map();
  const missUntil = new Map();
  const changed = new Set();
  let mine = null;
  let selfKey = null;

  const myPublic = () => (mine ||= importPublic(pub));

  /** The other member of a one-to-one chat, myself for "saved messages", null for groups. */
  const peerOf = (chatId) => {
    if (chatId === "saved" + uid) return uid;
    const p = chatId.split("_");
    if (p.length !== 2 || !p.includes(uid)) return null;
    return p[0] === uid ? p[1] : p[0];
  };
  const isGroup = (chatId) => peerOf(chatId) === null;

  async function peerPublic(peer) {
    if (peerKeys.has(peer)) return peerKeys.get(peer);
    let text = await pins.get(peer);
    if (!text) {
      if (Date.now() < (missUntil.get(peer) || 0)) return null;
      text = await publicOf(peer);
      if (!text) {
        // that person has no key yet (an old version of the app): ask again in a minute, not for every message
        missUntil.set(peer, Date.now() + 60000);
        return null;
      }
      try {
        await importPublic(text);
      } catch (e) {
        missUntil.set(peer, Date.now() + 60000);
        return null;
      }
      await pins.set(peer, text);
    }
    let k;
    try {
      k = await importPublic(text);
    } catch (e) {
      return null;
    }
    peerKeys.set(peer, k);
    return k;
  }

  const getSelfKey = async () => (selfKey ||= derive(key, await myPublic(), "self"));

  /** The key of a one-to-one chat or of my saved messages. Null for groups and when the other side has no key. */
  async function chatKey(chatId) {
    if (chatKeys.has(chatId)) return chatKeys.get(chatId);
    const peer = peerOf(chatId);
    if (peer === null) return null;
    let k;
    if (peer === uid) {
      k = await getSelfKey();
    } else {
      const pk = await peerPublic(peer);
      if (!pk) return null;
      k = await derive(key, pk, chatId);
    }
    chatKeys.set(chatId, k);
    return k;
  }

  async function pairKey(person) {
    if (pairKeys.has(person)) return pairKeys.get(person);
    let k;
    if (person === uid) {
      k = await derive(key, await myPublic(), "wrap|self");
    } else {
      const pk = await peerPublic(person);
      if (!pk) return null;
      const a = person < uid ? person : uid;
      const b = person < uid ? uid : person;
      k = await derive(key, pk, "wrap|" + a + "|" + b);
    }
    pairKeys.set(person, k);
    return k;
  }

  /** The key of a group message that [from] sent, taken out of the wrap that was made for me. */
  async function groupKey(chatId, from, wraps) {
    const mineWrap = wraps && wraps[uid];
    if (typeof mineWrap !== "string") return null;
    const pk = await pairKey(from);
    if (!pk) return null;
    let raw;
    try {
      raw = fromB64(mineWrap);
    } catch (e) {
      return null;
    }
    const plain = await openRaw(pk, "w|" + chatId + "|" + from + "|" + uid, raw);
    return plain ? aesKey(plain) : null;
  }

  async function openText(k, chatId, from, field, sealed) {
    let raw;
    try {
      raw = fromB64(sealed);
    } catch (e) {
      return null;
    }
    const plain = await openRaw(k, chatId + "|" + from + "|" + field, raw);
    return plain ? td.decode(plain) : null;
  }

  const openBytes = (k, chatId, from, field, sealed) => openRaw(k, chatId + "|" + from + "|" + field, sealed);

  /** The chat-list line (the last message), of any kind of chat. */
  async function openLast(chatId, from, sealed, wraps) {
    const k = isGroup(chatId) ? await groupKey(chatId, from, wraps) : await chatKey(chatId);
    return k ? openText(k, chatId, from, "l", sealed) : null;
  }

  /** Looks at the server's copy of a person's key once; if it is not the one remembered, the change is noted and the new key is used. */
  async function refreshPeer(peer) {
    if (peer === uid) return false;
    const text = await publicOf(peer);
    if (!text) return false;
    const pinned = await pins.get(peer);
    if (pinned === text) return false;
    try {
      await importPublic(text);
    } catch (e) {
      return false;
    }
    await pins.set(peer, text);
    peerKeys.delete(peer);
    pairKeys.delete(peer);
    for (const id of [...chatKeys.keys()]) if (id.includes(peer)) chatKeys.delete(id);
    missUntil.delete(peer);
    if (pinned) changed.add(peer);
    return Boolean(pinned);
  }

  return { peerOf, isGroup, chatKey, groupKey, openText, openBytes, openLast, refreshPeer, keyChanged: (peer) => changed.has(peer) };
}
