// KHOSQ Web, stage 6 (6f): people and my chat list. What the page writes here is what the phone writes (Repository.findByHandle / startChat /
// acceptChat / setBlocked / setPref):
//  - a new chat is a document chats/<smaller id>_<larger id> { members, names, handles, status: "pending", requester: me, pv }, so the other
//    person sees "wants to write to you" until they accept (the Firestore rules allow exactly this shape);
//  - accepting a request is { status: "active" }, blocking is { "blocked.<me>": true } (and a field delete to unblock);
//  - my private choices (pinned, muted, archived, "marked unread") are lists in prefs/<me>, changed with arrayUnion / arrayRemove.
// Nothing here reads a message and nothing is sent in the clear: a request carries only names and IDs, which the phone also puts there.

/** What the phone lets into an ID field (Home.kt cleanHandle): letters a-z, digits, _ and -, at most 30. */
export const HANDLE_MAX = 30;
export const cleanHandle = (s) =>
  String(s || "")
    .toLowerCase()
    .replace(/[^a-z0-9_-]/g, "")
    .slice(0, HANDLE_MAX);

/** The id of the one-to-one chat of two people: the smaller id first (Repository.chatId, and the rules' dmId). */
export const chatIdOf = (a, b) => (a < b ? a + "_" + b : b + "_" + a);

/** The four private lists the phone keeps in prefs/<me>. */
export const PREF_FIELDS = ["pins", "muted", "archived", "marked"];

/**
 * @param {object} o
 * @param {{db: object, fsM: object}} o.f  the signed-in Firebase
 * @param {string} o.uid  my account id
 * @param {{name?: string, handle?: string, pv?: number}} o.user  my own profile
 */
export function makePeople({ f, uid, user }) {
  const { db, fsM } = f;
  const myName = () => (user && (user.name || user.handle)) || "";

  /**
   * Looks for a person by their ID (what the phone calls findByHandle). {ok: true, peer: {uid, handle, name, pv}} or {ok: false, why}:
   * "empty" (nothing typed), "notFound", "self" (my own ID) or "error" (the server could not be asked).
   */
  async function find(raw) {
    const h = cleanHandle(raw);
    if (!h) return { ok: false, why: "empty" };
    try {
      const hd = await fsM.getDoc(fsM.doc(db, "handles", h));
      const who = hd.exists() ? hd.data().uid : null;
      if (typeof who !== "string" || !who) return { ok: false, why: "notFound" };
      if (who === uid) return { ok: false, why: "self" };
      const ud = await fsM.getDoc(fsM.doc(db, "users", who));
      const x = ud.exists() ? ud.data() : null;
      if (!x || typeof x.handle !== "string" || !x.handle) return { ok: false, why: "notFound" };
      return { ok: true, peer: { uid: who, handle: x.handle, name: typeof x.name === "string" && x.name ? x.name : x.handle, pv: Number(x.pv) || 0 } };
    } catch (e) {
      return { ok: false, why: "error" };
    }
  }

  /**
   * Opens the chat with [peer], making it as a request when there is none yet (the phone's startChat). {ok: true, id, created} or
   * {ok: false, why: "error"}. An old chat is never touched, whatever state it is in (blocked, archived ...): it is only opened.
   */
  async function start(peer) {
    const id = chatIdOf(uid, peer.uid);
    const ref = fsM.doc(db, "chats", id);
    let exists;
    try {
      exists = (await fsM.getDoc(ref)).exists();
    } catch (e) {
      return { ok: false, why: "error" };
    }
    if (exists) return { ok: true, id, created: false };
    const mine = Number(user && user.pv) || 0;
    const pv = { [uid]: mine };
    if (peer.pv > 0) pv[peer.uid] = peer.pv; // so the picture of the other person shows at once; the phone fills it in later
    try {
      await fsM.setDoc(ref, {
        members: [uid, peer.uid],
        names: { [uid]: myName(), [peer.uid]: peer.name },
        handles: { [uid]: (user && user.handle) || "", [peer.uid]: peer.handle },
        status: "pending",
        requester: uid,
        pv,
      });
    } catch (e) {
      return { ok: false, why: "error" };
    }
    return { ok: true, id, created: true };
  }

  /** A request that somebody else made to me is accepted. Nothing else can be accepted. */
  function accept(chat) {
    if (!chat || chat.group === true || (chat.status || "active") !== "pending" || !chat.requester || chat.requester === uid) return { ok: false, why: "no" };
    return { ok: true, committed: fsM.updateDoc(fsM.doc(db, "chats", chat.id), { status: "active" }) };
  }

  /** Blocks or unblocks the other person of a one-to-one chat (not a group, not my saved messages). Only my own block is touched. */
  function setBlocked(chat, on) {
    if (!chat || chat.group === true || chat.saved === true) return { ok: false, why: "no" };
    return { ok: true, committed: fsM.updateDoc(fsM.doc(db, "chats", chat.id), { ["blocked." + uid]: on ? true : fsM.deleteField() }) };
  }

  /** Puts a chat on one of my lists (pins, muted, archived, marked), or takes it off. One small write into my own space. */
  function setPref(field, chatId, on) {
    if (!PREF_FIELDS.includes(field) || typeof chatId !== "string" || !chatId) return { ok: false, why: "no" };
    const change = on ? fsM.arrayUnion(chatId) : fsM.arrayRemove(chatId);
    return { ok: true, committed: fsM.setDoc(fsM.doc(db, "prefs", uid), { [field]: change }, { merge: true }) };
  }

  return { find, start, accept, setBlocked, setPref };
}
