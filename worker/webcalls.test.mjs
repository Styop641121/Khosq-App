// Calls on the web page (docs/calls.js): the documents must be what the phone writes and reads (CallManager.kt, the Firestore rules, the Worker's
// "call" push and "ice"), and the state machine must behave like the phone's, with the few differences the file says. Whole calls are run between
// two instances of the page's code, a pretend Firestore that both of them watch, a pretend RTCPeerConnection and a pretend clock.
// Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { makeCalls, parseIce, mediaWhy, dmId, PHASE, FALLBACK_ICE, RING_MS, CONNECT_MS, GRACE_MS, STALE_MS, INCOMING_MS, ICE_FRESH_MS, ICE_REFRESH_MS, NOTE_MS, NOTE_LONG_MS } from "../docs/calls.js";

const ME = "Mm00000000000000000000000001";
const BOB = "Bb00000000000000000000000002";
const CAROL = "Cc00000000000000000000000003";
const WORKER = "https://worker.example/";
const flush = async () => {
  for (let i = 0; i < 6; i++) await new Promise((r) => setImmediate(r));
};

// ---------------------------------------------------------------------------------------------- a pretend Firestore both sides watch
function makeStore({ syncEcho = false } = {}) {
  const docs = new Map(); // "calls/x" -> data
  const subs = new Set();
  const writes = [];
  let seq = 0;
  const clone = (x) => (x === undefined ? undefined : JSON.parse(JSON.stringify(x)));
  const parent = (p) => p.slice(0, p.lastIndexOf("/"));
  const listOf = (colPath, cs = []) => {
    const out = [];
    for (const [p, d] of docs) {
      if (parent(p) !== colPath) continue;
      if (cs.every((c) => c.t !== "where" || d[c.field] === c.value)) out.push({ id: p.slice(p.lastIndexOf("/") + 1), data: clone(d), path: p });
    }
    return out;
  };
  const view = (s) => {
    if (s.target.kind === "doc") {
      const d = docs.get(s.target.path);
      return d === undefined ? { key: "none" } : { key: JSON.stringify(d) };
    }
    return { key: JSON.stringify(listOf(s.target.path, s.target.cs || [])) };
  };
  const fire = (s, first) => {
    const v = view(s);
    if (!first && v.key === s.last) return;
    s.last = v.key;
    if (s.target.kind === "doc") {
      const d = docs.get(s.target.path);
      s.next({ id: s.target.id, exists: () => d !== undefined, data: () => clone(d) });
      return;
    }
    const cur = listOf(s.target.path, s.target.cs || []);
    const changes = [];
    const seen = new Map(s.seen);
    for (const d of cur) {
      const before = seen.get(d.id);
      const mk = () => ({ id: d.id, data: () => clone(d.data) });
      if (before === undefined) changes.push({ type: "added", doc: mk() });
      else if (before !== JSON.stringify(d.data)) changes.push({ type: "modified", doc: mk() });
      seen.delete(d.id);
    }
    for (const [id] of seen) changes.push({ type: "removed", doc: { id, data: () => ({}) } });
    s.seen = new Map(cur.map((d) => [d.id, JSON.stringify(d.data)]));
    s.next({ docs: cur.map((d) => ({ id: d.id, data: () => clone(d.data) })), docChanges: () => changes });
  };
  const hear = () => { for (const s of [...subs]) if (!s.dead) fire(s, false); };
  // a real Firestore tells the listeners of a local write a moment later; some stand-ins tell them at once, inside the write itself: [syncEcho]
  const notify = () => (syncEcho ? hear() : queueMicrotask(hear));
  const store = {
    docs,
    writes,
    hold: null, // a promise the next setDoc waits for
    failListen: false,
    client() {
      const fsM = {
        collection: (db, ...p) => ({ kind: "col", path: p.join("/") }),
        doc: (a, ...p) => (a && a.kind === "col" ? { kind: "doc", path: a.path + "/auto" + ++seq, id: "auto" + seq } : { kind: "doc", path: p.join("/"), id: p[p.length - 1] }),
        where: (field, op, value) => ({ t: "where", field, op, value }),
        query: (col, ...cs) => ({ kind: "q", path: col.path, cs }),
        serverTimestamp: () => ({ sv: "ts" }),
        async setDoc(ref, data) {
          if (store.hold) await store.hold;
          writes.push({ kind: "set", path: ref.path, data: clone(data) });
          docs.set(ref.path, clone(data));
          notify();
        },
        async updateDoc(ref, data) {
          writes.push({ kind: "update", path: ref.path, data: clone(data) });
          if (!docs.has(ref.path)) throw new Error("no document");
          docs.set(ref.path, { ...docs.get(ref.path), ...clone(data) });
          notify();
        },
        async addDoc(col, data) {
          const m = /^calls\/([^/]+)\//.exec(col.path);
          if (m && !docs.has("calls/" + m[1])) throw Object.assign(new Error("denied"), { code: "permission-denied" }); // the rules read the call document first
          const id = "c" + ++seq;
          writes.push({ kind: "add", path: col.path + "/" + id, data: clone(data) });
          docs.set(col.path + "/" + id, clone(data));
          notify();
          return { id };
        },
        writeBatch() {
          const ops = [];
          return {
            set(ref, data) { ops.push({ kind: "set", path: ref.path, data }); return this; },
            update(ref, data) { ops.push({ kind: "update", path: ref.path, data }); return this; },
            async commit() {
              for (const op of ops) {
                writes.push({ kind: op.kind, path: op.path, data: clone(op.data), batch: true });
                if (op.kind === "set") docs.set(op.path, clone(op.data));
                else docs.set(op.path, { ...(docs.get(op.path) || {}), ...clone(op.data) });
              }
              notify();
            },
          };
        },
        onSnapshot(target, next, err) {
          if (store.failListen && target.kind === "q") {
            queueMicrotask(() => err && err(new Error("denied")));
            return () => {};
          }
          const s = { target, next, err, last: "", seen: new Map(), dead: false };
          subs.add(s);
          queueMicrotask(() => { if (!s.dead) fire(s, true); });
          return () => { s.dead = true; subs.delete(s); };
        },
      };
      return { db: {}, fsM, auth: { currentUser: { getIdToken: async () => "token" } } };
    },
    listeners: () => [...subs].filter((s) => !s.dead).length,
    collection: (colPath) => listOf(colPath),
  };
  return store;
}

