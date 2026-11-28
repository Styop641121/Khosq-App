// KHOSQ Web, stage 10 (6m): group voice and video calls (up to MAX_PARTS people at once, everybody connected to everybody: a "mesh").
//
// What is written, and where (the phone writes and reads the very same things, GroupCallManager.kt):
//   gcalls/<id>        the call: { chat, host, hostName, title, kind: "audio" | "video", state: "active" | "ended", ts, con?, dur?,
//                        parts: { <uid>: { sid, j, at, name, m, cam } } }
//                      parts is who is IN the call now. sid: my session in it (a new one at every join), j: when I joined, at: a sign of life
//                      every BEAT_MS, m: my microphone is off, cam: my camera is on. Everybody writes only their own entry.
//   gcalls/<id>/sig/*  the talk between two devices: { from, to, fsid, tsid, lid, k: "offer" | "answer" | "cand", sdp?, c?: [{m, i, s}], at }
//                      one document per message; a device reads only those with to == me. lid names one try of one pair.
//   chats/<group>.gcall  { id, host, hostName, kind, ts } while a call goes on: the others see it with the chat list they already watch.
//                      That is what makes the other phones and pages ring and the chat show "a call is going on - join".
//   chats/<group>/messages/*  one "call" line when the call is over (like the one-to-one call): "call_done" with the length, or "call_missed".
// The sound and the picture go straight between the devices (WebRTC, encrypted by the browser itself), the same servers as for a one-to-one call.
//
// Who offers: the one who came LATER offers (larger j, then larger uid); the one who was there waits for the offer and answers. Both read the same
// numbers from the call document, so they always agree, even when two people come at the same moment.
// Who is there: an entry counts while its "at" keeps changing (seen on MY clock, so a wrong clock somewhere does not matter); a person who closed
// the page without leaving stops counting after LIVE_MS. More than MAX_PARTS people: the later ones (by j, uid) leave by themselves ("full").
//
// The logic is kept apart from the screen: [rtc] and the timers are handed in, so the tests run whole calls between several instances of this file
// without a browser; the real browser test does the same with real WebRTC.
import { cut } from "./send.js";
import { FALLBACK_ICE, mediaWhy } from "./calls.js";

export const MAX_PARTS = 6; // at most this many people in one call (the phone has the same number)
export const RING_MS = 45000; // the one who started gives up after this if nobody came
export const RING_SHOW_MS = 90000; // a call that began longer ago than this is not rung for (the chat still offers to join)
export const BEAT_MS = 25000; // a sign of life, written into my entry
export const LIVE_MS = 75000; // an entry whose sign of life has not changed for this long is not counted
export const FIRST_SIGHT_MS = 240000; // an entry seen for the first time counts only if its sign is this recent (by my clock)
export const ALONE_MS = 20000; // the last one in a call that had people leaves after this
export const CONNECT_MS = 30000; // a pair that does not connect within this is tried again
export const GRACE_MS = 8000; // a connection that drops may come back within this
export const RETRY_MS = 3000;
export const RETRIES = 2; // so a pair gets three tries in all
export const BATCH_MS = 250; // addresses found within this time go in one document
export const DEAD_MS = 180000; // a call nobody is in, older than this, is closed by whoever looks
export const NOTE_MS = 1800;
export const NOTE_LONG_MS = 5000;
export const FIRST_DOC_MS = 8000; // joining waits this long for the call document
const LONG_NOTES = new Set(["denied", "noMedia", "unsupported", "full"]);
export const PHASE = Object.freeze({ IDLE: "idle", RINGING: "ringing", JOINING: "joining", ACTIVE: "active", ENDED: "ended" });
const GVIDEO = { width: { ideal: 480 }, height: { ideal: 360 }, frameRate: { ideal: 20 } }; // smaller than a one-to-one picture: up to five are sent
const str = (v) => (typeof v === "string" ? v : "");
const num = (v) => (typeof v === "number" && Number.isFinite(v) ? v : 0);
const rid = () => Math.random().toString(36).slice(2, 10) + Math.random().toString(36).slice(2, 6);

/** What the sender of a picture is told when n people are in the call (a mesh sends the picture to everybody: n-1 times). */
export function qualityFor(n) {
  if (n <= 2) return { maxBitrate: 0, scale: 1 };
  if (n === 3) return { maxBitrate: 500000, scale: 1 };
  if (n === 4) return { maxBitrate: 300000, scale: 1.5 };
  return { maxBitrate: 200000, scale: 2 };
}

/** The pointer in a chat document: { id, host, hostName, video, ts } or null. */
export function pointerOf(c) {
  const g = c && typeof c === "object" ? c.gcall : null;
  if (!g || typeof g !== "object") return null;
  const id = str(g.id);
  if (!id || id.length > 64 || id.includes("/")) return null;
  return { id, host: str(g.host), hostName: str(g.hostName), video: g.kind === "video", ts: num(g.ts) };
}

/**
 * Who is in a call, by the entries of its document. See the top of the file: the sign of life is judged by whether it changes, on my own clock.
 * Returns the people oldest first (j, then uid): { uid, sid, j, at, name, m, cam }.
 */
