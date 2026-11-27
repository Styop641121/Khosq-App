// KHOSQ Web, stage 8 (6h): one-to-one voice and video calls. What the page writes and reads is what the phone writes and reads (CallManager.kt):
// the call is a document calls/<id> that both people watch,
//   { caller, callerName, callee, calleeName, kind: "audio" | "video", state: "ringing" | "accepted" | "declined" | "ended" | "missed",
//     ts, offer: {type, sdp}, answer: {type, sdp}, dur }
// and two small collections under it, calls/<id>/callerCandidates and calleeCandidates, one document {sdpMid, sdpMLineIndex, sdp} for every
// network address a side finds. The sound and the picture then go straight between the two devices (WebRTC, encrypted by the browser itself); only
// when no direct way exists they go through a relay (TURN) whose keys the Worker holds. The caller also writes a "call" line into the chat when
// the call ends, the way the phone does ("Voice call · 1:05", "Missed call" ...), and asks the Worker to wake the other phone.
//
// The logic is kept apart from the screen: [rtc] (the browser's RTCPeerConnection and getUserMedia) and the timers are handed in, so the tests can
// run whole calls between two instances of this file without a browser; the real browser test does it with real WebRTC.
//
// A call that fails or drops writes no "ended" (as the phone: each side ends by its own timers and its own view of the connection); a hang-up,
// a refusal, an answer that could not be made and a call nobody answers do.
//
// Differences from the phone, on purpose: a page can ring only while it is open (a browser has no way to wake a closed page); a call that was
// answered on another device of mine (the phone, another tab) stops ringing here; a call that is cancelled while its document is still on its way is
// ended at once (the phone leaves it ringing); a video call can be answered with the microphone alone when there is no camera or it is refused.
import { cut } from "./send.js";

export const RING_MS = 45000; // the caller gives up after this (the phone's ringTimeout)
export const CONNECT_MS = 30000; // an answered call that never connects is ended (connectTimeout)
export const GRACE_MS = 8000; // a connection that drops may come back within this (graceTimeout)
export const STALE_MS = 120000; // a ringing document older than this is not rung for
export const INCOMING_MS = 75000; // a ringing document that nobody ends is dropped after this (the caller's own limit is 45 s)
export const ICE_FRESH_MS = 20 * 3600 * 1000; // TURN keys from the Worker are used for this long
export const ICE_REFRESH_MS = 6 * 3600 * 1000; // and asked for again after this
export const NOTE_MS = 1800; // how long the reason of an ended call stays on the screen
export const NOTE_LONG_MS = 5000; // and for a reason the person has to act on (allow the microphone ...)
const LONG_NOTES = new Set(["denied", "noMedia", "unsupported"]);
export const PHASE = Object.freeze({ IDLE: "idle", OUTGOING: "outgoing", INCOMING: "incoming", CONNECTING: "connecting", CONNECTED: "connected", ENDED: "ended" });

/** The free public servers the phone falls back on when the Worker has no TURN keys (the phone's fallbackIce). */
export const FALLBACK_ICE = Object.freeze([
  { urls: "stun:stun.l.google.com:19302" },
  { urls: "stun:stun1.l.google.com:19302" },
  { urls: "turn:openrelay.metered.ca:80", username: "openrelayproject", credential: "openrelayproject" },
  { urls: "turn:openrelay.metered.ca:443", username: "openrelayproject", credential: "openrelayproject" },
  { urls: "turn:openrelay.metered.ca:443?transport=tcp", username: "openrelayproject", credential: "openrelayproject" },
]);

/** The id of the one-to-one chat of two people (smaller first), where the call line goes. */
export const dmId = (a, b) => (a < b ? a + "_" + b : b + "_" + a);

/** The Worker's answer {iceServers: [...]} as a list the browser takes. Port 53 is left out (blocked on many networks, only slows things down). */
export function parseIce(json) {
  const out = [];
  const list = json && Array.isArray(json.iceServers) ? json.iceServers : [];
  for (const o of list) {
    if (!o || typeof o !== "object") continue;
    const raw = typeof o.urls === "string" ? [o.urls] : Array.isArray(o.urls) ? o.urls.filter((u) => typeof u === "string") : [];
    const urls = raw.filter((u) => !u.includes(":53"));
    if (!urls.length) continue;
    const s = { urls };
    if (typeof o.username === "string" && o.username) s.username = o.username;
    if (typeof o.credential === "string" && o.credential) s.credential = o.credential;
    out.push(s);
  }
  return out;
}