// ---------------------------------------------------------------------------------------------- a pretend clock
function makeClock() {
  const c = { t: 1_800_000_000_000, timers: [], seq: 0 };
  c.now = () => c.t;
  c.setTimeout = (fn, ms) => {
    const id = ++c.seq;
    c.timers.push({ id, at: c.t + ms, fn });
    return id;
  };
  c.clearTimeout = (id) => {
    c.timers = c.timers.filter((x) => x.id !== id);
  };
  c.pending = () => c.timers.length;
  c.advance = async (ms) => {
    const end = c.t + ms;
    for (;;) {
      c.timers.sort((a, b) => a.at - b.at);
      const next = c.timers[0];
      if (!next || next.at > end) break;
      c.timers.shift();
      c.t = Math.max(c.t, next.at);
      next.fn();
      await flush();
    }
    c.t = end;
    await flush();
  };
  return c;
}

// ---------------------------------------------------------------------------------------------- a pretend browser: peers, microphone and camera
function makeWorld() {
  const peers = [];
  const gum = [];
  const w = { peers, gum, fail: null, failVideo: null, streams: [], locals: [], made: 0 };
  const track = (kind) => ({ kind, enabled: true, stopped: false, stop() { this.stopped = true; } });
  const stream = (kinds) => {
    const tracks = kinds.map(track);
    const s = { tracks, getTracks: () => s.tracks, getAudioTracks: () => s.tracks.filter((t) => t.kind === "audio"), getVideoTracks: () => s.tracks.filter((t) => t.kind === "video"), addTrack: (t) => s.tracks.push(t) };
    w.streams.push(s);
    return s;
  };
  class Peer {
    constructor(cfg) {
      this.cfg = cfg;
      this.tracks = [];
      this.localDescription = null;
      this.remoteDescription = null;
      this.iceConnectionState = "new";
      this.added = [];
      this.closed = false;
      this.no = w.made++; // every peer has addresses of its own: what comes from whom can be told apart
      peers.push(this);
    }
    addTrack(t) { this.tracks.push(t); }
    async createOffer() { return { type: "offer", sdp: "offer kinds=" + this.tracks.map((t) => t.kind).join(",") }; }
    async createAnswer() { return { type: "answer", sdp: "answer kinds=" + this.tracks.map((t) => t.kind).join(",") }; }
    async setLocalDescription(d) {
      this.localDescription = d;
      for (let i = 1; i <= 2; i++) queueMicrotask(() => this.onicecandidate && this.onicecandidate({ candidate: { candidate: "candidate:" + i + " 1 udp 2122260223 192.0.2." + (this.no * 10 + i) + " " + (50000 + this.no * 10 + i) + " typ host", sdpMid: "0", sdpMLineIndex: 0 } }));
      queueMicrotask(() => this.onicecandidate && this.onicecandidate({ candidate: null }));
    }
    async setRemoteDescription(d) {
      // a real browser takes a moment: the description is "there" only after the promise has settled, and an address added before that is refused
      for (let i = 0; i < 4; i++) await null;
      this.remoteDescription = d;
      const kinds = (/kinds=(\S*)/.exec(d.sdp) || [])[1] || "";
      const rs = stream([]);
      for (const k of kinds.split(",").filter(Boolean)) queueMicrotask(() => { const t = track(k); rs.tracks.push(t); this.ontrack && this.ontrack({ track: t, streams: [rs] }); });
    }
    async addIceCandidate(c) {
      if (!this.remoteDescription) throw Object.assign(new Error("no remote description"), { name: "InvalidStateError" });
      this.added.push(c);
    }
    close() { this.closed = true; }
    ice(state) { this.iceConnectionState = state; this.oniceconnectionstatechange && this.oniceconnectionstatechange(); }
  }
  w.rtc = {
    Peer,
    Stream: function () { return stream([]); },
    async getUserMedia(c) {
      gum.push(c);
      if (w.fail) throw Object.assign(new Error("x"), { name: w.fail });
      if (c.video && w.failVideo) throw Object.assign(new Error("x"), { name: w.failVideo });
      const s = stream(c.video ? ["audio", "video"] : ["audio"]);
      w.locals.push(s);
      return s;
    },
  };
  return w;
}

/** Two people, each with their own page code, on one Firestore, one clock and one pretend browser. */
function pair(opts = {}) {
  const store = opts.store || makeStore();
  const clock = opts.clock || makeClock();
  const browser = makeWorld();
  const requests = [];
  const mk = (uid, name, extra = {}) => {
    const states = [];
    const c = makeCalls({ f: store.client(), uid, name, worker: WORKER, rtc: browser.rtc, timers: clock, now: clock.now, onChange: (s) => states.push(s), resolveName: (u, claimed) => (u === BOB ? "Բոբ" : u === ME ? "Ստյոպա" : claimed), ...extra });
    return { c, states, uid, last: () => states[states.length - 1] || c.state() };
  };
  const a = mk(ME, "Ստյոպա");
  const b = mk(BOB, "Բոբ");
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    const body = JSON.parse(init.body);
    requests.push({ url, headers: init.headers, body });
    if (body.type === "ice") return opts.ice ? { ok: true, json: async () => opts.ice } : { ok: false, json: async () => ({}) };
    return { ok: true, json: async () => ({}) };
  };
  return { a, b, store, clock, browser, requests, opts, done: () => (globalThis.fetch = realFetch), mk };
}
const phase = (x) => x.c.state().phase;

