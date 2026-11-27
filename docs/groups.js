// KHOSQ Web, stage 9 (6i): groups. What the page writes here is what the phone writes (Repository.createGroup / addMembers / removeMember /
// renameGroup / setGroupPhoto / removeGroupPhoto / setGroupDesc / setAdminOnly / setAdmin / leaveGroup), field for field, so the phone shows
// a group made or changed on the page exactly as it shows its own:
//  - a group is chats/<new id> { group: true, title, members: [me, ...], admins: [me], pv, names, handles, status: "active", lastText: "",
//    lastTs, lastFrom }, and then one line "grp_created" in its messages;
//  - every change is ONE batch: the change in the chat document, and a line { from: me, text: "", kind: "sys", code, arg, ts } in the
//    messages ("grp_added" with the names, "grp_removed" with the name, "grp_renamed" with the new title ...). Every phone and every page
//    writes the line in its own language from the code;
//  - the picture is photos/<chat id> { data, ts: version } and the chat's gpv says which version is the newest (the rules refuse 90 KB or more);
//  - nothing needs a key: a group has no shared key. Each message is sealed with its own key, which is wrapped for the members the message is
//    sent to (send.js), so adding or removing a person changes who the NEXT messages are wrapped for and nothing else. A new member cannot read
//    what was sent before, a removed member cannot read what is sent after.
// The Firestore rules decide in the end (an admin may change anything in the group, a member may only leave); the checks here are the ones
// the phone's screen makes, so the page does not offer or send what the rules would refuse.
// Not here: the invitation link. The phone and the page ask the Worker for it, and joining by a link is done by the Worker, not by the page: see invite.js.
import { cut } from "./send.js";

export const TITLE_MAX = 40; // the phone's field for the name of a group
export const DESC_MAX = 200; // and for its description
export const PHOTO_LIMIT = 90000; // the rules refuse a picture of this many bytes or more

/** The name of a group as the phone saves it: trimmed, at most 40 characters. "" when nothing is left. */
export const cleanTitle = (s) => cut(String(s == null ? "" : s).replace(/[\r\n]+/g, " ").trim(), TITLE_MAX).trim();

/** The description as the phone saves it: trimmed, at most 200 characters (line breaks stay). */
export const cleanDesc = (s) => cut(String(s == null ? "" : s).replace(/\r\n?/g, "\n").trim(), DESC_MAX).trim();

const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);
const list = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === "string") : []);

/**
 * The people I may put into a group: my accepted one-to-one chats where nobody is blocked (the phone's contacts()). One entry per person,
 * sorted by name: {uid, name, handle, pv, chatId}.
 */
export function contactsOf(chats, uid) {
  const out = new Map();
  for (const c of Array.isArray(chats) ? chats : []) {
    if (!c || c.group === true || c.saved === true || (c.status || "active") !== "active") continue;
    if (c.blocked && typeof c.blocked === "object" && Object.keys(c.blocked).length) continue;
    const members = list(c.members);
    const peer = members.find((m) => m !== uid);
    if (!peer || out.has(peer)) continue;
    const name = c.names && typeof c.names[peer] === "string" ? c.names[peer] : "";
    const handle = c.handles && typeof c.handles[peer] === "string" ? c.handles[peer] : "";
    out.set(peer, { uid: peer, name: name || handle, handle, pv: c.pv ? num(c.pv[peer]) : 0, chatId: c.id });
  }
  return [...out.values()].sort((a, b) => a.name.localeCompare(b.name) || a.uid.localeCompare(b.uid));
}

/** Who writes what in a group (the phone's Chat.admins / isGroup): true when the chat is a real group (not my saved messages). */
export const isRealGroup = (chat) => Boolean(chat) && chat.group === true && chat.saved !== true;
export const adminsOf = (chat) => list(chat && chat.admins);
export const iAmAdmin = (chat, uid) => isRealGroup(chat) && adminsOf(chat).includes(uid);

/**
 * @param {object} o
 * @param {{db: object, fsM: object}} o.f  the signed-in Firebase
 * @param {string} o.uid  my account id
 * @param {{name?: string, handle?: string, pv?: number}} o.user  my own profile
 * @param {(id: string, ver: number, bytes: Uint8Array) => void} [o.cachePhoto]  called with a picture that was just saved, so it is shown at once
 *        without being read back
 */
