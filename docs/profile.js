// KHOSQ Web, stage 11 (6k): my own profile. What the page writes here is what the phone writes (Repository.saveMyInfo / savePhoto / removePhoto /
// setHideSeen / syncSelf), field for field, so the phone shows a change made on the page exactly as it shows its own:
//  - name and "about":  users/<me> { name, about }                                  (merge; name at most 30, about at most 140 characters, both trimmed;
//                                                                                    an empty name keeps the old one)
//  - the picture:       photos/<me> { data, ts: version }, THEN users/<me> { pv: version }   (the rules refuse 90 KB or more)
//  - no picture:        photos/<me> deleted, THEN users/<me> { pv: 0 }
//  - "Show online":     users/<me> { hideSeen }   (the switch shows the opposite: hideSeen = false means "online" is shown)
//  - chats:             chats/<id> { "names.<me>": name, "pv.<me>": version } in every chat that still says the old name or picture. This is how
//                       a new name or picture reaches the others: the chat list of each of them reads it from the chat document (the phone's syncSelf).
// The page watches users/<me>, so a change made on the phone shows here too. The page never writes "last seen" (it does not tell the phone's
// "online" to anybody), so the switch only sets the same field the phone's switch sets.
// A write of mine is shown at once (the library tells the page about it before the server has it) and taken back by the library when the
// server refuses it; the chats are told only after the server has taken it. A failed write is reported.
import { cut } from "./send.js";

export const NAME_MAX = 30; // the phone's field for the name
export const ABOUT_MAX = 140; // and for "about"
export const PHOTO_LIMIT = 90000; // the rules refuse a picture of this many bytes or more

/** The name as the phone saves it: one line, trimmed, at most 30 characters. "" when nothing is left (then the old name stays). */
export const cleanName = (s) => cut(String(s == null ? "" : s).replace(/[\r\n]+/g, " ").trim(), NAME_MAX);

/** "About" as the phone saves it: trimmed, at most 140 characters (line breaks stay). */
export const cleanAbout = (s) => cut(String(s == null ? "" : s).replace(/\r\n?/g, "\n").trim(), ABOUT_MAX);

const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);

/** The name to show for me: my name, or my ID when there is none (the phone's Profile.name). */
export const shownName = (user) => (user && (user.name || user.handle)) || "";

/**
 * The chats that still say an old name or picture of mine: [{id, fields}], in the order given. A chat that says nothing yet counts as picture 0.
 * @param {Array<{id: string, names?: object, pv?: object}>} chats
 * @param {string} uid
 * @param {string} name  the name to be shown
 * @param {number} pv  my picture's version
 */
export function staleChats(chats, uid, name, pv) {
  if (!name) return []; // never write an empty name into somebody's chat list
  const out = [];
  for (const c of Array.isArray(chats) ? chats : []) {
    if (!c || typeof c.id !== "string" || !c.id) continue;
    const have = c.names && typeof c.names === "object" ? c.names[uid] : undefined;
    const ver = c.pv && typeof c.pv === "object" ? num(c.pv[uid]) : 0;
    if (have !== name || ver !== pv) out.push({ id: c.id, fields: { ["names." + uid]: name, ["pv." + uid]: pv } });
  }
  return out;
}

/**
 * @param {object} o
 * @param {{db: object, fsM: object}} o.f  the signed-in Firebase
 * @param {string} o.uid  my account id
 * @param {{name?: string, handle?: string, about?: string, pv?: number, hideSeen?: boolean}} o.user  my profile; changed IN PLACE, so every part
 *        of the page that holds it sees the new values
 * @param {(id: string, ver: number, bytes: Uint8Array) => (void|Promise<void>)} [o.cachePhoto]  called with a picture that was just saved, before
 *        the page shows it, so it is not read back
 * @param {() => void} [o.onChange]  called whenever [user] changed
 */