// ---------------------------------------------------------------------------------------------- tests
test("small helpers: the chat id, the Worker's TURN list (port 53 left out), what a refused microphone is called", () => {
  assert.equal(dmId(ME, BOB), ME < BOB ? ME + "_" + BOB : BOB + "_" + ME);
  assert.equal(dmId(BOB, ME), dmId(ME, BOB));
  assert.deepEqual(
    parseIce({ iceServers: [{ urls: ["stun:s.example:3478", "stun:s.example:53"] }, { urls: "turn:t.example:3478?transport=udp", username: "u", credential: "p" }, { urls: ["turn:x:53"] }, null, { urls: 5 }, { urls: "turn:t:1", username: "", credential: "" }] }),
    [{ urls: ["stun:s.example:3478"] }, { urls: ["turn:t.example:3478?transport=udp"], username: "u", credential: "p" }, { urls: ["turn:t:1"] }],
  );
  assert.deepEqual(parseIce(null), []);
  assert.deepEqual(parseIce({ iceServers: "no" }), []);
  assert.equal(mediaWhy({ name: "NotAllowedError" }), "denied");
  assert.equal(mediaWhy({ name: "NotFoundError" }), "noMedia");
  assert.equal(mediaWhy({ name: "AbortError" }), "error");
  assert.equal(mediaWhy(null), "error");
  assert.equal(FALLBACK_ICE.length, 5);
  assert.deepEqual([RING_MS, CONNECT_MS, GRACE_MS, STALE_MS], [45000, 30000, 8000, 120000]);
});

test("a whole voice call: the exact documents, the push, the addresses both ways, connected, hung up, the line in the chat", async () => {
  const p = pair({ ice: { iceServers: [{ urls: ["turn:own.example:3478"], username: "u1", credential: "c1" }] } });
  try {
    p.b.c.listen();
    p.a.c.listen();
    await flush();
    await p.a.c.refreshIce();
    const r = await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    assert.equal(r.ok, true);
    await flush();
    // the call document: exactly the phone's fields
    const docs = p.store.collection("calls");
    assert.equal(docs.length, 1);
    const d = docs[0].data;
    assert.deepEqual(Object.keys(d).sort(), ["callee", "calleeName", "caller", "callerName", "kind", "offer", "state", "ts"]);
    assert.deepEqual({ caller: d.caller, callerName: d.callerName, callee: d.callee, calleeName: d.calleeName, kind: d.kind, state: d.state, ts: d.ts }, { caller: ME, callerName: "Ստյոպա", callee: BOB, calleeName: "Բոբ", kind: "audio", state: "ringing", ts: p.clock.t });
    assert.deepEqual(d.offer, { type: "offer", sdp: "offer kinds=audio" });
    assert.equal(r.id, docs[0].id);
    // the push for the phone: the phone's call push (chat id, "call", my name, the sign of the kind)
    const pushes = p.requests.filter((x) => x.body.type === "call");
    assert.deepEqual(pushes.map((x) => x.body), [{ chatId: dmId(ME, BOB), type: "call", title: "Ստյոպա", body: "\u{1F4DE}" }]);
    assert.equal(pushes[0].headers.Authorization, "Bearer token");
    // the relay's keys from the Worker are used, with the first two free servers after them
    assert.deepEqual(p.browser.peers[0].cfg.iceServers, [{ urls: ["turn:own.example:3478"], username: "u1", credential: "c1" }, ...FALLBACK_ICE.slice(0, 2)]);
    // the callee rings
    assert.equal(phase(p.b), PHASE.INCOMING);
    const ring = p.b.c.state();
    assert.deepEqual([ring.peerUid, ring.peerName, ring.video, ring.outgoing], [ME, "Ստյոպա", false, false]);
    assert.equal(phase(p.a), PHASE.OUTGOING);
    // answers
    assert.deepEqual(await p.b.c.accept(), { ok: true });
    await flush();
    const after = p.store.collection("calls")[0].data;
    assert.equal(after.state, "accepted");
    assert.deepEqual(after.answer, { type: "answer", sdp: "answer kinds=audio" });
    assert.equal(phase(p.a), PHASE.CONNECTING);
    assert.equal(phase(p.b), PHASE.CONNECTING);
    // the addresses: each side's, in its own collection, exactly the phone's three fields
    const id = r.id;
    const caller = p.store.collection("calls/" + id + "/callerCandidates").map((x) => x.data);
    const callee = p.store.collection("calls/" + id + "/calleeCandidates").map((x) => x.data);
    assert.equal(caller.length, 2);
    assert.equal(callee.length, 2);
    assert.deepEqual(Object.keys(caller[0]).sort(), ["sdp", "sdpMLineIndex", "sdpMid"]);
    assert.deepEqual(caller[0], { sdpMid: "0", sdpMLineIndex: 0, sdp: "candidate:1 1 udp 2122260223 192.0.2.1 50001 typ host" });
    assert.deepEqual(callee[0], { sdpMid: "0", sdpMLineIndex: 0, sdp: "candidate:1 1 udp 2122260223 192.0.2.11 50011 typ host" }, "the callee's own addresses are in its own collection");
    // each side got the other's addresses (the first two peers are the caller's and the callee's)
    const [pa, pb] = p.browser.peers;
    assert.equal(pa.added.length, 2);
    assert.equal(pb.added.length, 2);
    assert.deepEqual(pa.added[0], { candidate: "candidate:1 1 udp 2122260223 192.0.2.11 50011 typ host", sdpMLineIndex: 0, sdpMid: "0" }, "the caller got the callee's address");
    assert.deepEqual(pb.added[0], { candidate: "candidate:1 1 udp 2122260223 192.0.2.1 50001 typ host", sdpMLineIndex: 0, sdpMid: "0" }, "the callee got the caller's");
    assert.deepEqual(pa.added.map((c) => c.candidate.split(" ")[4]).sort(), ["192.0.2.11", "192.0.2.12"], "and only the other side's, none of its own");
    assert.deepEqual(pb.added.map((c) => c.candidate.split(" ")[4]).sort(), ["192.0.2.1", "192.0.2.2"]);
    // nothing was written to the call before the call document was (the rules read it first): the document is the first write of the call
    const first = p.store.writes.findIndex((x) => x.path.startsWith("calls/"));
    assert.equal(p.store.writes[first].path, "calls/" + id, "the call document comes first");
    assert.equal(p.store.writes[first].kind, "set");
    assert.equal(p.store.writes.filter((x) => x.path.includes("Candidates/")).length, 4, "and none of the four addresses was refused");
    // connected
    pa.ice("connected");
    pb.ice("completed");
    assert.equal(phase(p.a), PHASE.CONNECTED);
    assert.equal(phase(p.b), PHASE.CONNECTED);
    assert.equal(p.a.c.state().startedAt, p.clock.t);
    assert.ok(p.a.c.state().remote);
    await p.clock.advance(65_000);
    // hang up on the caller's side
    p.a.c.hangup();
    await flush();
    assert.equal(p.store.collection("calls")[0].data.state, "ended");
    assert.equal(p.store.collection("calls")[0].data.dur, 65);
    assert.equal(phase(p.a), PHASE.ENDED);
    assert.equal(phase(p.b), PHASE.ENDED);
    // the line in the chat, written by the caller only
    const lines = p.store.writes.filter((x) => x.path.startsWith("chats/") && x.path.includes("/messages/"));
    assert.equal(lines.length, 1);
    assert.equal(lines[0].path.split("/")[1], dmId(ME, BOB));
    assert.deepEqual(lines[0].data, { from: ME, text: "", kind: "call", code: "call_done", vid: false, dur: 65, ts: { sv: "ts" } });
    assert.equal(p.store.writes.filter((x) => x.path.startsWith("chats/") && !x.path.includes("/messages/")).length, 0, "a finished call does not touch the chat's list line");
    // everything is let go
    assert.ok(pa.closed && pb.closed);
    assert.equal(p.browser.locals.length, 2);
    assert.ok(p.browser.locals.every((s) => s.tracks.length > 0 && s.tracks.every((t) => t.stopped)), "the microphone is let go on both sides");
    await p.clock.advance(2000);
    assert.equal(phase(p.a), PHASE.IDLE);
    assert.equal(phase(p.b), PHASE.IDLE);
  } finally {
    p.done();
  }
});