/** What getUserMedia failed with, as a word the screen can say. */
export function mediaWhy(e) {
  const n = e && e.name;
  if (n === "NotAllowedError" || n === "SecurityError" || n === "PermissionDeniedError") return "denied";
  if (n === "NotFoundError" || n === "OverconstrainedError" || n === "NotReadableError" || n === "DevicesNotFoundError") return "noMedia";
  return "error";
}

const VIDEO = { width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 24 } }; // what the phone asks the camera for (640x480, 24 fps)
const str = (v) => (typeof v === "string" ? v : "");

/**
 * @param {object} o
 * @param {{db: object, fsM: object, auth?: object}} o.f  the signed-in Firebase
 * @param {string} o.uid  my account id
 * @param {string} o.name  my name (shown to the other side, and the title of the push)
 * @param {string} [o.worker]  the Worker's address ("" = no push, no TURN keys)
 * @param {{Peer: Function, getUserMedia: Function, Stream: Function}} o.rtc  RTCPeerConnection, navigator.mediaDevices.getUserMedia, MediaStream
 * @param {{setTimeout: Function, clearTimeout: Function}} [o.timers]
 * @param {() => number} [o.now]
 * @param {(uid: string, claimed: string) => string} [o.resolveName]  the name to show for a caller: the contact's own name where there is one
 * @param {(state: object) => void} [o.onChange]  gets a copy of the state at every change
 */