export function makeProfile({ f, uid, user, cachePhoto, onChange = () => {} }) {
  const { db, fsM } = f;
  const me = () => fsM.doc(db, "users", uid);
  const healed = new Set(); // "chat|name|version" of the changes already sent, so a refused one is not asked again and again
  let live = false; // the first answer of the watch has come: only then is what the page knows about me current enough to be written into chats
  let inflight = 0; // my own writes the server has not taken yet: what they change is not told to the chats until the server has taken it

  /**
   * Name and "about". Resolves {ok: true, name, about, committed} (the server has it when [committed] settles) or {ok: false, why}:
   * "same" (nothing changed).
   */
  function save(nameRaw, aboutRaw) {
    const name = cleanName(nameRaw) || shownName(user);
    const about = cleanAbout(aboutRaw);
    if (name === (user.name || "") && about === (user.about || "")) return { ok: false, why: "same" };
    const committed = (async () => {
      inflight++;
      try {
        await fsM.setDoc(me(), { name, about }, { merge: true });
      } finally {
        inflight--;
      }
      user.name = name;
      user.about = about;
      onChange();
    })();
    return { ok: true, name, about, committed };
  }

  /** A new picture: the picture first, then my pv, so nobody is told about a picture that is not there yet. [bytes] is a JPEG under 90 KB (media.js). */
  function setPhoto(bytes) {
    if (!(bytes instanceof Uint8Array) || !bytes.length) return { ok: false, why: "empty" };
    if (bytes.length >= PHOTO_LIMIT) return { ok: false, why: "big" };
    const ver = Math.max(Date.now(), num(user.pv) + 1);
    const committed = (async () => {
      inflight++;
      try {
        await fsM.setDoc(fsM.doc(db, "photos", uid), { data: fsM.Bytes.fromUint8Array(bytes), ts: ver });
        if (cachePhoto) await cachePhoto(uid, ver, bytes);
        await fsM.setDoc(me(), { pv: ver }, { merge: true });
      } finally {
        inflight--;
      }
      user.pv = ver;
      onChange();
    })();
    return { ok: true, ver, committed };
  }

  /** Takes the picture away: the picture is deleted and my pv goes to 0. */
  function removePhoto() {
    if (!(num(user.pv) > 0)) return { ok: false, why: "same" };
    const committed = (async () => {
      inflight++;
      try {
        await fsM.deleteDoc(fsM.doc(db, "photos", uid));
        await fsM.setDoc(me(), { pv: 0 }, { merge: true });
      } finally {
        inflight--;
      }
      user.pv = 0;
      onChange();
    })();
    return { ok: true, committed };
  }

  /** [on]: whether others may see that I am online (the phone's switch "Show online" is the opposite of the field hideSeen). */
  function setShowOnline(on) {
    const hide = on !== true;
    if ((user.hideSeen === true) === hide) return { ok: false, why: "same" };
    const committed = (async () => {
      inflight++;
      try {
        await fsM.setDoc(me(), { hideSeen: hide }, { merge: true });
      } finally {
        inflight--;
      }
      user.hideSeen = hide;
      onChange();
    })();
    return { ok: true, committed };
  }

  /**
   * Makes the chats say my current name and picture (the phone's syncPeople). Called with the chat list each time it changes and when my profile
   * changed. Each change is sent once. Does nothing until my profile has been read live, and while one of my own changes has not been taken by
   * the server (the page already shows it, but a change the server then refuses must not end up in anybody's chat list). Returns how many chats
   * were written.
   */
  function syncChats(chats) {
    if (!live || inflight > 0) return 0;
    let n = 0;
    for (const s of staleChats(chats, uid, shownName(user), num(user.pv))) {
      const key = s.id + "|" + s.fields["names." + uid] + "|" + s.fields["pv." + uid];
      if (healed.has(key)) continue;
      healed.add(key);
      n++;
      Promise.resolve()
        .then(() => fsM.updateDoc(fsM.doc(db, "chats", s.id), s.fields))
        .catch(() => {
          // a chat that does not take it (the rules, a chat that is gone) is left alone; the phone does the same
        });
    }
    return n;
  }

  /**
   * Follows users/<me>: a name, "about", picture or switch changed on the phone shows here. [onFirst] is called after the first answer.
   * Returns the function that stops it.
   */
  function watch(onFirst) {
    return fsM.onSnapshot(
      me(),
      (d) => {
        const x = d && d.exists && d.exists() ? d.data() : null;
        if (x) {
          if (typeof x.name === "string") user.name = x.name;
          if (typeof x.handle === "string") user.handle = x.handle;
          user.about = typeof x.about === "string" ? x.about : "";
          user.pv = num(x.pv);
          user.hideSeen = x.hideSeen === true;
        }
        const first = !live;
        live = true;
        onChange();
        if (first && onFirst) onFirst();
      },
      () => {
        // the watch ended (offline, rules): what the page knew stays; nothing is written into chats from it
      },
    );
  }

  return { save, setPhoto, removePhoto, setShowOnline, syncChats, watch };
}
