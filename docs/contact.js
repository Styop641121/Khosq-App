// KHOSQ Web, stage 12 (6l): what the page knows about the other person of a one-to-one chat, as the phone's contact card shows it:
//  - users/<them>  {lastSeen, hideSeen, about}  -> "online" / "last seen ..." and the line "about" (the phone's Repository.userInfo),
//  - the groups the two of us are both in,
//  - the verification: the safety number and the eight pictures (verify.js) and the mark "verified" (e2e.js keeps it in this browser).
// It only READS people's documents; it writes nothing to the database. (The page never writes "last seen" of its own.)
import { emojis, numberText, verifyState, hex } from "./verify.js";

/** "Online" means the last sign of life is newer than this (the phone's 100 seconds). */
export const ONLINE_MS = 100_000;
export const MAX_GROUPS = 10; // the phone's take(10)

/** A time of the database (a Timestamp, a number, or the shapes a test may use) in milliseconds; 0 when there is none. */
export function millisOf(v) {
  if (v && typeof v.toMillis === "function") return num(v.toMillis());
  if (v && typeof v.toDate === "function") return num(v.toDate().getTime());
  if (typeof v === "number") return num(v);
  if (v && typeof v === "object") {
    const s = v.seconds ?? v._seconds;
    if (typeof s === "number") return num(s * 1000 + Math.floor(num(v.nanoseconds ?? v._nanoseconds) / 1e6));
  }
  return 0;
}
const num = (v) => (Number.isFinite(Number(v)) && Number(v) > 0 ? Number(v) : 0);

/**
 * What the database says about one person: {seen, about, hide}. [seen] is 0 when the person hides it or there is nothing; [about] is "" when
 * nothing is written (the phone's UserInfo). [data] is the document's data, or null when the document is not there.
 */
export function infoOf(data) {
  const x = data && typeof data === "object" ? data : {};
  const hide = x.hideSeen === true;
  return { seen: hide ? 0 : millisOf(x.lastSeen), about: typeof x.about === "string" ? x.about : "", hide };
}

/** The word to show: {kind: "online"} / {kind: "seen", ts} / {kind: "none"}. */
export function statusOf(info, now) {
  const s = info && info.seen > 0 ? info.seen : 0;
  if (!s) return { kind: "none", ts: 0 };
  return now - s < ONLINE_MS ? { kind: "online", ts: s } : { kind: "seen", ts: s };
}

const two = (n) => String(n).padStart(2, "0");
/** "HH:mm" for today, "dd.MM HH:mm" for another day (the phone's formatSeen), in this computer's time. */
export function formatSeen(ts, now = Date.now()) {
  const d = new Date(ts);
  const n = new Date(now);
  const hm = two(d.getHours()) + ":" + two(d.getMinutes());
  const same = d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
  return same ? hm : two(d.getDate()) + "." + two(d.getMonth() + 1) + " " + hm;
}

/** The names of the groups [peer] and I are both in (not "saved messages"), at most 10, in the order of the list. */
export function commonGroups(chats, peer) {
  const out = [];
  for (const c of Array.isArray(chats) ? chats : []) {
    if (!c || c.group !== true || c.saved === true) continue;
    if (!Array.isArray(c.members) || !c.members.includes(peer)) continue;
    out.push({ id: c.id, title: typeof c.title === "string" ? c.title : "" });
    if (out.length >= MAX_GROUPS) break;
  }
  return out;
}

/**
 * @param {object} o
 * @param {{db: object, fsM: object}} o.f
 * @param {ReturnType<typeof import("./e2e.js").makeE2E>} o.e2e
 */
export function makeContact({ f, e2e }) {
  const { db, fsM } = f;

  /** Follows users/<peer>: [onInfo] gets {seen, about, hide} at once and with each change. Returns the function that stops it. */
  function watch(peer, onInfo, onError = () => {}) {
    return fsM.onSnapshot(
      fsM.doc(db, "users", peer),
      (d) => {
        let data = null;
        try {
          data = d && d.exists && d.exists() ? d.data({ serverTimestamps: "estimate" }) : null;
        } catch (e) {
          data = null;
        }
        onInfo(infoOf(data));
      },
      (e) => onError(e),
    );
  }

  /**
   * Where the verification of [peer] stands: {ready, state, number, pictures, changed}. [ready] is false while their key is not known (the
   * phone: "The contact's key has not been received yet"). [changed] is the phone's "key changed, not acknowledged yet".
   */
  async function view(peer) {
    const d = await e2e.pairDigest(peer);
    if (!d) return { ready: false, state: "none", number: "", pictures: [], changed: false };
    const state = verifyState(await e2e.verifiedWith(peer), hex(d));
    return { ready: true, state, number: numberText(d), pictures: emojis(d), changed: await e2e.keyChanged(peer) };
  }

  const confirm = (peer) => e2e.markVerified(peer);
  const clear = (peer) => e2e.clearVerified(peer);
  const acknowledge = (peer) => e2e.acknowledge(peer);

  return { watch, view, confirm, clear, acknowledge };
}