export function makeCalls({ f, uid, name, worker = "", rtc, timers = { setTimeout: (...a) => setTimeout(...a), clearTimeout: (...a) => clearTimeout(...a) }, now = () => Date.now(), resolveName = (u, c) => c, onChange = () => {} }) {
  const { db, fsM } = f;
  const T = timers;
  const blank = () => ({ phase: PHASE.IDLE, callId: "", peerUid: "", peerName: "", video: false, outgoing: false, muted: false, cameraOn: false, noCamera: false, local: null, remote: null, note: "", startedAt: 0 });
  let st = blank();

  let gen = 0; // the number of the call in progress: what an older call is still waiting for is dropped
  let stopped = false;
  let pc = null;
  let localStream = null;
  let remoteStream = null;
  let isCaller = false;
  let pendingOffer = "";
  let remoteSet = false;
  let answerApplied = false;
  let docReady = false;
  let remoteCand = [];
  let outCand = [];
  let incomingUnsub = null;
  let callUnsub = null;
  let candUnsub = null;
  let retryT = null;
  let ringT = null;
  let connectT = null;
  let graceT = null;
  let incomingT = null;
  let resetT = null;
  let ice = { list: [], at: 0, busy: false };

  const set = (patch) => {
    st = { ...st, ...patch };
    try {
      onChange({ ...st });
    } catch (e) {
      // a broken screen must not stop the call
    }
  };
  const callRef = (id) => fsM.doc(db, "calls", id);
  const clear = (t) => (t ? T.clearTimeout(t) : null);
  const clearTimers = () => {
    clear(ringT);
    clear(connectT);
    clear(graceT);
    clear(incomingT);
    ringT = connectT = graceT = incomingT = null;
  };

  // ------------------------------------------------------------------------------------------------ the relay's keys (from the Worker)
  const iceFresh = () => (ice.list.length && now() - ice.at < ICE_FRESH_MS ? ice.list : []);
  const iceServers = () => {
    const own = iceFresh();
    return own.length ? [...own, ...FALLBACK_ICE.slice(0, 2)] : [...FALLBACK_ICE];
  };
  /** Asks the Worker for TURN keys in the background (at most every few hours). The call never waits for them: it uses the free servers if they are not there. */
  async function refreshIce() {
    if (!worker || ice.busy || stopped) return;
    if (ice.list.length && now() - ice.at < ICE_REFRESH_MS) return;
    const user = f.auth && f.auth.currentUser;
    if (!user || typeof user.getIdToken !== "function") return;
    ice.busy = true;
    try {
      const token = await user.getIdToken();
      const r = await fetch(worker, { method: "POST", headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" }, body: JSON.stringify({ type: "ice" }) });
      if (r.ok) {
        const list = parseIce(await r.json());
        if (list.length) ice = { ...ice, list, at: now() };
      }
    } catch (e) {
      // the free servers are used instead
    } finally {
      ice.busy = false;
    }
  }

  /** Asks the Worker to wake the other person's phone. Best effort; no text goes along but the sign of the kind. */
  async function notify(peerUid, video) {
    if (!worker) return;
    try {
      const user = f.auth && f.auth.currentUser;
      if (!user || typeof user.getIdToken !== "function") return;
      const token = await user.getIdToken();
      await fetch(worker, {
        method: "POST",
        headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
        body: JSON.stringify({ chatId: dmId(uid, peerUid), type: "call", title: cut(name || "", 60), body: video ? "\u{1F4F9}" : "\u{1F4DE}" }),
      });
    } catch (e) {
      // the phone still rings when it is looking at the calls by itself
    }
  }

  // ------------------------------------------------------------------------------------------------ the browser's WebRTC
  function buildPeer(stream) {
    remoteSet = false;
    answerApplied = false;
    docReady = false;
    remoteCand = [];
    outCand = [];
    remoteStream = null;
    const conn = new rtc.Peer({ iceServers: iceServers() });
    pc = conn;
    for (const track of stream.getTracks()) conn.addTrack(track, stream);
    conn.onicecandidate = (e) => {
      if (pc !== conn || !e || !e.candidate || !e.candidate.candidate) return;
      const c = { sdpMid: str(e.candidate.sdpMid), sdpMLineIndex: Number(e.candidate.sdpMLineIndex) || 0, sdp: e.candidate.candidate };
      if (docReady) pushCandidate(c);
      else outCand.push(c);
    };
    conn.ontrack = (e) => {
      if (pc !== conn || !e || !e.track) return;
      let s = e.streams && e.streams[0];
      if (!s) {
        remoteStream = remoteStream || new rtc.Stream();
        remoteStream.addTrack(e.track);
        s = remoteStream;
      }
      set({ remote: s });
    };
    conn.oniceconnectionstatechange = () => {
      if (pc !== conn) return;
      const s = conn.iceConnectionState;
      if (s === "connected" || s === "completed") {
        clear(graceT);
        clear(connectT);
        clear(ringT);
        graceT = connectT = ringT = null;
        if (st.phase === PHASE.CONNECTING || st.phase === PHASE.OUTGOING || st.phase === PHASE.CONNECTED) {
          set({ phase: PHASE.CONNECTED, startedAt: st.startedAt || now() });
        }
      } else if (s === "disconnected") {
        clear(graceT);
        graceT = T.setTimeout(() => {
          if (st.phase === PHASE.CONNECTED || st.phase === PHASE.CONNECTING) finish("lost");
        }, GRACE_MS);
      } else if (s === "failed") {
        finish("failed");
      }
    };
  }

  function pushCandidate(c) {
    if (!st.callId) return;
    const sub = isCaller ? "callerCandidates" : "calleeCandidates";
    try {
      Promise.resolve(fsM.addDoc(fsM.collection(db, "calls", st.callId, sub), { sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex, sdp: c.sdp })).catch(() => {});
    } catch (e) {
      // one address less
    }
  }

  function addRemote(c) {
    if (!pc) return;
    const init = { candidate: c.sdp, sdpMLineIndex: c.sdpMLineIndex };
    if (c.sdpMid) init.sdpMid = c.sdpMid;
    Promise.resolve(pc.addIceCandidate(init)).catch(() => {});
  }
  function flushRemote() {
    const list = remoteCand;
    remoteCand = [];
    for (const c of list) addRemote(c);
  }

  function watchCandidates(sub) {
    if (candUnsub) candUnsub();
    const id = st.callId;
    candUnsub = fsM.onSnapshot(
      fsM.collection(db, "calls", id, sub),
      (snap) => {
        if (st.callId !== id) return;
        for (const ch of snap.docChanges()) {
          if (ch.type !== "added") continue;
          const x = ch.doc.data();
          const sdp = str(x.sdp);
          if (!sdp) continue;
          const c = { sdpMid: str(x.sdpMid), sdpMLineIndex: Number(x.sdpMLineIndex) || 0, sdp };
          if (remoteSet) addRemote(c);
          else remoteCand.push(c);
        }
      },
      () => {},
    );
  }

  function setState(s) {
    const id = st.callId;
    if (!id) return;
    try {
      Promise.resolve(fsM.updateDoc(callRef(id), { state: s })).catch(() => {});
    } catch (e) {
      // the other side ends it by its own timers
    }
  }

  // ------------------------------------------------------------------------------------------------ the call document
  function watchCall(id) {
    if (callUnsub) callUnsub();
    callUnsub = fsM.onSnapshot(
      callRef(id),
      (d) => onCallDoc(id, d),
      () => {},
    );
  }

  function onCallDoc(id, d) {
    if (st.callId !== id || st.phase === PHASE.IDLE || st.phase === PHASE.ENDED) return;
    if (!d || !d.exists()) return finish(null);
    const x = d.data();
    const state = str(x.state);
    if (state === "declined") return finish(isCaller ? "declined" : null);
    if (state === "ended" || state === "missed") return finish(null);
    if (!isCaller && state === "accepted" && st.phase === PHASE.INCOMING) return finish("elsewhere"); // answered on another device of mine
    if (isCaller && !answerApplied) {
      const sdp = x.answer && typeof x.answer === "object" ? str(x.answer.sdp) : "";
      if (sdp && pc) {
        answerApplied = true;
        clear(ringT);
        ringT = null;
        set({ phase: PHASE.CONNECTING });
        clear(connectT);
        connectT = T.setTimeout(() => {
          if (st.phase === PHASE.CONNECTING) finish("failed");
        }, CONNECT_MS);
        const g = gen;
        Promise.resolve(pc.setRemoteDescription({ type: "answer", sdp })).then(
          () => {
            if (g !== gen) return;
            remoteSet = true;
            flushRemote();
          },
          () => {
            if (g === gen) finish("error");
          },
        );
      }
    }
  }

  // ------------------------------------------------------------------------------------------------ incoming
  /** Starts looking for calls to me (the phone's listen): the documents where I am the callee and the state is "ringing". */
  function listen() {
    if (incomingUnsub || stopped) return;
    refreshIce();
    const q = fsM.query(fsM.collection(db, "calls"), fsM.where("callee", "==", uid), fsM.where("state", "==", "ringing"));
    incomingUnsub = fsM.onSnapshot(
      q,
      (snap) => onIncoming(snap.docs),
      () => {
        // a listener that failed does not come back by itself: look again soon
        if (incomingUnsub) incomingUnsub();
        incomingUnsub = null;
        clear(retryT);
        retryT = T.setTimeout(() => {
          retryT = null;
          listen();
        }, 5000);
      },
    );
  }

  function onIncoming(docs) {
    for (const d of docs) {
      const x = d.data();
      const ts = Number(x.ts) || 0;
      if (now() - ts > STALE_MS) continue;
      if (st.phase === PHASE.ENDED) resetNow();
      if (st.phase !== PHASE.IDLE) {
        if (d.id !== st.callId) setStateOf(d.id, "declined"); // busy: the caller is told at once
        continue;
      }
      const sdp = x.offer && typeof x.offer === "object" ? str(x.offer.sdp) : "";
      const caller = str(x.caller);
      if (!sdp || sdp.length > 200000 || !caller || caller === uid) continue;
      const video = x.kind === "video";
      const g = ++gen;
      isCaller = false;
      pendingOffer = sdp;
      refreshIce();
      set({ ...blank(), phase: PHASE.INCOMING, callId: d.id, peerUid: caller, peerName: cut(resolveName(caller, str(x.callerName)) || "KHOSQ", 60), video, cameraOn: video });
      watchCall(d.id);
      incomingT = T.setTimeout(() => {
        if (g === gen && st.phase === PHASE.INCOMING) {
          setState("missed");
          finish(null);
        }
      }, INCOMING_MS);
      return;
    }
  }

  function setStateOf(id, s) {
    try {
      Promise.resolve(fsM.updateDoc(callRef(id), { state: s })).catch(() => {});
    } catch (e) {
      // nothing to do
    }
  }

  async function media(video) {
    return rtc.getUserMedia({ audio: true, video: video ? VIDEO : false });
  }
  const stopStream = (s) => {
    if (!s) return;
    try {
      for (const t of s.getTracks()) t.stop();
    } catch (e) {
      // already stopped
    }
  };

  /** Answers the ringing call. Resolves {ok: true} or {ok: false, why}. */
  async function accept() {
    if (st.phase !== PHASE.INCOMING || !pendingOffer || !rtc) return { ok: false, why: "no" };
    const g = gen;
    const id = st.callId;
    const video = st.video;
    clear(incomingT);
    incomingT = null;
    set({ phase: PHASE.CONNECTING });
    clear(connectT);
    connectT = T.setTimeout(() => {
      if (st.phase === PHASE.CONNECTING) finish("failed");
    }, CONNECT_MS);
    let stream = null;
    let err = null;
    try {
      stream = await media(video);
    } catch (e) {
      err = e;
      if (video && mediaWhy(e) !== "denied") {
        // a video call can be answered with the microphone alone (no camera, or one that is busy)
        try {
          stream = await media(false);
          err = null;
          set({ noCamera: true, cameraOn: false });
        } catch (e2) {
          err = e2;
        }
      }
    }
    if (g !== gen) {
      stopStream(stream);
      return { ok: false, why: "gone" };
    }
    if (!stream) {
      // finish first, then tell the caller: the write is heard by my own listener, which would end the call without the reason
      finish(mediaWhy(err));
      setStateOf(id, "declined");
      return { ok: false, why: mediaWhy(err) };
    }
    localStream = stream;
    set({ local: stream, cameraOn: stream.getVideoTracks().length > 0 && st.cameraOn });
    try {
      buildPeer(stream);
      docReady = true; // my addresses go to the document at once (the caller keeps them until it has my answer)
      watchCandidates("callerCandidates");
      await pc.setRemoteDescription({ type: "offer", sdp: pendingOffer });
      if (g !== gen) return { ok: false, why: "gone" };
      remoteSet = true;
      flushRemote();
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      if (g !== gen) return { ok: false, why: "gone" };
      await fsM.updateDoc(callRef(id), { answer: { type: "answer", sdp: answer.sdp }, state: "accepted" });
    } catch (e) {
      if (g === gen) {
        finish("error");
        setStateOf(id, "ended");
      }
      return { ok: false, why: "error" };
    }
    return { ok: true };
  }

  function decline() {
    if (st.phase !== PHASE.INCOMING) return;
    setState("declined");
    finish(null);
  }

  // ------------------------------------------------------------------------------------------------ outgoing
  /**
   * Calls a person I have an accepted chat with. Resolves {ok: true, id} once the call document is written (the phone is then rung), or
   * {ok: false, why}: busy, no (a bad person), unsupported, denied (the microphone or camera was refused), noMedia (there is none), error.
   */
  async function call({ peerUid, peerName, video }) {
    if (st.phase !== PHASE.IDLE && st.phase !== PHASE.ENDED) return { ok: false, why: "busy" };
    if (typeof peerUid !== "string" || !peerUid || peerUid === uid) return { ok: false, why: "no" };
    if (!rtc || !rtc.Peer || !rtc.getUserMedia) return { ok: false, why: "unsupported" };
    if (st.phase === PHASE.ENDED) resetNow();
    const wantVideo = video === true;
    const g = ++gen;
    isCaller = true;
    const id = fsM.doc(fsM.collection(db, "calls")).id;
    refreshIce();
    set({ ...blank(), phase: PHASE.OUTGOING, callId: id, peerUid, peerName: cut(peerName || "", 60), video: wantVideo, outgoing: true, cameraOn: wantVideo });
    let stream = null;
    try {
      stream = await media(wantVideo);
    } catch (e) {
      if (g === gen) finish(mediaWhy(e));
      return { ok: false, why: mediaWhy(e) };
    }
    if (g !== gen) {
      stopStream(stream);
      return { ok: false, why: "gone" };
    }
    localStream = stream;
    set({ local: stream });
    try {
      buildPeer(stream);
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      if (g !== gen) return { ok: false, why: "gone" };
      await fsM.setDoc(callRef(id), {
        caller: uid,
        callerName: name || "",
        callee: peerUid,
        calleeName: peerName || "",
        kind: wantVideo ? "video" : "audio",
        state: "ringing",
        ts: now(),
        offer: { type: "offer", sdp: offer.sdp },
      });
    } catch (e) {
      if (g === gen) finish("error");
      return { ok: false, why: "error" };
    }
    if (g !== gen) {
      // I hung up while the document was on its way: the other side must not ring
      setStateOf(id, "ended");
      return { ok: false, why: "gone" };
    }
    docReady = true;
    const list = outCand;
    outCand = [];
    for (const c of list) pushCandidate(c);
    notify(peerUid, wantVideo);
    watchCall(id);
    watchCandidates("calleeCandidates");
    clear(ringT);
    ringT = T.setTimeout(() => {
      if (g === gen && st.phase === PHASE.OUTGOING) {
        // finish first: the write of "missed" is heard by my own listener, which would end the call without the reason (the "no answer" note)
        const id = st.callId;
        finish("noanswer");
        setStateOf(id, "missed");
      }
    }, RING_MS);
    return { ok: true, id };
  }

  function hangup() {
    switch (st.phase) {
      case PHASE.INCOMING:
        return decline();
      case PHASE.IDLE:
      case PHASE.ENDED:
        return;
      default:
        setState("ended");
        finish(null);
    }
  }

  // ------------------------------------------------------------------------------------------------ controls
  function toggleMute() {
    if (!localStream) return;
    const muted = !st.muted;
    for (const t of localStream.getAudioTracks()) t.enabled = !muted;
    set({ muted });
  }
  function toggleCamera() {
    if (!localStream || !st.video || st.noCamera) return;
    const on = !st.cameraOn;
    for (const t of localStream.getVideoTracks()) t.enabled = on;
    set({ cameraOn: on });
  }

  // ------------------------------------------------------------------------------------------------ the end
  /** The "call" line in the chat (the caller writes it, the phone too) and, for a missed call, the chat's list line. */
  function logCall(cur, note) {
    if (!cur.peerUid) return;
    let missed = false;
    let sec = 0;
    let code;
    if (cur.startedAt > 0) {
      sec = Math.max(0, Math.floor((now() - cur.startedAt) / 1000));
      code = "call_done";
    } else if (note === null) code = "call_cancelled";
    else if (note === "noanswer") {
      missed = true;
      code = "call_missed";
    } else if (note === "declined") code = "call_declined";
    else return;
    const chatId = dmId(uid, cur.peerUid);
    try {
      const batch = fsM.writeBatch(db);
      batch.set(fsM.doc(fsM.collection(db, "chats", chatId, "messages")), {
        from: uid,
        text: "",
        kind: "call",
        code,
        vid: cur.video === true,
        dur: sec,
        ts: fsM.serverTimestamp(),
      });
      if (missed) batch.update(fsM.doc(db, "chats", chatId), { lastText: "\u{1F4F5}", lastEnc: false, lastKind: "call_missed", lastTs: now(), lastFrom: uid });
      Promise.resolve(batch.commit()).catch(() => {});
    } catch (e) {
      // the line is only history
    }
  }

  function resetNow() {
    clear(resetT);
    resetT = null;
    st = blank();
    onChange({ ...st });
  }

  /** Ends the call in progress: everything is let go (the camera and microphone light goes off) and the reason stays on the screen a moment. */
  function finish(note) {
    const cur = st;
    if (cur.phase === PHASE.IDLE || cur.phase === PHASE.ENDED) return;
    clearTimers();
    if (callUnsub) callUnsub();
    if (candUnsub) candUnsub();
    callUnsub = candUnsub = null;
    const conn = pc;
    const stream = localStream;
    pc = null;
    localStream = null;
    remoteStream = null;
    pendingOffer = "";
    remoteCand = [];
    outCand = [];
    docReady = false;
    gen++;
    stopStream(stream);
    try {
      if (conn) conn.close();
    } catch (e) {
      // closed already
    }
    set({ phase: PHASE.ENDED, note: note || "", local: null, remote: null, muted: false });
    if (isCaller) {
      logCall(cur, note);
      if (cur.startedAt > 0 && cur.callId) {
        const sec = Math.max(0, Math.floor((now() - cur.startedAt) / 1000));
        setDur(cur.callId, sec);
      }
    }
    const endedId = cur.callId;
    clear(resetT);
    resetT = T.setTimeout(
      () => {
        resetT = null;
        if (st.phase === PHASE.ENDED && st.callId === endedId) resetNow();
      },
      note ? (LONG_NOTES.has(note) ? NOTE_LONG_MS : NOTE_MS) : 400,
    );
  }

  function setDur(id, sec) {
    try {
      Promise.resolve(fsM.updateDoc(callRef(id), { dur: sec })).catch(() => {});
    } catch (e) {
      // only history
    }
  }

  /** The page goes away: the call ends, the listeners stop. */
  function stop() {
    stopped = true;
    if (st.phase !== PHASE.IDLE && st.phase !== PHASE.ENDED) hangup();
    clearTimers();
    clear(retryT);
    clear(resetT);
    if (incomingUnsub) incomingUnsub();
    if (callUnsub) callUnsub();
    if (candUnsub) candUnsub();
    incomingUnsub = callUnsub = candUnsub = null;
  }

  return { listen, call, accept, decline, hangup, toggleMute, toggleCamera, stop, refreshIce, state: () => ({ ...st }) };
}