export function makeLiveness(now) {
  const seen = new Map(); // "uid|sid" -> { at, t }
  return {
    live(parts) {
      const t = now();
      const out = [];
      const keep = new Set();
      if (parts && typeof parts === "object") {
        for (const uid of Object.keys(parts)) {
          const p = parts[uid];
          if (!p || typeof p !== "object" || typeof p.sid !== "string" || !p.sid) continue;
          const at = num(p.at);
          const key = uid + "|" + p.sid;
          keep.add(key);
          const s = seen.get(key);
          if (!s) seen.set(key, { at, t: Math.abs(t - at) > FIRST_SIGHT_MS ? t - LIVE_MS - 1 : t });
          else if (s.at !== at) seen.set(key, { at, t });
          if (t - seen.get(key).t >= LIVE_MS) continue;
          out.push({ uid, sid: p.sid, j: num(p.j), at, name: cut(str(p.name), 60), m: p.m === true, cam: p.cam === true });
        }
      }
      for (const k of [...seen.keys()]) if (!keep.has(k)) seen.delete(k);
      out.sort((a, b) => a.j - b.j || (a.uid < b.uid ? -1 : a.uid > b.uid ? 1 : 0));
      return out;
    },
  };
}

/**
 * @param {object} o
 * @param {{db: object, fsM: object, auth?: object}} o.f  the signed-in Firebase
 * @param {string} o.uid  my account id
 * @param {string|(() => string)} o.name  my name
 * @param {string} [o.worker]  the Worker's address ("" = no push)
 * @param {{Peer: Function, getUserMedia: Function, Stream: Function}} o.rtc
 * @param {{servers: () => object[]}} [o.ice]  the relay servers (calls.js asks the Worker for them)
 * @param {{setTimeout: Function, clearTimeout: Function}} [o.timers]
 * @param {() => number} [o.now]
 * @param {(uid: string, claimed: string, chatId: string) => string} [o.resolveName]  the name to show for a person of a group
 * @param {() => boolean} [o.isBusy]  true while something else (a one-to-one call) is going on
 * @param {(state: object) => void} [o.onChange]  gets a copy of the state at every change
 */