test("a video call: the camera is asked for like the phone does, the picture comes back, mute and camera switches touch only their own tracks", async () => {
  const p = pair();
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: true });
    await flush();
    assert.deepEqual(p.browser.gum[0], { audio: true, video: { width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 24 } } });
    const d = p.store.collection("calls")[0].data;
    assert.equal(d.kind, "video");
    assert.deepEqual(d.offer, { type: "offer", sdp: "offer kinds=audio,video" });
    assert.equal(p.requests.find((x) => x.body.type === "call").body.body, "\u{1F4F9}");
    assert.equal(p.b.c.state().video, true);
    assert.equal(p.b.c.state().cameraOn, true);
    await p.b.c.accept();
    await flush();
    const [pa, pb] = p.browser.peers;
    pa.ice("connected");
    pb.ice("connected");
    const local = p.a.c.state().local;
    p.a.c.toggleMute();
    assert.equal(p.a.c.state().muted, true);
    assert.deepEqual(local.getAudioTracks().map((t) => t.enabled), [false]);
    assert.deepEqual(local.getVideoTracks().map((t) => t.enabled), [true]);
    p.a.c.toggleCamera();
    assert.equal(p.a.c.state().cameraOn, false);
    assert.deepEqual(local.getVideoTracks().map((t) => t.enabled), [false]);
    assert.deepEqual(local.getAudioTracks().map((t) => t.enabled), [false]);
    p.a.c.toggleMute();
    p.a.c.toggleCamera();
    assert.deepEqual([...local.getAudioTracks(), ...local.getVideoTracks()].map((t) => t.enabled), [true, true]);
    assert.equal(p.a.c.state().remote.getTracks().length, 2, "both the sound and the picture arrived");
    p.b.c.hangup();
    await flush();
    const line = p.store.writes.find((x) => x.path.includes("/messages/")).data;
    assert.equal(line.vid, true);
    assert.equal(line.code, "call_done");
  } finally {
    p.done();
  }
});

test("declined: the caller is told and writes 'declined'; the callee writes nothing; the document says declined", async () => {
  const p = pair();
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    p.b.c.decline();
    await flush();
    assert.equal(p.store.collection("calls")[0].data.state, "declined");
    assert.equal(p.a.c.state().note, "declined");
    assert.equal(phase(p.a), PHASE.ENDED);
    assert.equal(phase(p.b), PHASE.ENDED);
    assert.equal(p.b.c.state().note, "");
    const lines = p.store.writes.filter((x) => x.path.includes("/messages/"));
    assert.deepEqual(lines.map((x) => [x.data.from, x.data.code, x.data.dur]), [[ME, "call_declined", 0]]);
    assert.ok(p.browser.peers[0].closed);
  } finally {
    p.done();
  }
});