export function makeGroups({ f, uid, user, cachePhoto }) {
  const { db, fsM } = f;
  const myName = () => (user && (user.name || user.handle)) || "";
  const chatRef = (id) => fsM.doc(db, "chats", id);
  const newLine = (id) => fsM.doc(fsM.collection(db, "chats", id, "messages"));
  /** "Ani added Gor": a code and an argument; every screen writes it in its own language. */
  const sys = (code, arg = "") => ({ from: uid, text: "", kind: "sys", code, arg, ts: fsM.serverTimestamp() });

  const notGroup = { ok: false, why: "no" };
  const notAdmin = { ok: false, why: "admin" };
  /** A change only an admin of a real group may make: null when it is allowed, otherwise what to answer. */
  const needAdmin = (chat) => (!isRealGroup(chat) ? notGroup : !adminsOf(chat).includes(uid) ? notAdmin : null);

  /** One batch: the change and its line. */
  function change(chat, fields, code, arg) {
    const b = fsM.writeBatch(db);
    b.update(chatRef(chat.id), fields);
    b.set(newLine(chat.id), sys(code, arg));
    return { ok: true, committed: b.commit() };
  }

  /** The people of [picks] that are real contacts of mine (and not me, not twice, not in [skip]): the rest is dropped. */
  function pickable(picks, skip = []) {
    const seen = new Set([uid, ...skip]);
    const out = [];
    for (const p of Array.isArray(picks) ? picks : []) {
      if (!p || typeof p.uid !== "string" || !p.uid || seen.has(p.uid)) continue;
      seen.add(p.uid);
      out.push(p);
    }
    return out;
  }

  /**
   * Makes a group (the phone's createGroup): I am its only admin. Resolves {ok: true, id, committed} (committed: the server has it) or
   * {ok: false, why}: "title" (nothing typed), "picks" (nobody chosen). The line "the group was made" is written after the group; if only that
   * line is refused the group is there all the same, so that is not an error.
   * @param {string} titleRaw
   * @param {Array<{uid: string, name: string, handle: string, pv?: number}>} picks  from contactsOf
   */
  function create(titleRaw, picks) {
    const title = cleanTitle(titleRaw);
    if (!title) return { ok: false, why: "title" };
    const chosen = pickable(picks);
    if (!chosen.length) return { ok: false, why: "picks" };
    const ref = fsM.doc(fsM.collection(db, "chats"));
    const names = { [uid]: myName() };
    const handles = { [uid]: (user && user.handle) || "" };
    for (const p of chosen) {
      names[p.uid] = p.name || p.handle || "";
      handles[p.uid] = p.handle || "";
    }
    const data = {
      group: true,
      title,
      members: [uid, ...chosen.map((p) => p.uid)],
      admins: [uid],
      pv: { [uid]: num(user && user.pv) },
      names,
      handles,
      status: "active",
      lastText: "",
      lastTs: Date.now(),
      lastFrom: uid,
    };
    const committed = (async () => {
      await fsM.setDoc(ref, data);
      try {
        await fsM.setDoc(newLine(ref.id), sys("grp_created"));
      } catch (e) {
        // the group is made; it only lacks the first line
      }
    })();
    return { ok: true, id: ref.id, committed };
  }

  /** Adds people to a group (addMembers): an admin only; people who are already in it are dropped. */
  function addMembers(chat, picks) {
    const no = needAdmin(chat);
    if (no) return no;
    const chosen = pickable(picks, list(chat.members));
    if (!chosen.length) return { ok: false, why: "picks" };
    const fields = { members: fsM.arrayUnion(...chosen.map((p) => p.uid)) };
    for (const p of chosen) {
      fields["names." + p.uid] = p.name || p.handle || "";
      fields["handles." + p.uid] = p.handle || "";
    }
    return change(chat, fields, "grp_added", chosen.map((p) => p.name || p.handle || "").join(", "));
  }

  /** Takes a person out of the group (removeMember): an admin only, somebody who is in it, not me (that is "leave"). */
  function removeMember(chat, who, name) {
    const no = needAdmin(chat);
    if (no) return no;
    if (!who || who === uid || !list(chat.members).includes(who)) return { ok: false, why: "no" };
    return change(chat, { members: fsM.arrayRemove(who), admins: fsM.arrayRemove(who) }, "grp_removed", String(name || ""));
  }

  /** A new name (renameGroup): an admin only; the same name is no change. */
  function rename(chat, titleRaw) {
    const no = needAdmin(chat);
    if (no) return no;
    const title = cleanTitle(titleRaw);
    if (!title) return { ok: false, why: "title" };
    if (title === (chat.title || "")) return { ok: false, why: "same" };
    return change(chat, { title }, "grp_renamed", title);
  }

  /** A new description (setGroupDesc); "" clears it. */
  function setDesc(chat, textRaw) {
    const no = needAdmin(chat);
    if (no) return no;
    const text = cleanDesc(textRaw);
    if (text === (typeof chat.desc === "string" ? chat.desc : "")) return { ok: false, why: "same" };
    return change(chat, { desc: text }, "grp_desc", "");
  }

  /** The announcement mode (setAdminOnly): on, only the admins may write. The rules enforce it; the page hides the box. */
  function setAdminOnly(chat, on) {
    const no = needAdmin(chat);
    if (no) return no;
    const now = (chat.sendMode || "all") !== "all";
    if (Boolean(on) === now) return { ok: false, why: "same" };
    return change(chat, { sendMode: on ? "admins" : "all" }, on ? "grp_mode_admins" : "grp_mode_all", "");
  }

  /** Makes a member an admin, or takes it away (setAdmin): an admin only, somebody who is in the group, not me. */
  function setAdmin(chat, who, name, on) {
    const no = needAdmin(chat);
    if (no) return no;
    if (!who || who === uid || !list(chat.members).includes(who)) return { ok: false, why: "no" };
    if (adminsOf(chat).includes(who) === Boolean(on)) return { ok: false, why: "same" };
    return change(chat, { admins: on ? fsM.arrayUnion(who) : fsM.arrayRemove(who) }, on ? "grp_admin_on" : "grp_admin_off", String(name || ""));
  }

  /**
   * A new picture (setGroupPhoto): the picture first, then the chat's gpv and the line, so nobody is told about a picture that is not there yet.
   * [bytes] is a JPEG under 90 KB (media.js makes it).
   */
  function setPhoto(chat, bytes) {
    const no = needAdmin(chat);
    if (no) return no;
    if (!(bytes instanceof Uint8Array) || !bytes.length) return { ok: false, why: "empty" };
    if (bytes.length >= PHOTO_LIMIT) return { ok: false, why: "big" };
    const ver = Math.max(Date.now(), num(chat.gpv) + 1);
    const committed = (async () => {
      await fsM.setDoc(fsM.doc(db, "photos", chat.id), { data: fsM.Bytes.fromUint8Array(bytes), ts: ver });
      if (cachePhoto) cachePhoto(chat.id, ver, bytes);
      const b = fsM.writeBatch(db);
      b.update(chatRef(chat.id), { gpv: ver });
      b.set(newLine(chat.id), sys("grp_photo"));
      await b.commit();
    })();
    return { ok: true, ver, committed };
  }

  /** Takes the picture away (removeGroupPhoto): the picture is deleted and gpv goes to 0. The phone writes no line for this. */
  function removePhoto(chat) {
    const no = needAdmin(chat);
    if (no) return no;
    if (!(num(chat.gpv) > 0)) return { ok: false, why: "same" };
    const committed = (async () => {
      await fsM.deleteDoc(fsM.doc(db, "photos", chat.id));
      await fsM.updateDoc(chatRef(chat.id), { gpv: 0 });
    })();
    return { ok: true, committed };
  }

  /**
   * Leaves the group (leaveGroup): any member. When I am an admin the others stay admins; when I was the only one, the first of the rest
   * becomes admin (so a group always has one while it has people). The line is written first, while I am still a member (and not at all
   * where the rules would refuse it: see below).
   */
  function leave(chat) {
    if (!isRealGroup(chat) || !list(chat.members).includes(uid)) return notGroup;
    const rest = list(chat.members).filter((m) => m !== uid);
    const fields = { members: fsM.arrayRemove(uid) };
    if (adminsOf(chat).includes(uid)) {
      const others = adminsOf(chat).filter((a) => a !== uid);
      fields.admins = others.length ? others : rest.slice(0, 1);
    }
    // The rules (canSend) let only an admin write a line in an announcement group, so a member who is not an admin and leaves from there cannot
    // write "left the group": the phone's batch (the line first) would be refused as a whole and that person could not leave at all. Here the
    // leaving is then the change of members alone.
    const mayWrite = (chat.sendMode || "all") === "all" || adminsOf(chat).includes(uid);
    if (!mayWrite) return { ok: true, committed: fsM.updateDoc(chatRef(chat.id), fields) };
    const b = fsM.writeBatch(db);
    b.set(newLine(chat.id), sys("grp_left"));
    b.update(chatRef(chat.id), fields);
    return { ok: true, committed: b.commit() };
  }

  return { create, addMembers, removeMember, rename, setDesc, setAdminOnly, setAdmin, setPhoto, removePhoto, leave };
}
