// Reading from Firestore, and nothing else: this file never writes (writing is in send.js and people.js). Everything here goes through the account the page signed in as, so
// the same rules that guard the phone guard the page (a chat can be read only by its members).
//
// Cost on the free plan: the chat list is one listener (one read per chat when it opens, then one per change); a chat shows its newest
// PAGE messages and asks for older ones only when the person presses the button.
export const PAGE = 40;

const millis = (v) => (v && typeof v.toMillis === "function" ? v.toMillis() : typeof v === "number" ? v : Date.now());
const bytesOf = (b) => (b && typeof b.toUint8Array === "function" ? b.toUint8Array() : null);

export function makeData(f, uid) {
  const { db, fsM } = f;

  const chatOf = (d) => ({ id: d.id, ...d.data() });

  /** A message the way the page uses it. The sealed parts stay sealed here; e2e.js opens them. */
  const messageOf = (d) => {
    const x = d.data({ serverTimestamps: "estimate" });
    return {
      id: d.id,
      from: x.from || "",
      text: typeof x.text === "string" ? x.text : "",
      ts: millis(x.ts),
      kind: x.kind || "text",
      data: bytesOf(x.data),
      dur: Number(x.dur) || 0,
      reply: typeof x.reply === "string" ? x.reply : "",
      replyFrom: x.replyFrom || "",
      deleted: x.deleted === true,
      reactions: x.reactions && typeof x.reactions === "object" ? x.reactions : {},
      fwd: x.fwd === true,
      code: x.code || "",
      arg: x.arg || "",
      vid: x.vid === true,
      edited: x.edited === true,
      enc: x.e === true,
      wraps: x.e === true && x.w && typeof x.w === "object" ? x.w : {},
      votes: x.votes && typeof x.votes === "object" ? x.votes : {},
      pending: d.metadata ? d.metadata.hasPendingWrites : false,
    };
  };

  return {
    /** My chats, live. */
    watchChats(onList, onError) {
      const q = fsM.query(fsM.collection(db, "chats"), fsM.where("members", "array-contains", uid));
      return fsM.onSnapshot(q, (snap) => onList(snap.docs.map(chatOf)), onError);
    },

    /** My own choices (pinned, muted, archived, marked as unread), live. */
    watchPrefs(onPrefs) {
      const list = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === "string") : []);
      return fsM.onSnapshot(
        fsM.doc(db, "prefs", uid),
        (d) => {
          const x = d.exists() ? d.data() : {};
          onPrefs({ pins: list(x.pins), muted: list(x.muted), archived: list(x.archived), marked: list(x.marked) });
        },
        () => onPrefs({ pins: [], muted: [], archived: [], marked: [] }),
      );
    },

    /** The newest [n] messages of a chat, live: onList(list oldest first, snapshot of the oldest one for paging, whether older ones may exist). */
    watchMessages(chatId, n, onList, onError) {
      const q = fsM.query(fsM.collection(db, "chats", chatId, "messages"), fsM.orderBy("ts", "desc"), fsM.limit(n));
      return fsM.onSnapshot(
        q,
        (snap) => {
          const docs = snap.docs;
          onList(docs.map(messageOf).reverse(), docs.length ? docs[docs.length - 1] : null, docs.length >= n);
        },
        onError,
      );
    },

    /** One page of older messages, once (not live). */
    async olderMessages(chatId, beforeDoc, n) {
      const q = fsM.query(fsM.collection(db, "chats", chatId, "messages"), fsM.orderBy("ts", "desc"), fsM.startAfter(beforeDoc), fsM.limit(n));
      const snap = await fsM.getDocs(q);
      const docs = snap.docs;
      return { list: docs.map(messageOf).reverse(), oldest: docs.length ? docs[docs.length - 1] : beforeDoc, more: docs.length >= n };
    },

    /** users/{id}: name, handle, public key ... or null. */
    async user(id) {
      try {
        const d = await fsM.getDoc(fsM.doc(db, "users", id));
        return d.exists() ? d.data() : null;
      } catch (e) {
        return null;
      }
    },

    /** photos/{id} (a person's or a group's picture): {v, bytes} or null. */
    async photo(id) {
      try {
        const d = await fsM.getDoc(fsM.doc(db, "photos", id));
        if (!d.exists()) return null;
        const x = d.data();
        const bytes = bytesOf(x.data);
        return bytes ? { v: Number(x.ts) || 0, bytes } : null;
      } catch (e) {
        return null;
      }
    },
  };
}