test("cancelled by the caller while it rings: 'ended', a 'cancelled' line, the callee stops ringing", async () => {
  const p = pair();
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    assert.equal(phase(p.b), PHASE.INCOMING);
    p.a.c.hangup();
    await flush();
    assert.equal(p.store.collection("calls")[0].data.state, "ended");
    assert.equal(phase(p.b), PHASE.ENDED);
    assert.deepEqual(p.store.writes.filter((x) => x.path.includes("/messages/")).map((x) => x.data.code), ["call_cancelled"]);
    assert.equal(p.clock.pending() > 0, true);
    await p.clock.advance(5000);
    assert.equal(p.clock.pending(), 0, "no timer is left running");
  } finally {
    p.done();
  }
});

test("nobody answers, and the store tells my own listener of the 'missed' write inside the write: the reason (No answer) is still shown", async () => {
  const p = pair({ store: makeStore({ syncEcho: true }) });
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    await p.clock.advance(RING_MS + 500);
    assert.equal(p.store.collection("calls")[0].data.state, "missed");
    assert.equal(p.a.c.state().note, "noanswer", "the note is not lost to the echo of my own write");
    assert.equal(p.store.writes.filter((x) => x.path.startsWith("chats/") && x.data.code === "call_missed").length, 1, "one line");
  } finally {
    p.done();
  }
});

test("a store that tells my listener at once: a refused microphone on answering still shows its reason, and the caller is told 'declined'", async () => {
  const p = pair({ store: makeStore({ syncEcho: true }) });
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    p.browser.fail = "NotAllowedError";
    const r = await p.b.c.accept();
    assert.equal(r.ok, false);
    assert.equal(p.b.c.state().note, "denied", "the reason is not lost to the echo of my own write");
    assert.equal(p.store.collection("calls")[0].data.state, "declined");
  } finally {
    p.done();
  }
});

test("nobody answers for 45 seconds: 'missed' in the document, a 'missed' line, and the chat's list line is written", async () => {
  const p = pair();
  try {
    p.b.c.listen();
    await flush();
    const t0 = p.clock.t;
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    await p.clock.advance(RING_MS - 1000);
    assert.equal(phase(p.a), PHASE.OUTGOING);
    await p.clock.advance(1500);
    assert.equal(p.store.collection("calls")[0].data.state, "missed");
    assert.equal(p.a.c.state().note, "noanswer");
    assert.ok(p.b.states.some((x) => x.phase === PHASE.ENDED), "the callee's ringing stopped");
    const w = p.store.writes.filter((x) => x.path.startsWith("chats/"));
    assert.deepEqual(w.map((x) => [x.kind, x.data.code || x.data.lastKind]), [["set", "call_missed"], ["update", "call_missed"]]);
    assert.deepEqual(w[1].data, { lastText: "\u{1F4F5}", lastEnc: false, lastKind: "call_missed", lastTs: t0 + RING_MS, lastFrom: ME });
    assert.equal(w[1].path, "chats/" + dmId(ME, BOB));
  } finally {
    p.done();
  }
});

test("busy: a second call is declined at once; I cannot start a call while in one; nobody calls himself", async () => {
  const p = pair();
  try {
    const carol = p.mk(CAROL, "Կարոլ");
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    await p.b.c.accept();
    await flush();
    // Carol calls Bob who is in a call
    const x = await carol.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    assert.equal(x.ok, true);
    await flush();
    const second = p.store.collection("calls").find((d) => d.data.caller === CAROL);
    assert.equal(second.data.state, "declined");
    assert.equal(carol.c.state().note, "declined");
    assert.equal(phase(p.b), PHASE.CONNECTING, "the call in progress is not disturbed");
    assert.deepEqual(await p.a.c.call({ peerUid: CAROL, peerName: "Կարոլ", video: false }), { ok: false, why: "busy" });
    const idle = p.mk(ME, "x");
    assert.deepEqual(await idle.c.call({ peerUid: ME, peerName: "x", video: false }), { ok: false, why: "no" });
    assert.deepEqual(await idle.c.call({ peerUid: "", peerName: "x", video: false }), { ok: false, why: "no" });
  } finally {
    p.done();
  }
});

test("what is not rung for: an old document, my own call, one without an offer, one that is already answered", async () => {
  const p = pair();
  try {
    const put = (id, extra) => p.store.docs.set("calls/" + id, { caller: BOB, callerName: "Բոբ", callee: ME, calleeName: "x", kind: "audio", state: "ringing", ts: p.clock.t, offer: { type: "offer", sdp: "offer kinds=audio" }, ...extra });
    put("old", { ts: p.clock.t - STALE_MS - 1 });
    put("mine", { caller: ME });
    put("nooffer", { offer: undefined });
    put("empty", { offer: { type: "offer", sdp: "" } });
    put("done", { state: "accepted" });
    put("huge", { offer: { type: "offer", sdp: "x".repeat(200001) } });
    p.a.c.listen();
    await flush();
    assert.equal(phase(p.a), PHASE.IDLE, "an offer longer than 200000 characters is not rung for");
    put("fresh", {});
    p.store.client().fsM.updateDoc({ path: "calls/fresh" }, { ts: p.clock.t });
    await flush();
    assert.equal(phase(p.a), PHASE.INCOMING);
    assert.equal(p.a.c.state().callId, "fresh");
  } finally {
    p.done();
  }
  // the biggest offer that is still accepted
  const q = pair();
  try {
    q.store.docs.set("calls/big", { caller: BOB, callerName: "Բոբ", callee: ME, calleeName: "x", kind: "audio", state: "ringing", ts: q.clock.t, offer: { type: "offer", sdp: "x".repeat(200000) } });
    q.a.c.listen();
    await flush();
    assert.equal(phase(q.a), PHASE.INCOMING, "200000 characters are still fine");
  } finally {
    q.done();
  }
});

