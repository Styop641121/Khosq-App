// KHOSQ Web, stage 2: opening what the phones sealed. This is the same arithmetic as crypto/E2E.kt (and the test of this page makes the Kotlin
// code seal messages and then opens them here, so the two cannot drift apart without a test failing).
//
// A one-to-one chat: ECDH (my private key, their public key) -> HKDF-SHA256 (salt = chat id, info "khosq-e2e-v1") -> AES-256-GCM key.
// Saved messages: the same with my own public key (salt "self"). A group message has its own key, wrapped for every member: the wrap is
// opened with the key I share with the sender (salt "wrap|<smaller id>|<larger id>"; "wrap|self" for my own messages).
// Every sealed piece is  iv(12) + ciphertext + tag(16)  in standard base64, bound to "<chat>|<sender>|<field>" (wraps: "w|<chat>|<from>|<to>").
//
// Sealing (stage 3, sending) is the same arithmetic the other way round: a random 12-byte IV, AES-256-GCM with the same bound text. A group
// message key is NOT random: like the phone, it is made from my own secret and the message id (HKDF, salt "<chat>|<message id>", info
// "khosq-msg-v1"), so that the phone can seal an edit of a message that was sent from here, and the other way round.
//
// The private key here is the page's non-extractable key: this file can use it, never read it.
import { digestOf, hex } from "./verify.js";

const te = new TextEncoder();
const td = new TextDecoder();
const CURVE = { name: "ECDH", namedCurve: "P-256" };
const FLAG = "!"; // in the remembered keys, "!<person>" = "1" says that person's key changed and is not acknowledged yet
const VER = "ver."; // and "ver.<person>" = the digest of the two keys at the moment I confirmed the pictures (the phone's Prefs "ver.<peer>"; kept in this browser only)

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
const BOTH = ["encrypt", "decrypt"];
/** An AES key the page cannot read back (not extractable). Keys of received messages only open; keys of chats also seal. */
const aesKey = (raw, usages = ["decrypt"]) => crypto.subtle.importKey("raw", raw, "AES-GCM", false, usages);

/** ECDH with my key, then HKDF-SHA256 with the salt: 32 bytes. */
async function deriveBits(myKey, theirPublic, salt) {
  const secret = await crypto.subtle.deriveBits({ name: "ECDH", public: theirPublic }, myKey, 256);
  const base = await crypto.subtle.importKey("raw", secret, "HKDF", false, ["deriveBits"]);
  return crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: te.encode(salt), info: te.encode("khosq-e2e-v1") }, base, 256);
}
const derive = async (myKey, theirPublic, salt) => aesKey(await deriveBits(myKey, theirPublic, salt), BOTH);

async function openRaw(key, aad, blob) {
  if (!blob || blob.length < 12 + 16) return null;
  try {
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: blob.subarray(0, 12), additionalData: te.encode(aad), tagLength: 128 }, key, blob.subarray(12));
    return new Uint8Array(plain);
  } catch (e) {
    return null;
  }
}

