// KHOSQ Web, stage 3: writing. What the page writes into Firestore for a text message is what the phone writes (Repository.send in the app):
// a message document { from, text (sealed), ts, e, w (groups), reply, replyFrom } and, in the same batch, the chat document's preview
// ({ lastText, lastEnc, lastW, lastKind, lastTs, lastFrom }) and +1 on the unread counter of everybody else.
//
// Stage 4 (6d) adds what the phone does to a message that is already there (Repository.react / editMessage / deleteForAll): a reaction
// (reactions.<me>), a changed text of my own message (re-sealed with the same message key, within 15 minutes), and "delete for everyone".
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

export const REACTIONS = ["👍", "❤️", "😂", "😮", "😢", "🙏"]; // the phone's six
export const EDIT_MS = 15 * 60 * 1000; // a text can be changed for 15 minutes (as on the phone)

const SYSTEM = new Set(["call", "sys"]);
const REAL = new Set(["text", "img", "voice", "loc", "poll", "sticker"]);

/** A message that can take a reaction, be answered, edited or deleted: a real message, not a call line or a group line, not deleted. */
export const isActionable = (m) => Boolean(m) && !m.deleted && !SYSTEM.has(m.kind);

/** May I change this text? Mine, a text, sealed (the page never rewrites a readable message), and not older than 15 minutes. */
export function canEdit(m, uid, now) {
  return isActionable(m) && m.from === uid && m.kind === "text" && m.enc === true && now - m.ts < EDIT_MS;
}

/** May I delete this for everyone? Only my own. */
export const canDelete = (m, uid) => isActionable(m) && m.from === uid;

/** The newest message of the kinds that show in the chat list (the phone's "isLast": the chat's preview is then this message). */
export function lastReal(list) {
  for (let i = list.length - 1; i >= 0; i--) if (REAL.has(list[i].kind)) return list[i];
  return null;
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
  const sending = new Set(); // ids of my new messages that the server has not taken yet (they show as "sending")

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
   * Seals and writes a text message. Resolves at once with {ok:false, why} (nothing was written) or {ok:true, id, taken, committed}: the message is
   * then already on the screen as "sending"; [taken] settles when the server has taken it (it rejects when the server refuses) and [committed]
   * when the push for it has gone out as well.
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
    sending.add(msgRef.id);
    const taken = batch.commit();
    const done = () => sending.delete(msgRef.id);
    taken.then(done, done);
    const committed = taken.then(() => (others.length ? notify(chat) : undefined));
    return { ok: true, id: msgRef.id, taken, committed };
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

  const msgRef = (chatId, id) => fsM.doc(db, "chats", chatId, "messages", id);

  /** A reaction of mine on a message (the same emoji again takes it back). Resolves when the server has it; rejects when it refuses. */
  function react(chat, m, emoji) {
    if (!isActionable(m) || !REACTIONS.includes(emoji)) return { ok: false, why: "no" };
    const mine = m.reactions && m.reactions[uid];
    const committed = fsM.updateDoc(msgRef(chat.id, m.id), { ["reactions." + uid]: mine === emoji ? fsM.deleteField() : emoji });
    return { ok: true, committed };
  }

  /**
   * Changes the text of my own message. The message key stays the same (a group's is made again from my secret and the message id, a
   * one-to-one chat's is the chat key), so the people who have the message can still open it. When it is the chat's newest message, the
   * chat-list line changes too. Nothing readable is written: a message that was not sealed is not edited.
   * @param {boolean} isLast  whether this is the chat's newest message (see lastReal)
   */
  async function edit(chat, m, text, isLast) {
    const body = String(text).replace(/\r\n?/g, "\n").trim();
    if (!canEdit(m, uid, Date.now())) return { ok: false, why: isActionable(m) && m.from === uid && m.kind === "text" && m.enc === true ? "late" : "no" };
    if (!body) return { ok: false, why: "empty" };
    if (body === m.text) return { ok: false, why: "same" };
    if (body.length > MAX_TEXT) return { ok: false, why: "long" };
    let key = null;
    try {
      key = await e2e.sentKey(chat.id, m.id);
    } catch (e) {
      key = null;
    }
    if (!key) return { ok: false, why: "noKey" };
    const batch = fsM.writeBatch(db);
    batch.update(msgRef(chat.id, m.id), { text: await e2e.sealText(key, chat.id, uid, "t", body), edited: true });
    if (isLast) {
      // the wraps already in the chat document still open the new line: it is the same message key
      batch.update(fsM.doc(db, "chats", chat.id), { lastText: await e2e.sealText(key, chat.id, uid, "l", body), lastEnc: true, lastKind: "text" });
    }
    return { ok: true, committed: batch.commit() };
  }

  /**
   * "Delete for everyone": the text, the picture or sound and the quote are taken out of the message (the phone's deleteForAll).
   * One addition on purpose: when it is a text and the chat's newest message, the chat-list line is cleared as well. The phone leaves
   * it, so the deleted text stays readable in everybody's chat list.
   */
  function remove(chat, m, isLast) {
    if (!canDelete(m, uid)) return { ok: false, why: "no" };
    const batch = fsM.writeBatch(db);
    batch.update(msgRef(chat.id, m.id), { deleted: true, text: "", data: fsM.deleteField(), reply: fsM.deleteField() });
    if (isLast && m.kind === "text") {
      batch.update(fsM.doc(db, "chats", chat.id), { lastText: "", lastEnc: false, lastW: fsM.deleteField(), lastKind: "deleted" });
    }
    return { ok: true, committed: batch.commit() };
  }

  return { sendText, markRead, markDelivered, react, edit, remove, sending };
}