test("the end button of the window while a call is ringing is a refusal (hangup = decline): the caller is told, no line from the callee", async () => {
  const p = pair();
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    assert.equal(phase(p.b), PHASE.INCOMING);
    p.b.c.hangup();
    await flush();
    assert.equal(p.store.collection("calls")[0].data.state, "declined");
    assert.equal(phase(p.b), PHASE.ENDED);
    assert.equal(p.a.c.state().note, "declined");
    assert.equal(p.store.writes.filter((x) => x.path.includes("/messages/")).map((x) => x.data.code).join(), "call_declined");
  } finally {
    p.done();
  }
});

test("a refused camera on a video call that is answered: the call is declined with the reason (not answered with the microphone alone)", async () => {
  const p = pair();
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: true });
    await flush();
    p.browser.failVideo = "NotAllowedError";
    const r = await p.b.c.accept();
    assert.deepEqual(r, { ok: false, why: "denied" });
    assert.equal(p.b.c.state().note, "denied");
    assert.equal(p.store.collection("calls")[0].data.state, "declined");
    assert.equal(p.browser.gum.filter((c) => !c.video).length, 0, "the microphone alone was not tried (every request was for a picture too)");
    assert.equal(p.browser.peers.length, 1, "no connection was made on this side (only the caller's)");
  } finally {
    p.done();
  }
});

test("how long the reason of an ended call stays: a short one 1.8 s, one the person has to act on 5 s, a quiet end almost at once", async () => {
  const p = pair();
  try {
    p.browser.fail = "NotAllowedError";
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    assert.equal(phase(p.a), PHASE.ENDED);
    await p.clock.advance(NOTE_MS + 100);
    assert.equal(phase(p.a), PHASE.ENDED, "a refused microphone is still told after the short time");
    await p.clock.advance(NOTE_LONG_MS - NOTE_MS);
    assert.equal(phase(p.a), PHASE.IDLE, "and gone after the long one");
    p.browser.fail = null;
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    p.b.c.decline();
    await flush();
    assert.equal(p.a.c.state().note, "declined");
    await p.clock.advance(NOTE_MS - 100);
    assert.equal(phase(p.a), PHASE.ENDED);
    await p.clock.advance(300);
    assert.equal(phase(p.a), PHASE.IDLE, "a short reason goes after 1.8 s");
    assert.ok(NOTE_MS < NOTE_LONG_MS);
  } finally {
    p.done();
  }
});

test("the name rung for is the contact's own name when there is one, never longer than 60 characters; the sound or video of the offer is read", async () => {
  const p = pair();
  try {
    const put = (id, extra) => p.store.docs.set("calls/" + id, { caller: CAROL, callerName: "Ն".repeat(100), callee: ME, calleeName: "x", kind: "video", state: "ringing", ts: p.clock.t, offer: { type: "offer", sdp: "offer kinds=audio,video" }, ...extra });
    put("long", {});
    p.a.c.listen();
    await flush();
    assert.equal(p.a.c.state().peerName.length, 60);
    assert.equal(p.a.c.state().video, true);
    assert.equal(p.a.c.state().cameraOn, true);
    p.a.c.decline();
    await flush();
    put("bob", { caller: BOB, callerName: "someone else" });
    p.store.client().fsM.updateDoc({ path: "calls/bob" }, { ts: p.clock.t });
    await p.clock.advance(1000);
    assert.equal(p.a.c.state().peerName, "Բոբ", "the name of the contact, not the one the caller wrote into the document");
  } finally {
    p.done();
  }
});

test("answered on another device of mine: this one stops ringing, with no writes of its own", async () => {
  const p = pair();
  try {
    const second = p.mk(BOB, "Բոբ");
    p.b.c.listen();
    second.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    assert.equal(phase(p.b), PHASE.INCOMING);
    assert.equal(phase(second), PHASE.INCOMING);
    const before = p.store.writes.length;
    await second.c.accept();
    await flush();
    assert.equal(phase(p.b), PHASE.ENDED);
    assert.equal(p.b.c.state().note, "elsewhere");
    assert.equal(phase(second), PHASE.CONNECTING, "the one that answered goes on");
    const mine = p.store.writes.slice(before).filter((w) => w.data && w.data.state && w.data.state !== "accepted");
    assert.deepEqual(mine, [], "the one that lost does not write 'declined' or 'ended' over the answered call");
    assert.equal(p.store.collection("calls")[0].data.state, "accepted");
  } finally {
    p.done();
  }
});

test("the microphone refused: no call document is written, nothing is left on, the person is told why", async () => {
  const p = pair();
  try {
    p.browser.fail = "NotAllowedError";
    const r = await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    assert.deepEqual(r, { ok: false, why: "denied" });
    assert.equal(p.a.c.state().note, "denied");
    assert.equal(phase(p.a), PHASE.ENDED);
    assert.equal(p.store.collection("calls").length, 0);
    assert.equal(p.store.writes.length, 0);
    assert.equal(p.browser.peers.length, 0);
    p.browser.fail = "NotFoundError";
    await p.clock.advance(3000);
    assert.deepEqual(await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: true }), { ok: false, why: "noMedia" });
    p.browser.fail = null;
    await p.clock.advance(3000);
    assert.deepEqual(await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false }), { ok: true, id: p.store.collection("calls")[0].id }, "and it works again once the microphone is allowed");
  } finally {
    p.done();
  }
});