export function makeGroupCalls({ f, uid, name, worker = "", rtc, ice = null, timers = { setTimeout: (...a) => setTimeout(...a), clearTimeout: (...a) => clearTimeout(...a) }, now = () => Date.now(), resolveName = (u, c) => c, isBusy = () => false, onChange = () => {} }) {
  const { db, fsM } = f;
  const T = timers;
  const myName = () => String((typeof name === "function" ? name() : name) || "");
  const blank = () => ({ phase: PHASE.IDLE, callId: "", chatId: "", title: "", video: false, hostUid: "", hostName: "", outgoing: false, muted: false, cameraOn: false, noCamera: false, local: null, people: [], count: 0, ringIn: [], note: "", startedAt: 0 });
  let st = blank();

  let gen = 0; // the number of the call in progress: what an older call is still waiting for is dropped
  let stopped = false;
  let sid = "";
  let myJ = 0;
  let joined = false; // my entry has been written
  let local = null;
  let doc = null; // the last data of the call document
  let live = [];
  let lv = makeLiveness(now);
  let lastQ = -1;
  const links = new Map(); // peer uid -> link
  const inbox = new Map(); // "uid|lid" -> addresses that came before their offer
  const pendingOffers = new Map(); // peer uid -> an offer from somebody the call document does not show yet
  let early = []; // messages that came while my own entry was still on its way to the server
  let docUnsub = null;
  let sigUnsub = null;
  let beatT = null;
  let ringT = null;
  let aloneT = null;
  let resetT = null;
  let docRetryT = null;
  let ringUnsub = null;
  let ringDropT = null;
  let ringLv = makeLiveness(now);
  const handled = new Set(); // calls I have answered, refused or let go: not rung for again
  let chatsNow = [];
  let bw = null; // the banner of the open chat

  const set = (patch) => {
    st = { ...st, ...patch };
    try {
      onChange({ ...st });
    } catch (e) {
      // a broken screen must not stop the call
    }
  };
  const clear = (t) => (t ? T.clearTimeout(t) : null);
  const gRef = (id) => fsM.doc(db, "gcalls", id);
  const chatRef = (id) => fsM.doc(db, "chats", id);
  const chatOf = (id) => chatsNow.find((c) => c && c.id === id) || null;
  const nameOf = (u, claimed, chatId) => cut(str(resolveName(u, str(claimed), chatId)) || str(claimed) || "?", 60);
  const iceList = () => {
    try {
      const l = ice && typeof ice.servers === "function" ? ice.servers() : null;
      if (Array.isArray(l) && l.length) return l;
    } catch (e) {
      // the free servers are used
    }
    return [...FALLBACK_ICE];
  };

  /** Asks the Worker to wake the other phones. Best effort; no text goes along but the sign of the kind. */
  async function notify(chatId, video) {
    if (!worker) return;
    try {
      const user = f.auth && f.auth.currentUser;
      if (!user || typeof user.getIdToken !== "function") return;
      const token = await user.getIdToken();
      await fetch(worker, {
        method: "POST",
        headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
        body: JSON.stringify({ chatId, type: "call", title: cut(myName(), 60), body: video ? "\u{1F4F9}" : "\u{1F4DE}" }),
      });
    } catch (e) {
      // the phones still ring when they look at the chats by themselves
    }
  }

  const stopStream = (s) => {
    if (!s) return;
    try {
      for (const t of s.getTracks()) t.stop();
    } catch (e) {
      // already stopped
    }
  };
  async function media(video) {
    return rtc.getUserMedia({ audio: true, video: video ? GVIDEO : false });
  }

  // ------------------------------------------------------------------------------------------------ the pairs
  /** True when I (not the other) must make the offer: the later one offers. */
  const iOffer = (me, p) => me.j > p.j || (me.j === p.j && uid > p.uid);

  function publish() {
    const people = [];
    for (const p of live) {
      if (p.uid === uid) continue;
      const l = links.get(p.uid);
      people.push({ uid: p.uid, name: nameOf(p.uid, p.name, st.chatId), state: l ? l.state : "connecting", stream: l ? l.stream : null, muted: p.m, cam: p.cam });
    }
    const n = live.some((p) => p.uid === uid) ? live.length : live.length + 1;
    set({ people, count: n });
  }

  function makeLink(peerUid, peerSid, lid, offerer, tries) {
    const conn = new rtc.Peer({ iceServers: iceList() });
    const link = { peerUid, peerSid, lid, offerer, tries: tries || 0, conn, state: "connecting", stream: null, remoteSet: false, answered: false, remoteCand: [], out: [], outT: null, connT: null, graceT: null };
    links.set(peerUid, link);
    if (local) for (const track of local.getTracks()) conn.addTrack(track, local);
    conn.onicecandidate = (e) => {
      if (links.get(peerUid) !== link) return;
      if (!e || !e.candidate || !e.candidate.candidate) return flushOut(link);
      link.out.push({ m: str(e.candidate.sdpMid), i: Number(e.candidate.sdpMLineIndex) || 0, s: e.candidate.candidate });
      if (link.out.length >= 12) flushOut(link);
      else if (!link.outT) link.outT = T.setTimeout(() => flushOut(link), BATCH_MS);
    };
    conn.ontrack = (e) => {
      if (links.get(peerUid) !== link || !e || !e.track) return;
      let s = e.streams && e.streams[0];
      if (!s) {
        link.stream = link.stream || new rtc.Stream();
        link.stream.addTrack(e.track);
        s = link.stream;
      }
      link.stream = s;
      publish();
    };
    conn.oniceconnectionstatechange = () => {
      if (links.get(peerUid) !== link) return;
      const s = conn.iceConnectionState;
      if (s === "connected" || s === "completed") {
        clear(link.graceT);
        clear(link.connT);
        link.graceT = link.connT = null;
        link.state = "connected";
        if (!st.startedAt) set({ startedAt: now() });
        publish();
        quality(true);
      } else if (s === "disconnected") {
        clear(link.graceT);
        link.graceT = T.setTimeout(() => failLink(link), GRACE_MS);
      } else if (s === "failed") {
        failLink(link);
      }
    };
    link.connT = T.setTimeout(() => {
      if (link.state === "connecting") failLink(link);
    }, CONNECT_MS);
    return link;
  }

  function flushOut(link) {
    clear(link.outT);
    link.outT = null;
    if (!link.out.length) return;
    const c = link.out;
    link.out = [];
    sendSig({ to: link.peerUid, tsid: link.peerSid, lid: link.lid, k: "cand", c });
  }

  function dropLink(link) {
    clear(link.outT);
    clear(link.connT);
    clear(link.graceT);
    link.outT = link.connT = link.graceT = null;
    if (links.get(link.peerUid) === link) links.delete(link.peerUid);
    try {
      link.conn.close();
    } catch (e) {
      // closed already
    }
  }

  /** A pair that did not work: it is let go and shown as lost; the one who offers tries again a little later (RETRIES times). */
  function failLink(link) {
    if (links.get(link.peerUid) !== link || st.phase !== PHASE.ACTIVE) return;
    const g = gen;
    clear(link.outT);
    clear(link.connT);
    clear(link.graceT);
    link.outT = link.connT = link.graceT = null;
    try {
      link.conn.close();
    } catch (e) {
      // closed already
    }
    link.state = "lost";
    link.stream = null;
    publish();
    if (link.offerer && link.tries < RETRIES) {
      T.setTimeout(() => {
        if (g !== gen || st.phase !== PHASE.ACTIVE || links.get(link.peerUid) !== link) return;
        const p = live.find((x) => x.uid === link.peerUid && x.sid === link.peerSid);
        if (!p) return;
        links.delete(link.peerUid);
        startOffer(p, link.tries + 1);
        publish();
      }, RETRY_MS);
    }
  }

  function startOffer(p, tries) {
    const link = makeLink(p.uid, p.sid, rid(), true, tries || 0);
    (async () => {
      try {
        const offer = await link.conn.createOffer();
        await link.conn.setLocalDescription(offer);
        if (links.get(p.uid) !== link) return;
        sendSig({ to: p.uid, tsid: p.sid, lid: link.lid, k: "offer", sdp: offer.sdp });
      } catch (e) {
        failLink(link);
      }
    })();
    return link;
  }

  function sendSig(o) {
    const id = st.callId;
    if (!id) return;
    const body = { from: uid, to: o.to, fsid: sid, tsid: o.tsid, lid: o.lid, k: o.k, at: now() };
    if (o.sdp) body.sdp = o.sdp;
    if (o.c) body.c = o.c;
    try {
      Promise.resolve(fsM.addDoc(fsM.collection(db, "gcalls", id, "sig"), body)).catch(() => {});
    } catch (e) {
      // one message less: the pair is tried again
    }
  }

  function addRemote(link, c) {
    const init = { candidate: c.s, sdpMLineIndex: c.i };
    if (c.m) init.sdpMid = c.m;
    Promise.resolve(link.conn.addIceCandidate(init)).catch(() => {});
  }
  function flushRemote(link) {
    const list = link.remoteCand;
    link.remoteCand = [];
    for (const c of list) addRemote(link, c);
    const key = link.peerUid + "|" + link.lid;
    const early = inbox.get(key);
    if (early) {
      inbox.delete(key);
      for (const c of early) addRemote(link, c);
    }
  }

  const candList = (x) => (Array.isArray(x.c) ? x.c.filter((c) => c && typeof c === "object" && str(c.s)).slice(0, 40).map((c) => ({ m: str(c.m), i: Number(c.i) || 0, s: str(c.s) })) : []);

  function onSig(x, g) {
    if (g !== gen || !x || typeof x !== "object") return;
    if (st.phase === PHASE.JOINING) {
      if (early.length < 200) early.push(x);
      return;
    }
    if (st.phase !== PHASE.ACTIVE) return;
    const from = str(x.from);
    if (!from || from === uid || str(x.to) !== uid || str(x.tsid) !== sid) return; // not for this session of mine
    const lid = str(x.lid);
    if (!lid) return;
    if (x.k === "offer") {
      const sdp = str(x.sdp);
      if (!sdp || sdp.length > 200000 || !str(x.fsid)) return;
      const p = live.find((q) => q.uid === from);
      if (!p || p.sid !== x.fsid) {
        pendingOffers.set(from, { x, at: now() }); // the call document may not show them yet: asked again when it changes
        return;
      }
      void answer(from, x);
    } else if (x.k === "answer") {
      const link = links.get(from);
      if (!link || link.lid !== lid || !link.offerer || link.answered) return;
      const sdp = str(x.sdp);
      if (!sdp || sdp.length > 200000) return;
      link.answered = true;
      Promise.resolve(link.conn.setRemoteDescription({ type: "answer", sdp })).then(
        () => {
          if (links.get(from) !== link) return;
          link.remoteSet = true;
          flushRemote(link);
        },
        () => failLink(link),
      );
    } else if (x.k === "cand") {
      const list = candList(x);
      if (!list.length) return;
      const link = links.get(from);
      if (link && link.lid === lid) {
        if (link.remoteSet) for (const c of list) addRemote(link, c);
        else link.remoteCand.push(...list);
      } else {
        const key = from + "|" + lid;
        if (!inbox.has(key) && inbox.size >= 40) inbox.delete(inbox.keys().next().value);
        inbox.set(key, [...(inbox.get(key) || []), ...list].slice(0, 80));
      }
    }
  }

  async function answer(from, x) {
    const mine = links.get(from);
    const me = live.find((q) => q.uid === uid);
    const p = live.find((q) => q.uid === from);
    // the later of the two makes the offer (both read the same numbers): an offer from the one who should be answering is not taken
    if (me && p && iOffer(me, p)) return;
    if (mine && mine.lid === x.lid) return; // the same offer again
    if (mine) dropLink(mine);
    const g = gen;
    const link = makeLink(from, str(x.fsid), str(x.lid), false);
    try {
      await link.conn.setRemoteDescription({ type: "offer", sdp: str(x.sdp) });
      if (g !== gen || links.get(from) !== link) return;
      link.remoteSet = true;
      flushRemote(link);
      const ans = await link.conn.createAnswer();
      await link.conn.setLocalDescription(ans);
      if (g !== gen || links.get(from) !== link) return;
      sendSig({ to: from, tsid: link.peerSid, lid: link.lid, k: "answer", sdp: ans.sdp });
      publish();
    } catch (e) {
      if (g === gen) failLink(link);
    }
  }

  /** The video this device sends is made lighter when more people are in the call. */
  function quality(force) {
    const n = live.length;
    if (!force && n === lastQ) return;
    lastQ = n;
    const q = qualityFor(n);
    for (const l of links.values()) {
      try {
        if (typeof l.conn.getSenders !== "function") continue;
        for (const s of l.conn.getSenders()) {
          if (!s || !s.track || s.track.kind !== "video" || typeof s.getParameters !== "function") continue;
          const p = s.getParameters();
          p.encodings = Array.isArray(p.encodings) && p.encodings.length ? p.encodings : [{}];
          if (q.maxBitrate) p.encodings[0].maxBitrate = q.maxBitrate;
          else delete p.encodings[0].maxBitrate;
          p.encodings[0].scaleResolutionDownBy = q.scale;
          Promise.resolve(s.setParameters(p)).catch(() => {});
        }
      } catch (e) {
        // the picture stays as it is
      }
    }
  }

  // ------------------------------------------------------------------------------------------------ the call document
  /** Looks at the document again: who is there, which pairs must exist, who has to leave, whether I am alone. */
  function sync() {
    if (st.phase !== PHASE.ACTIVE || !doc) return;
    const all = lv.live(doc.parts);
    live = all.slice(0, MAX_PARTS); // when more came at once, the later ones are not in the call (they leave by themselves, below)
    const mine = doc.parts && doc.parts[uid];
    if (!mine || mine.sid !== sid) {
      // my entry is gone or is another session's (the same account joined on another device): this one is out, and must not touch the entry
      if (joined) finish("elsewhere", { quiet: true });
      return;
    }
    const me = all.find((p) => p.uid === uid);
    if (!me) return;
    if (all.indexOf(me) >= MAX_PARTS) return finish("full");
    const want = new Set();
    for (const p of live) {
      if (p.uid === uid) continue;
      want.add(p.uid);
      let link = links.get(p.uid);
      if (link && link.peerSid !== p.sid) {
        dropLink(link);
        link = null;
      }
      const wait = pendingOffers.get(p.uid);
      if (wait) {
        pendingOffers.delete(p.uid);
        if (wait.x.fsid === p.sid && now() - wait.at < 30000) void answer(p.uid, wait.x);
        link = links.get(p.uid);
      }
      if (!link && iOffer(me, p)) startOffer(p, 0);
    }
    for (const link of [...links.values()]) if (!want.has(link.peerUid)) dropLink(link);
    for (const k of [...pendingOffers.keys()]) if (now() - pendingOffers.get(k).at > 30000) pendingOffers.delete(k); // an offer waits for the person's entry to show, but not for ever
    publish();
    quality(false);
    // the first of the people there marks the moment two of them were together (the length of the call)
    if (live.length >= 2 && !doc.con && live[0].uid === uid) {
      doc = { ...doc, con: now() };
      Promise.resolve(fsM.updateDoc(gRef(st.callId), { con: doc.con })).catch(() => {});
    }
    if (live.length === 1 && doc.con) {
      if (!aloneT) {
        const g = gen;
        aloneT = T.setTimeout(() => {
          aloneT = null;
          if (g === gen && st.phase === PHASE.ACTIVE && live.length === 1) finish(null);
        }, ALONE_MS);
      }
    } else if (aloneT) {
      clear(aloneT);
      aloneT = null;
    }
  }

  function onCallDoc(g, d) {
    if (g !== gen) return;
    if (!d || !d.exists()) {
      doc = null;
      if (st.phase === PHASE.ACTIVE && joined) finish(null, { quiet: true });
      return;
    }
    doc = d.data();
    if (str(doc.state) === "ended") {
      if (st.phase === PHASE.ACTIVE) finish(null, { quiet: true }); // (a join that finds it over says so itself)
      return;
    }
    sync();
  }

  function watchDoc(id, g) {
    return new Promise((resolve) => {
      let first = resolve;
      const run = () => {
        if (docUnsub) docUnsub();
        docUnsub = fsM.onSnapshot(
          gRef(id),
          (d) => {
            if (g !== gen) return;
            onCallDoc(g, d);
            if (first) {
              first(d);
              first = null;
            }
          },
          () => {
            if (first) {
              first(null);
              first = null;
            }
            // a listener that failed does not come back by itself: look again soon
            if (g === gen && st.phase !== PHASE.IDLE && st.phase !== PHASE.ENDED) {
              clear(docRetryT);
              docRetryT = T.setTimeout(() => {
                docRetryT = null;
                if (g === gen) run();
              }, 5000);
            }
          },
        );
      };
      run();
    });
  }

  function watchSig(id, g) {
    if (sigUnsub) sigUnsub();
    sigUnsub = fsM.onSnapshot(
      fsM.query(fsM.collection(db, "gcalls", id, "sig"), fsM.where("to", "==", uid)),
      (snap) => {
        if (g !== gen) return;
        for (const ch of snap.docChanges()) if (ch.type === "added") onSig(ch.doc.data(), g);
      },
      () => {},
    );
  }

  // ------------------------------------------------------------------------------------------------ being in a call
  function startBeat(g) {
    clear(beatT);
    beatT = T.setTimeout(() => {
      if (g !== gen || st.phase !== PHASE.ACTIVE) return;
      Promise.resolve(fsM.updateDoc(gRef(st.callId), { ["parts." + uid + ".at"]: now() })).catch(() => {});
      sync(); // people whose sign of life stopped are dropped even when nothing else is written
      startBeat(g);
    }, BEAT_MS);
  }

  /** Common start of making a call and joining one. */
  async function enter({ create, id, chatId, title, video, hostUid, hostName }) {
    const g = ++gen;
    sid = rid();
    myJ = now();
    joined = false;
    doc = null;
    live = [];
    lv = makeLiveness(now);
    lastQ = -1;
    links.clear();
    inbox.clear();
    pendingOffers.clear();
    early = [];
    if (ice && typeof ice.refresh === "function") Promise.resolve(ice.refresh()).catch(() => {});
    set({ ...blank(), phase: PHASE.JOINING, callId: id, chatId, title: cut(title, 60), video, hostUid, hostName, outgoing: create, cameraOn: video });
    if (!create) {
      // look at the call first: nothing is asked of the person (the microphone) for a call that is over or full
      let tid = null;
      const timeout = new Promise((r) => {
        tid = T.setTimeout(() => r(null), FIRST_DOC_MS);
      });
      const first = await Promise.race([watchDoc(id, g), timeout]);
      clear(tid);
      if (g !== gen) return { ok: false, why: "gone" };
      if (!first || !first.exists() || str(first.data().state) !== "active") {
        finish("ended", { quiet: true });
        return { ok: false, why: "ended" };
      }
      const people = makeLiveness(now).live(first.data().parts);
      if (people.length === 0) {
        finish("ended", { quiet: true });
        return { ok: false, why: "ended" };
      }
      if (people.length >= MAX_PARTS && !people.some((p) => p.uid === uid)) {
        finish("full", { quiet: true });
        return { ok: false, why: "full" };
      }
    }
    let stream = null;
    let noCamera = false;
    try {
      stream = await media(video);
    } catch (e) {
      if (video && mediaWhy(e) !== "denied") {
        try {
          stream = await media(false); // a video call can be joined with the microphone alone (no camera, or one that is busy)
          noCamera = true;
        } catch (e2) {
          if (g === gen) finish(mediaWhy(e2), { quiet: true });
          return { ok: false, why: mediaWhy(e2) };
        }
      } else {
        if (g === gen) finish(mediaWhy(e), { quiet: true });
        return { ok: false, why: mediaWhy(e) };
      }
    }
    if (g !== gen) {
      stopStream(stream);
      return { ok: false, why: "gone" };
    }
    local = stream;
    const camOn = video && !noCamera && stream.getVideoTracks().length > 0;
    set({ local: stream, noCamera, cameraOn: camOn });
    watchSig(id, g);
    const part = { sid, j: myJ, at: now(), name: myName(), m: false, cam: camOn };
    let ts = 0;
    try {
      if (create) {
        ts = now();
        await fsM.setDoc(gRef(id), { chat: chatId, host: uid, hostName: myName(), title: cut(title, 60), kind: video ? "video" : "audio", state: "active", ts, parts: { [uid]: part } });
        if (g === gen) await fsM.updateDoc(chatRef(chatId), { gcall: { id, host: uid, hostName: myName(), kind: video ? "video" : "audio", ts } });
      } else {
        await fsM.updateDoc(gRef(id), { ["parts." + uid]: part });
      }
    } catch (e) {
      if (g === gen) finish("error", { quiet: true });
      if (create) abandon(true, id, chatId);
      return { ok: false, why: "error" };
    }
    if (g !== gen) {
      // I left while the writes were on their way
      abandon(create, id, chatId);
      return { ok: false, why: "gone" };
    }
    joined = true;
    handled.add(id);
    stopRing();
    set({ phase: PHASE.ACTIVE });
    if (create) {
      void watchDoc(id, g); // the document is looked at from now on
      notify(chatId, video);
      clear(ringT);
      ringT = T.setTimeout(() => {
        if (g === gen && st.phase === PHASE.ACTIVE && live.length <= 1 && !(doc && doc.con)) finish("noanswer");
      }, RING_MS);
    }
    const replay = early;
    early = [];
    for (const x of replay) onSig(x, g);
    if (!create) sync();
    startBeat(g);
    return { ok: true, id };
  }

  /** What is undone when the person left a call that was still being made. */
  function abandon(create, id, chatId) {
    if (create) {
      Promise.resolve(fsM.updateDoc(gRef(id), { state: "ended", ["parts." + uid]: fsM.deleteField() })).catch(() => {});
      Promise.resolve(fsM.updateDoc(chatRef(chatId), { gcall: fsM.deleteField() })).catch(() => {});
    } else {
      Promise.resolve(fsM.updateDoc(gRef(id), { ["parts." + uid]: fsM.deleteField() })).catch(() => {});
    }
  }

  /** Starts a group call in a group chat. Resolves {ok: true, id} once the call is written, or {ok: false, why}. */
  async function start({ chatId, title, video }) {
    if (st.phase !== PHASE.IDLE && st.phase !== PHASE.ENDED) return { ok: false, why: "busy" };
    if (isBusy()) return { ok: false, why: "busy" };
    if (typeof chatId !== "string" || !chatId || chatId.includes("/") || chatId.includes("_")) return { ok: false, why: "no" };
    if (!rtc || !rtc.Peer || !rtc.getUserMedia) return { ok: false, why: "unsupported" };
    if (st.phase === PHASE.ENDED) resetNow();
    const id = fsM.doc(fsM.collection(db, "gcalls")).id;
    return enter({ create: true, id, chatId, title: str(title), video: video === true, hostUid: uid, hostName: myName() });
  }

  /** Joins the call that rings (the one on the screen), or the call of a chat that the chat list shows. */
  async function join(chatId) {
    if (st.phase === PHASE.RINGING && (!chatId || chatId === st.chatId)) {
      const o = { create: false, id: st.callId, chatId: st.chatId, title: st.title, video: st.video, hostUid: st.hostUid, hostName: st.hostName };
      stopRing();
      return enter(o);
    }
    if (st.phase !== PHASE.IDLE && st.phase !== PHASE.ENDED) return { ok: false, why: "busy" };
    if (isBusy()) return { ok: false, why: "busy" };
    if (!rtc || !rtc.Peer || !rtc.getUserMedia) return { ok: false, why: "unsupported" };
    const c = chatOf(chatId);
    const p = c ? pointerOf(c) : null;
    if (!c || !p) return { ok: false, why: "ended" };
    if (st.phase === PHASE.ENDED) resetNow();
    return enter({ create: false, id: p.id, chatId, title: str(c.title), video: p.video, hostUid: p.host, hostName: nameOf(p.host, p.hostName, chatId) });
  }

  function leave() {
    if (st.phase === PHASE.RINGING) return decline();
    if (st.phase === PHASE.IDLE || st.phase === PHASE.ENDED) return;
    finish(null);
  }

  function toggleMute() {
    if (!local || st.phase !== PHASE.ACTIVE) return;
    const muted = !st.muted;
    for (const t of local.getAudioTracks()) t.enabled = !muted;
    set({ muted });
    Promise.resolve(fsM.updateDoc(gRef(st.callId), { ["parts." + uid + ".m"]: muted })).catch(() => {});
  }
  function toggleCamera() {
    if (!local || !st.video || st.noCamera || st.phase !== PHASE.ACTIVE) return;
    const on = !st.cameraOn;
    for (const t of local.getVideoTracks()) t.enabled = on;
    set({ cameraOn: on });
    Promise.resolve(fsM.updateDoc(gRef(st.callId), { ["parts." + uid + ".cam"]: on })).catch(() => {});
  }

  // ------------------------------------------------------------------------------------------------ the end
  /** The "call" line in the group's chat, written by the one who closes the call. */
  function logCall(cur, d, note) {
    const hosted = cur.hostUid === uid;
    let code = "";
    let sec = 0;
    if (d && num(d.con) > 0) {
      code = "call_done";
      sec = Math.max(0, Math.floor((now() - num(d.con)) / 1000));
    } else if (hosted && note === "noanswer") code = "call_missed";
    else if (hosted && note === null) code = "call_cancelled";
    if (!code) return;
    try {
      Promise.resolve(fsM.addDoc(fsM.collection(db, "chats", cur.chatId, "messages"), { from: uid, text: "", kind: "call", code, vid: cur.video === true, dur: sec, ts: fsM.serverTimestamp() })).catch(() => {});
    } catch (e) {
      // the line is only history
    }
    return sec;
  }

  function clearPointer(chatId, id) {
    const c = chatOf(chatId);
    const p = c ? pointerOf(c) : null;
    if (!p || p.id !== id) return;
    Promise.resolve(fsM.updateDoc(chatRef(chatId), { gcall: fsM.deleteField() })).catch(() => {});
  }

  function resetNow() {
    clear(resetT);
    resetT = null;
    st = blank();
    try {
      onChange({ ...st });
    } catch (e) {
      // a broken screen
    }
  }

  /** Leaves the call in progress: everything is let go (the camera and microphone light goes off), and the reason stays on the screen a moment. */
  function finish(note, o = {}) {
    const cur = st;
    if (cur.phase === PHASE.IDLE || cur.phase === PHASE.ENDED) return;
    const d = doc;
    const wasLive = live;
    const wasIn = joined;
    const mineSid = sid;
    joined = false;
    for (const t of [beatT, ringT, aloneT, docRetryT]) clear(t);
    beatT = ringT = aloneT = docRetryT = null;
    if (docUnsub) docUnsub();
    if (sigUnsub) sigUnsub();
    docUnsub = sigUnsub = null;
    for (const l of [...links.values()]) dropLink(l);
    links.clear();
    inbox.clear();
    pendingOffers.clear();
    const stream = local;
    local = null;
    doc = null;
    live = [];
    gen++;
    stopStream(stream);
    stopRing();
    if (cur.callId) handled.add(cur.callId);
    set({ phase: PHASE.ENDED, note: note || "", local: null, people: [], count: 0, muted: false });
    if (wasIn && !o.quiet && cur.callId) {
      // my entry goes; the last one out closes the call, writes the line and takes the sign out of the chat
      const ref = gRef(cur.callId);
      const other = Boolean(d && d.parts && d.parts[uid] && d.parts[uid].sid !== mineSid); // the same account is in the call on another device
      const others = wasLive.filter((p) => p.uid !== uid).length;
      if (!other) {
        if (others === 0) {
          const sec = logCall(cur, d, note || null) || 0;
          Promise.resolve(fsM.updateDoc(ref, { ["parts." + uid]: fsM.deleteField(), state: "ended", dur: sec })).catch(() => {});
          clearPointer(cur.chatId, cur.callId);
        } else {
          Promise.resolve(fsM.updateDoc(ref, { ["parts." + uid]: fsM.deleteField() })).catch(() => {});
        }
      }
    }
    const endedId = cur.callId;
    clear(resetT);
    resetT = T.setTimeout(
      () => {
        resetT = null;
        if (st.phase === PHASE.ENDED && st.callId === endedId) resetNow();
        checkRing();
      },
      note ? (LONG_NOTES.has(note) ? NOTE_LONG_MS : NOTE_MS) : 400,
    );
  }

  // ------------------------------------------------------------------------------------------------ ringing
  function stopRing() {
    if (ringUnsub) ringUnsub();
    ringUnsub = null;
    clear(ringDropT);
    ringDropT = null;
  }

  /** The ring ends without a word: refused, over, or nobody left to answer. */
  function dropRing() {
    if (st.phase !== PHASE.RINGING) return;
    handled.add(st.callId);
    stopRing();
    resetNow();
    checkRing();
  }

  function decline() {
    if (st.phase !== PHASE.RINGING) return;
    dropRing();
  }

  function ring(c, p) {
    if (st.phase === PHASE.ENDED) resetNow();
    ringLv = makeLiveness(now);
    set({ ...blank(), phase: PHASE.RINGING, callId: p.id, chatId: c.id, title: cut(str(c.title), 60), video: p.video, hostUid: p.host, hostName: nameOf(p.host, p.hostName, c.id), cameraOn: p.video });
    const id = p.id;
    stopRing();
    ringUnsub = fsM.onSnapshot(
      gRef(id),
      (d) => {
        if (st.phase !== PHASE.RINGING || st.callId !== id) return;
        if (!d || !d.exists()) return dropRing();
        const x = d.data();
        if (str(x.state) !== "active") return dropRing();
        const people = ringLv.live(x.parts);
        if (!people.length) {
          if (!x.parts || !Object.keys(x.parts).length) closeDead(c.id, id); // everybody left at the same moment: nobody closed it
          return dropRing();
        }
        set({ ringIn: people.map((q) => ({ uid: q.uid, name: nameOf(q.uid, q.name, c.id) })) });
      },
      () => {},
    );
    ringDropT = T.setTimeout(() => dropRing(), Math.max(1000, RING_SHOW_MS - Math.max(0, now() - p.ts)));
  }

  /** A new list of my chats: a group that shows a call I have not met makes the page ring; the open chat's banner is looked at again. */
  function chatsChanged(list) {
    chatsNow = Array.isArray(list) ? list : [];
    if (st.phase === PHASE.RINGING) {
      const c = chatOf(st.chatId);
      const p = c ? pointerOf(c) : null;
      if (!p || p.id !== st.callId) dropRing();
    }
    checkRing();
    bannerRefresh();
  }

  function checkRing() {
    if (stopped || (st.phase !== PHASE.IDLE && st.phase !== PHASE.ENDED)) return;
    for (const c of chatsNow) {
      if (!c || c.group !== true || c.saved === true) continue;
      const p = pointerOf(c);
      if (!p || !p.host || p.host === uid || handled.has(p.id)) continue;
      if (now() - p.ts > RING_SHOW_MS) continue;
      if (isBusy()) return;
      ring(c, p);
      return;
    }
  }

  // ------------------------------------------------------------------------------------------------ the banner of the open chat
  function emit(w, info) {
    const key = JSON.stringify(info);
    if (w.last === key) return;
    w.last = key;
    try {
      w.cb(info);
    } catch (e) {
      // a broken screen
    }
  }
  function dropBanner(w) {
    if (w.unsub) w.unsub();
    w.unsub = null;
    clear(w.t);
    w.t = null;
    w.id = "";
  }
  function closeDead(chatId, id) {
    Promise.resolve(fsM.updateDoc(gRef(id), { state: "ended" })).catch(() => {});
    clearPointer(chatId, id);
  }
  function bannerEval(w) {
    if (bw !== w) return;
    clear(w.t);
    w.t = T.setTimeout(() => bannerEval(w), 20000);
    const x = w.doc;
    if (!x || str(x.state) !== "active") return emit(w, null);
    const people = w.lv.live(x.parts);
    if (!people.length) {
      emit(w, null);
      if (!x.parts || !Object.keys(x.parts).length || now() - num(w.ptr.ts) > DEAD_MS) closeDead(w.chatId, w.id);
      return;
    }
    const mine = st.callId === w.id && (st.phase === PHASE.ACTIVE || st.phase === PHASE.JOINING);
    emit(w, { callId: w.id, chatId: w.chatId, video: str(x.kind) === "video", hostName: nameOf(str(x.host), str(x.hostName), w.chatId), people: people.map((q) => ({ uid: q.uid, name: nameOf(q.uid, q.name, w.chatId) })), count: Math.min(people.length, MAX_PARTS), full: people.length >= MAX_PARTS, mine });
  }
  function bannerRefresh() {
    const w = bw;
    if (!w) return;
    const c = chatOf(w.chatId);
    const p = c ? pointerOf(c) : null;
    if (!p) {
      dropBanner(w);
      w.doc = null;
      return emit(w, null);
    }
    if (w.id === p.id) return;
    dropBanner(w);
    w.id = p.id;
    w.ptr = p;
    w.doc = null;
    w.lv = makeLiveness(now);
    w.unsub = fsM.onSnapshot(
      gRef(p.id),
      (d) => {
        if (bw !== w || w.id !== p.id) return;
        w.doc = d && d.exists() ? d.data() : null;
        if (!w.doc && now() - p.ts > DEAD_MS) closeDead(w.chatId, p.id);
        bannerEval(w);
      },
      () => emit(w, null),
    );
  }
  /** Tells [cb] about the call of this chat ({callId, video, hostName, people, count, full, mine} or null) for as long as the chat is open. */
  function watchBanner(chatId, cb) {
    if (bw) {
      dropBanner(bw);
      bw = null;
    }
    if (!chatId || typeof cb !== "function") return () => {};
    const w = (bw = { chatId, cb, id: "", ptr: null, doc: null, unsub: null, t: null, lv: makeLiveness(now), last: "null" });
    bannerRefresh();
    return () => {
      if (bw === w) {
        dropBanner(w);
        bw = null;
      }
    };
  }

  /** The page goes away: the call ends, the listeners stop. */
  function stop() {
    stopped = true;
    if (st.phase === PHASE.ACTIVE || st.phase === PHASE.JOINING) finish(null);
    stopRing();
    for (const t of [beatT, ringT, aloneT, resetT, docRetryT]) clear(t);
    if (docUnsub) docUnsub();
    if (sigUnsub) sigUnsub();
    docUnsub = sigUnsub = null;
    if (bw) {
      dropBanner(bw);
      bw = null;
    }
  }

  return { chatsChanged, watchBanner, start, join, decline, leave, toggleMute, toggleCamera, stop, state: () => ({ ...st }) };
}
