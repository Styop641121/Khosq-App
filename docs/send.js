// KHOSQ Web, stage 3: writing. What the page writes into Firestore for a text message is what the phone writes (Repository.send in the app):
// a message document { from, text (sealed), ts, e, w (groups), reply, replyFrom } and, in the same batch, the chat document's preview
// ({ lastText, lastEnc, lastW, lastKind, lastTs, lastFrom }) and +1 on the unread counter of everybody else.
//
// One difference, on purpose: when a message cannot be sealed (the other person, or somebody in the group, has no key yet: an old version of
// the app), the phone falls back to sending it in the clear. The page never does: it sends nothing and says so.
import { STICKERS } from "./stickers.js";

export const MAX_TEXT = 4000; // the same limit as the phone's text field
export const MARK = ""; // starts the marker the phone stores for a quoted photo, voice message, location or sticker (REPLY_IMG ...)

/** The first [n] UTF-16 units (like Kotlin's take), without cutting an emoji or other surrogate pair in half. */
export function cut(s, n) {
  if (s.length <= n) return s;
  const head = s.slice(0, n);
  const last = head.charCodeAt(n - 1);
  return last >= 0xd800 && last <= 0xdbff ? head.slice(0, n - 1) : head;
}

function pollQuestion(text) {
  try {
    const j = JSON.parse(text);
    return j && typeof j.q === "string" ? j.q : "";
  } catch (e) {
    return "";
  }
}

/** What is stored for a quoted message: the text, or a marker for a photo, voice message, location or sticker (as Repository.replyOf). */
export function replyOf(m, view) {
  switch (m.kind) {
    case "img": return MARK + "img";
    case "voice": return MARK + "voice";
    case "loc": return MARK + "loc";
    case "poll": return "📊 " + cut(pollQuestion(view.text || ""), 70);
    case "sos": return "🆘 SOS";
    case "sticker": {
      const s = STICKERS[view.text || ""];
      return s ? s[0] + " " + s[1] : MARK + "sticker";
    }
    default: return cut(view.text || "", 80);
  }
}

/** Why nothing can be written to this chat right now: the conditions of the Firestore rules (canSend), or "" when writing is allowed. */
export function whyNot(chat, uid) {
  const blocked = chat.blocked && typeof chat.blocked === "object" ? Object.keys(chat.blocked).length > 0 : false;
  if (blocked) return "blocked";
  if ((chat.status || "active") !== "active" && (chat.requester || "") !== uid) return "pending";
  if ((chat.sendMode || "all") !== "all" && !(Array.isArray(chat.admins) && chat.admins.includes(uid))) return "admins";
  return "";
}

/**
 * @param {object} o
 * @param {object} o.f  the signed-in Firebase ({auth, db, fsM})
 * @param {string} o.uid  my account id
 * @param {string} o.name  my name (the title of the push for a one-to-one chat)
 * @param {object} o.e2e  docs/e2e.js
 * @param {string} o.worker  the Worker's address ("" = no push)
 */
export function makeSender({ f, uid, name, e2e, worker }) {
  const { db, fsM } = f;
  const delivered = new Map();

  /** Asks the Worker to wake the other phones. Best effort: the message itself is already in Firestore. No text goes along. */
  async function notify(chat) {
    if (!worker) return;
    try {
      const user = f.auth && f.auth.currentUser;
      if (!user || typeof user.getIdToken !== "function") return;
      const token = await user.getIdToken();
      const title = chat.group ? String(chat.title || "") : name;
      await fetch(worker, {
        method: "POST",
        headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
        body: JSON.stringify({ chatId: chat.id, type: "msg", title: cut(title, 60), body: "" }),
      });
    } catch (e) {
      // not delivered as a push; the other side still gets the message when it looks
    }
  }

  /**
   * Seals and writes a text message. Resolves at once with {ok:false, why} (nothing was written) or {ok:true, id, committed}: the message is
   * then already on the screen as "sending", and [committed] settles when the server has taken it (it rejects when the server refuses).
   * @param {object} chat  the chat document ({id, members, group, saved, title, status, requester, blocked, sendMode, admins})
   * @param {string} text
   * @param {{from: string, quote: string}|null} reply  who wrote the quoted message and what is stored for it (see replyOf)
   */
  async function sendText(chat, text, reply) {
    const body = String(text).replace(/\r\n?/g, "\n").trim();
    if (!body) return { ok: false, why: "empty" };
    if (body.length > MAX_TEXT) return { ok: false, why: "long" };
    const why = whyNot(chat, uid);
    if (why) return { ok: false, why };
    const members = Array.isArray(chat.members) ? chat.members : [];
    const others = chat.saved === true ? [] : chat.group === true ? members.filter((m) => m !== uid) : [e2e.peerOf(chat.id)].filter((m) => m && m !== uid);
    if (chat.group !== true && !others.length && chat.saved !== true) return { ok: false, why: "noChat" };

    const chatRef = fsM.doc(db, "chats", chat.id);
    const msgRef = fsM.doc(fsM.collection(db, "chats", chat.id, "messages"));
    let seal = null;
    try {
      seal = await e2e.begin(chat.id, msgRef.id, others);
    } catch (e) {
      seal = null;
    }
    if (!seal) return { ok: false, why: "noKey" };

    const fields = {
      from: uid,
      text: await e2e.sealText(seal.key, chat.id, uid, "t", body),
      ts: fsM.serverTimestamp(),
      e: true,
    };
    if (seal.wraps) fields.w = seal.wraps;
    if (reply) {
      fields.reply = await e2e.sealText(seal.key, chat.id, uid, "r", reply.quote);
      fields.replyFrom = reply.from;
    }
    const preview = {
      lastText: await e2e.sealText(seal.key, chat.id, uid, "l", body),
      lastEnc: true,
      lastW: seal.wraps || fsM.deleteField(),
      lastKind: "text",
      lastTs: Date.now(),
      lastFrom: uid,
    };
    for (const o of others) preview["unread." + o] = fsM.increment(1);

    const batch = fsM.writeBatch(db);
    batch.set(msgRef, fields);
    batch.update(chatRef, preview);
    // the push goes out once the server has the message, and only when somebody else is in the chat (not for my saved messages)
    const committed = batch.commit().then(() => (others.length ? notify(chat) : undefined));
    return { ok: true, id: msgRef.id, committed };
  }

  /** "Read": the unread counter of mine goes to 0 and the others' read ticks turn (the phone's markRead). */
  function markRead(chat) {
    return fsM
      .updateDoc(fsM.doc(db, "chats", chat.id), { ["unread." + uid]: 0, ["read." + uid]: Math.max(Date.now(), Number(chat.lastTs) || 0) })
      .catch(() => {});
  }

  /** "Delivered": the sender's grey double tick (the phone's Delivery.mark: at most once per new message). */
  function markDelivered(chat) {
    const last = Number(chat.lastTs) || 0;
    const mine = chat.dlv && typeof chat.dlv[uid] === "number" ? chat.dlv[uid] : 0;
    if (!chat.lastFrom || chat.lastFrom === uid || last <= 0 || last <= mine) return;
    if (last <= (delivered.get(chat.id) || 0)) return;
    delivered.set(chat.id, last);
    fsM.updateDoc(fsM.doc(db, "chats", chat.id), { ["dlv." + uid]: Math.max(Date.now(), last) }).catch(() => {});
  }

  return { sendText, markRead, markDelivered };
}