test("a video call answered where there is no camera: the call is answered with the microphone alone; a refused microphone declines", async () => {
  const p = pair();
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: true });
    await flush();
    p.browser.failVideo = "NotFoundError";
    await p.b.c.accept();
    await flush();
    assert.equal(p.b.c.state().noCamera, true);
    assert.equal(p.b.c.state().cameraOn, false);
    assert.equal(p.store.collection("calls")[0].data.state, "accepted");
    assert.equal(p.store.collection("calls")[0].data.answer.sdp, "answer kinds=audio");
    p.b.c.toggleCamera();
    assert.equal(p.b.c.state().cameraOn, false, "there is no camera to switch");
    p.b.c.hangup();
    await flush();
    // now a refused microphone
    await p.clock.advance(3000);
    p.browser.failVideo = null;
    p.browser.fail = "NotAllowedError";
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false }).catch(() => {});
    p.browser.fail = null;
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    p.browser.fail = "NotAllowedError";
    await p.b.c.accept();
    await flush();
    const last = p.store.collection("calls").find((d) => d.data.state === "declined");
    assert.ok(last, "the document says declined");
    assert.equal(p.b.c.state().note, "denied");
  } finally {
    p.done();
  }
});

test("addresses that come before the answer are kept and added in order afterwards (a browser refuses them without a remote description)", async () => {
  const p = pair();
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    const id = p.store.collection("calls")[0].id;
    // the caller's addresses are in the document; the callee has not answered yet
    p.browser.peers.length = 1;
    await p.b.c.accept();
    await flush();
    const pb = p.browser.peers[1];
    assert.equal(pb.added.length, 2, "both were added after the offer was set");
    assert.deepEqual(pb.added.map((c) => c.candidate.split(" ")[0]), ["candidate:1", "candidate:2"]);
    // an address with no sdpMid is added by its line number alone
    p.store.docs.set("calls/" + id + "/callerCandidates/late", { sdpMid: "", sdpMLineIndex: 1, sdp: "candidate:9 1 udp 1 192.0.2.9 1 typ host" });
    p.store.client().fsM.updateDoc({ path: "calls/" + id }, { x: 1 });
    p.store.docs.set("calls/" + id + "/callerCandidates/late2", { sdpMid: "", sdpMLineIndex: 1, sdp: "candidate:10 1 udp 1 192.0.2.9 2 typ host" });
    await flush();
    const late = pb.added.find((c) => c.candidate.startsWith("candidate:10"));
    assert.deepEqual(late, { candidate: "candidate:10 1 udp 1 192.0.2.9 2 typ host", sdpMLineIndex: 1 });
  } finally {
    p.done();
  }
});

test("the connection fails, drops for good, or drops and comes back; an answer that never connects is ended after 30 seconds", async () => {
  // failed
  let p = pair();
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    await p.b.c.accept();
    await flush();
    p.browser.peers[0].ice("connected");
    p.browser.peers[1].ice("connected");
    await p.clock.advance(5000);
    p.browser.peers[0].ice("failed");
    await flush();
    assert.equal(p.a.c.state().note, "failed");
    assert.equal(p.store.collection("calls")[0].data.state, "accepted", "nothing is written (as the phone): the other side ends by its own view of the connection");
    assert.equal(phase(p.b), PHASE.CONNECTED);
    assert.equal(p.store.collection("calls")[0].data.dur, 5, "but the length of what was said is kept");
    assert.deepEqual(p.store.writes.filter((x) => x.path.includes("/messages/")).map((x) => [x.data.code, x.data.dur]), [["call_done", 5]], "and the chat says a call of that length took place");
    p.browser.peers[1].ice("failed");
    await flush();
    assert.equal(p.b.c.state().note, "failed");
  } finally {
    p.done();
  }
  // dropped, comes back inside the grace time
  p = pair();
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    await p.b.c.accept();
    await flush();
    p.browser.peers[0].ice("connected");
    p.browser.peers[1].ice("connected");
    p.browser.peers[0].ice("disconnected");
    await p.clock.advance(GRACE_MS - 1000);
    assert.equal(phase(p.a), PHASE.CONNECTED);
    const started = p.a.c.state().startedAt;
    p.browser.peers[0].ice("connected");
    await p.clock.advance(GRACE_MS * 2);
    assert.equal(phase(p.a), PHASE.CONNECTED, "it came back: the call goes on");
    assert.equal(p.a.c.state().startedAt, started, "and the clock of the call goes on too (it does not start from 0:00 again)");
    // dropped for good
    p.browser.peers[0].ice("disconnected");
    await p.clock.advance(GRACE_MS + 100);
    assert.equal(p.a.c.state().note, "lost");
    assert.equal(p.store.collection("calls")[0].data.state, "accepted");
  } finally {
    p.done();
  }
  // never connects
  p = pair();
  try {
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    await p.b.c.accept();
    await flush();
    await p.clock.advance(CONNECT_MS - 1000);
    assert.equal(phase(p.a), PHASE.CONNECTING);
    await p.clock.advance(1500);
    // both sides run the same timer and each ends by itself
    assert.ok(p.a.states.some((x) => x.note === "failed") && p.b.states.some((x) => x.note === "failed"), "both say the connection failed");
    assert.equal(p.store.collection("calls")[0].data.state, "accepted");
    const lines = p.store.writes.filter((x) => x.path.includes("/messages/"));
    assert.equal(lines.length, 0, "a failed connection writes no line (as the phone)");
  } finally {
    p.done();
  }
});

test("hung up while the call document is still on its way: it is ended as soon as it is there, no push, the callee never rings for long", async () => {
  const p = pair();
  try {
    p.b.c.listen();
    await flush();
    let release;
    p.store.hold = new Promise((r) => (release = r));
    const pending = p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    assert.equal(phase(p.a), PHASE.OUTGOING);
    p.a.c.hangup();
    assert.equal(phase(p.a), PHASE.ENDED);
    release();
    assert.deepEqual(await pending, { ok: false, why: "gone" });
    await flush();
    assert.equal(p.store.collection("calls")[0].data.state, "ended", "the document that arrived late is ended");
    assert.equal(p.requests.filter((x) => x.body.type === "call").length, 0, "the other phone is not woken");
    assert.equal(p.browser.peers[0].closed, true);
  } finally {
    p.done();
  }
});

