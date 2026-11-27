// KHOSQ Web, stage 7 (6g): starred messages. What the page writes and reads here is what the phone writes (Repository.setStar / removeStar / stars):
// a private copy of the message in MY OWN space, prefs/<me>/stars/<chat id>~<message id>, so the list never has to open the chats:
//   { chatId, chatName, fromName, mine, kind, text, enc, ts, at }
// The text (only for a text or a sticker: at most 600 / 40 characters) is sealed with the key made from my own key pair (E2E.sealSelf, label
// "star|<key>"), so even the server's copy of my list is unreadable, and the phone and the page open each other's stars. A photo, a voice note
// or a place keeps only its kind (the list shows "Photo", "Voice message" ...).
//
// One difference, on purpose: when the text cannot be sealed the phone stores it in the clear; the page stores nothing and says so.
import { cut } from "./send.js";

export const STAR_TEXT_MAX = 600; // a starred text is cut to this many characters (the phone's take(600))
export const STAR_STICKER_MAX = 40; // a sticker's id (the phone's take(40))
export const STAR_LIMIT = 300; // the list shows the newest 300 (the phone asks for no more)
const NO_STAR = new Set(["sos", "call", "sys"]); // an SOS card means something only where it was sent

/** The id of a star: "<chat id>~<message id>". */
export const starKey = (chatId, msgId) => chatId + "~" + msgId;

/**
 * What is kept for a message. Null when it cannot be starred: deleted, a call or group line, an SOS card, or one I cannot read.
 * @param {object} o
 * @param {string} o.chatId
 * @param {string} o.chatName  the other person's name or the group's title; "" for my saved messages (the list writes that in the reader's language)
 * @param {string} o.fromName  who wrote it (my own name when it is mine)
 * @param {boolean} o.mine
 * @param {{id: string, kind: string, ts: number, deleted?: boolean}} o.m
 * @param {{ok: boolean, text: string}} o.view  the message, opened
 * @param {number} o.now
 */
export function starFor({ chatId, chatName, fromName, mine, m, view, now }) {
  if (!m || !view || view.ok !== true || m.deleted || NO_STAR.has(m.kind)) return null;
  const text = m.kind === "text" ? cut(String(view.text || ""), STAR_TEXT_MAX) : m.kind === "sticker" ? cut(String(view.text || ""), STAR_STICKER_MAX) : "";
  return { key: starKey(chatId, m.id), chatId, chatName: chatName || "", fromName: fromName || "", mine: mine === true, kind: m.kind, text, ts: Number(m.ts) || 0, at: now };
}

const str = (v) => (typeof v === "string" ? v : "");

/**
 * @param {object} o
 * @param {{db: object, fsM: object}} o.f  the signed-in Firebase
 * @param {string} o.uid  my account id
 * @param {{sealSelf: Function, openSelf: Function}} o.e2e
 */
export function makeStars({ f, uid, e2e }) {
  const { db, fsM } = f;
  const ref = (key) => fsM.doc(db, "prefs", uid, "stars", key);

  /**
   * My starred messages, newest star first, live. The list is handed over once the texts are opened; a newer snapshot wins over an older one
   * that is still being opened. [onList] gets [{key, chatId, chatName, fromName, mine, kind, text, ts, at}].
   */
  function watch(onList, onError) {
    let seq = 0;
    const q = fsM.query(fsM.collection(db, "prefs", uid, "stars"), fsM.orderBy("at", "desc"), fsM.limit(STAR_LIMIT));
    return fsM.onSnapshot(
      q,
      async (snap) => {
        const mine = ++seq;
        const list = [];
        for (const d of snap.docs) {
          const x = d.data();
          const raw = str(x.text);
          let text = raw;
          if (x.enc === true && raw) {
            try {
              text = (await e2e.openSelf("star|" + d.id, raw)) || "";
            } catch (e) {
              text = "";
            }
          }
          list.push({
            key: d.id,
            chatId: str(x.chatId),
            chatName: str(x.chatName),
            fromName: str(x.fromName),
            mine: x.mine === true,
            kind: str(x.kind) || "text",
            text,
            ts: Number(x.ts) || 0,
            at: Number(x.at) || 0,
          });
        }
        if (mine === seq) onList(list);
      },
      onError,
    );
  }

  /** Puts a message on my list. Resolves {ok: true, committed} or {ok: false, why}; nothing readable is ever written. */
  async function add(star) {
    if (!star || typeof star.key !== "string" || !star.key.includes("~")) return { ok: false, why: "no" };
    let sealed = "";
    if (star.text) {
      try {
        sealed = await e2e.sealSelf("star|" + star.key, star.text);
      } catch (e) {
        sealed = "";
      }
      if (!sealed) return { ok: false, why: "noKey" };
    }
    const committed = fsM.setDoc(ref(star.key), {
      chatId: star.chatId,
      chatName: star.chatName,
      fromName: star.fromName,
      mine: star.mine === true,
      kind: star.kind,
      text: sealed,
      enc: sealed !== "",
      ts: star.ts,
      at: star.at,
    });
    return { ok: true, committed };
  }

  /** Takes a message off my list. */
  function remove(key) {
    if (typeof key !== "string" || !key.includes("~")) return { ok: false, why: "no" };
    return { ok: true, committed: fsM.deleteDoc(ref(key)) };
  }

  return { watch, add, remove };
}