/** iv(12) + ciphertext + tag(16), bound to [aad]. */
async function sealRaw(key, aad, plain) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: te.encode(aad), tagLength: 128 }, key, plain));
  const out = new Uint8Array(12 + ct.length);
  out.set(iv, 0);
  out.set(ct, 12);
  return out;
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
  let selfBase = null;

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

  /** The secret the group message keys are made from (the same 32 bytes as the phone's selfKey), kept as a key that can only derive. */
  const getSelfBase = () =>
    (selfBase ||= (async () => crypto.subtle.importKey("raw", await deriveBits(key, await myPublic(), "self"), "HKDF", false, ["deriveBits"]))());

  /** The key of a group message I send: 32 bytes made from my secret and the message id, the same as the phone makes them. */
  async function messageKeyBits(chatId, msgId) {
    return new Uint8Array(
      await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: te.encode(chatId + "|" + msgId), info: te.encode("khosq-msg-v1") }, await getSelfBase(), 256),
    );
  }

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

  /** Seals a text for [field] (t text, r quote, l chat-list line, v vote) as the standard base64 the phone writes. */
  async function sealText(k, chatId, from, field, text) {
    return toB64(await sealRaw(k, chatId + "|" + from + "|" + field, te.encode(text)));
  }

  /** Seals bytes (a photo, a voice note) for [field] ("d") exactly as the phone does: iv (12) + ciphertext + tag (16), as raw bytes. */
  const sealBytes = (k, chatId, from, field, bytes) => sealRaw(k, chatId + "|" + from + "|" + field, bytes);

  /**
   * Text only I can read, for my starred list (the phone's E2E.sealSelf): sealed with the key made from my own key pair (salt "self"), bound to
   * "self|<label>". [label] is "star|<chat id>~<message id>". The standard base64 of iv (12) + ciphertext + tag (16).
   */
  async function sealSelf(label, text) {
    return toB64(await sealRaw(await getSelfKey(), "self|" + label, te.encode(text)));
  }

  /** The text that [sealSelf] (here or on the phone) sealed, or null when it is not mine, not for this label or damaged. */
  async function openSelf(label, sealed) {
    let raw;
    try {
      raw = fromB64(sealed);
    } catch (e) {
      return null;
    }
    const plain = await openRaw(await getSelfKey(), "self|" + label, raw);
    return plain ? td.decode(plain) : null;
  }

  /** The key of a message I sent myself, to change its text later: a group's is made again from my secret, otherwise the chat key. */
  async function sentKey(chatId, msgId) {
    return isGroup(chatId) ? aesKey(await messageKeyBits(chatId, msgId), BOTH) : chatKey(chatId);
  }

  /**
   * Starts sealing a new message. A one-to-one chat or my saved messages: the chat key. A group: a message key wrapped for every member (me
   * included). Null when it cannot be done now (the other person, or somebody in the group, has no key yet): the caller must then NOT send
   * the message in the clear, as an old phone would. {key, wraps} otherwise (wraps is null outside groups).
   */
  async function begin(chatId, msgId, others) {
    if (!isGroup(chatId)) {
      const k = await chatKey(chatId);
      return k ? { key: k, wraps: null } : null;
    }
    const raw = await messageKeyBits(chatId, msgId);
    const wraps = {};
    for (const m of new Set([...others, uid])) {
      const pk = await pairKey(m);
      if (!pk) return null;
      wraps[m] = toB64(await sealRaw(pk, "w|" + chatId + "|" + uid + "|" + m, raw));
    }
    return { key: await aesKey(raw, BOTH), wraps };
  }

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
    if (pinned) {
      changed.add(peer);
      // kept in the browser too: a reload must not make the warning disappear before somebody has looked at it
      await pins.set(FLAG + peer, "1");
    }
    return Boolean(pinned);
  }

  /** True while somebody's key has changed since it was first seen and nobody has pressed "I checked it" (the phone's pkchg). */
  async function keyChanged(peer) {
    if (changed.has(peer)) return true;
    if ((await pins.get(FLAG + peer)) === "1") {
      changed.add(peer);
      return true;
    }
    return false;
  }

  async function acknowledge(peer) {
    changed.delete(peer);
    await pins.set(FLAG + peer, "");
  }

  /**
   * SHA-256 of my public key text and the other person's (the phone's pairDigest): the same bytes on the phone and here while both hold the same two
   * keys. null for myself, for somebody whose key is not known yet, or while the page cannot compute it.
   */
  async function pairDigest(peer) {
    if (!peer || peer === uid) return null;
    if (!(await peerPublic(peer))) return null; // makes sure the key is remembered (the phone's peerPublic)
    const theirs = await pins.get(peer);
    if (!theirs) return null;
    try {
      return await digestOf(pub, theirs);
    } catch (e) {
      return null;
    }
  }

  /** What was saved when I confirmed the pictures with this person ("" when never). */
  async function verifiedWith(peer) {
    return (await pins.get(VER + peer)) || "";
  }

  /**
   * I confirmed that the pictures match: the digest of the two keys is saved, and a "key changed" warning is over (the phone's markVerified). False
   * while the key is not known. Throws when the browser would not keep the mark (pins.set swallows a refusal, so the saved value is read back):
   * the person is told, and nothing looks verified that is not.
   */
  async function markVerified(peer) {
    const d = await pairDigest(peer);
    if (!d) return false;
    const h = hex(d);
    await pins.set(VER + peer, h);
    if ((await pins.get(VER + peer)) !== h) throw new Error("the browser did not keep the mark");
    changed.delete(peer);
    await pins.set(FLAG + peer, "");
    return true;
  }

  /** Takes the confirmation away (the phone's clearVerified). Throws when the browser would not keep that either. */
  async function clearVerified(peer) {
    await pins.set(VER + peer, "");
    if ((await pins.get(VER + peer)) || "") throw new Error("the browser did not keep the change");
  }

  return { peerOf, isGroup, chatKey, groupKey, openText, openBytes, openLast, refreshPeer, keyChanged, acknowledge, pairDigest, verifiedWith, markVerified, clearVerified, begin, sealText, sealBytes, sentKey, messageKeyBits, sealSelf, openSelf };
}