test("the relay's keys: asked for once, kept for a long time, asked for again after six hours; free servers when the Worker has none or fails", async () => {
  const p = pair({ ice: { iceServers: [{ urls: "turn:own:3478", username: "u", credential: "c" }] } });
  try {
    const asks = () => p.requests.filter((x) => x.body.type === "ice").length;
    await p.a.c.refreshIce();
    await p.a.c.refreshIce();
    assert.equal(asks(), 1);
    p.clock.t += ICE_REFRESH_MS - 1000;
    await p.a.c.refreshIce();
    assert.equal(asks(), 1);
    p.clock.t += 2000;
    await p.a.c.refreshIce();
    assert.equal(asks(), 2);
    p.clock.t += ICE_FRESH_MS + 1000;
    p.requests.length = 0;
    p.browser.peers.length = 0;
    p.opts.ice = null; // the Worker cannot be reached now
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    assert.deepEqual(p.browser.peers[0].cfg.iceServers, FALLBACK_ICE, "old keys are not used");
  } finally {
    p.done();
  }
  const q = pair(); // the Worker answers 501: nothing is wrong, the free servers are used
  try {
    await q.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    assert.deepEqual(q.browser.peers[0].cfg.iceServers, FALLBACK_ICE);
    const none = makeCalls({ f: q.store.client(), uid: ME, name: "x", worker: "", rtc: q.browser.rtc, timers: q.clock, now: q.clock.now });
    await none.refreshIce();
    assert.equal(q.requests.filter((x) => x.body.type === "ice").length, 1, "no Worker address: nothing is asked");
  } finally {
    q.done();
  }
});

test("the list of calls to me is one query; a listener that fails is started again after 5 seconds; stopping lets everything go", async () => {
  const p = pair();
  try {
    p.store.failListen = true;
    p.a.c.listen();
    await flush();
    assert.equal(p.store.listeners(), 0);
    p.store.failListen = false;
    await p.clock.advance(5100);
    assert.equal(p.store.listeners(), 1, "listening again");
    p.b.c.listen();
    p.b.c.listen();
    await flush();
    assert.equal(p.store.listeners(), 2, "listening twice does not start a second listener");
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    assert.equal(p.store.listeners(), 2 + 3, "the call adds its document and the other side's addresses");
    p.a.c.stop();
    await flush();
    p.b.c.stop();
    await flush();
    assert.equal(p.store.listeners(), 0, "stop ends the call and every listener");
    assert.equal(p.store.collection("calls")[0].data.state, "ended");
    p.a.c.listen();
    assert.equal(p.store.listeners(), 0, "a stopped page does not listen again");
  } finally {
    p.done();
  }
});

test("a ringing call that nobody ends is dropped after 75 seconds and marked missed", async () => {
  const p = pair();
  try {
    p.store.docs.set("calls/x", { caller: BOB, callerName: "Բոբ", callee: ME, calleeName: "", kind: "audio", state: "ringing", ts: p.clock.t, offer: { type: "offer", sdp: "offer kinds=audio" } });
    p.a.c.listen();
    await flush();
    assert.equal(phase(p.a), PHASE.INCOMING);
    await p.clock.advance(INCOMING_MS - 1000);
    assert.equal(phase(p.a), PHASE.INCOMING);
    await p.clock.advance(1500);
    assert.ok(p.a.states.some((x) => x.phase === PHASE.ENDED));
    assert.equal(p.store.docs.get("calls/x").state, "missed");
    assert.equal(p.store.writes.filter((x) => x.path.includes("/messages/")).length, 0, "the callee writes no line");
  } finally {
    p.done();
  }
});

test("a call that ends for a reason of the browser (an error) writes no line; the second call right after an ended one is accepted", async () => {
  const p = pair();
  try {
    p.browser.rtc.Peer = p.browser.rtc.Peer;
    const bad = makeCalls({
      f: p.store.client(),
      uid: ME,
      name: "x",
      worker: "",
      rtc: { ...p.browser.rtc, Peer: function () { throw new Error("no peer"); } },
      timers: p.clock,
      now: p.clock.now,
    });
    assert.deepEqual(await bad.call({ peerUid: BOB, peerName: "Բոբ", video: false }), { ok: false, why: "error" });
    assert.equal(bad.state().note, "error");
    assert.equal(p.store.writes.length, 0, "no document, no line");
    // a call straight after an ended one does not wait for the note to go away
    const unsupported = makeCalls({ f: p.store.client(), uid: ME, name: "x", worker: "", rtc: null, timers: p.clock, now: p.clock.now });
    assert.deepEqual(await unsupported.call({ peerUid: BOB, peerName: "Բոբ", video: false }), { ok: false, why: "unsupported" });
    p.b.c.listen();
    await flush();
    await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    await flush();
    p.a.c.hangup();
    await flush();
    assert.equal(phase(p.a), PHASE.ENDED);
    const again = await p.a.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    assert.equal(again.ok, true, "an ended call's note does not block the next call");
  } finally {
    p.done();
  }
});

test("my name may be a function: a call made after I changed my name carries the new name, in the call and in the push", async () => {
  const p = pair();
  try {
    let cur = "Ստյոպա";
    const x = p.mk(ME, () => cur);
    p.b.c.listen();
    await flush();
    cur = "Ստյոպա Նոր";
    const r = await x.c.call({ peerUid: BOB, peerName: "Բոբ", video: false });
    assert.equal(r.ok, true);
    await flush();
    assert.equal(p.store.collection("calls")[0].data.callerName, "Ստյոպա Նոր");
    assert.equal(p.requests.filter((q) => q.body.type === "call")[0].body.title, "Ստյոպա Նոր");
    assert.equal(p.b.c.state().peerName, "Ստյոպա", "the callee's own chat name wins, as before (resolveName)");
  } finally {
    p.done();
  }
});
