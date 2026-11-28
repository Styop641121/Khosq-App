// Group calls on the web page (docs/gcalls.js): what is written must be what the phone writes and reads (GroupCallManager.kt) and what the Firestore
// rules allow; the pairs must find each other whoever comes first; people who vanish must not hold a place. Whole calls are run between several
// instances of the page's code, a pretend Firestore that all of them watch (it also checks every write against the rules of firestore.rules, written
// out again here), a pretend RTCPeerConnection for each person and a pretend clock.
// Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import { makeGroupCalls, makeLiveness, pointerOf, qualityFor, PHASE, MAX_PARTS, RING_MS, RING_SHOW_MS, BEAT_MS, LIVE_MS, FIRST_SIGHT_MS, ALONE_MS, CONNECT_MS, GRACE_MS, RETRY_MS, RETRIES, BATCH_MS, DEAD_MS, NOTE_MS, NOTE_LONG_MS, FIRST_DOC_MS } from "../docs/gcalls.js";
import { makeCalls } from "../docs/calls.js";

const A = "Aa00000000000000000000000001";
const B = "Bb00000000000000000000000002";
const C = "Cc00000000000000000000000003";
const D = "Dd00000000000000000000000004";
const E = "Ee00000000000000000000000005";
const F = "Ff00000000000000000000000006";
const G = "Gg00000000000000000000000007";
const NAMES = { [A]: "Արամ", [B]: "Բոբ", [C]: "Կարոլ", [D]: "Դավիթ", [E]: "Էվա", [F]: "Ֆիլ", [G]: "Գոռ" };
const CHAT = "grpYerevan01";
const WORKER = "https://worker.example/";
const flush = async () => {
  for (let i = 0; i < 8; i++) await new Promise((r) => setImmediate(r));
};

// ---------------------------------------------------------------------------------------------- a pretend Firestore with the rules checked
const DEL = { __del: true };
function makeStore() {
  const docs = new Map();
  const subs = new Set();
  const writes = [];
  const violations = [];
  let seq = 0;
  const clone = (x) => (x === undefined ? undefined : JSON.parse(JSON.stringify(x)));
  const parent = (p) => p.slice(0, p.lastIndexOf("/"));
  const idOf = (p) => p.slice(p.lastIndexOf("/") + 1);
  const listOf = (colPath, cs = []) => {
    const out = [];
    for (const [p, d] of docs) {
      if (parent(p) !== colPath) continue;
      const ok = cs.every((c) => {
        if (c.t !== "where") return true;
        const v = d[c.field];
        return c.op === "==" ? v === c.value : c.op === "array-contains" ? Array.isArray(v) && v.includes(c.value) : false;
      });
      if (ok) out.push({ id: idOf(p), data: clone(d), path: p });
    }
    return out;
  };
  const view = (s) => (s.target.kind === "doc" ? (docs.has(s.target.path) ? JSON.stringify(docs.get(s.target.path)) : "none") : JSON.stringify(listOf(s.target.path, s.target.cs || [])));
  const fire = (s, first) => {
    const v = view(s);
    if (!first && v === s.last) return;
    s.last = v;
    if (s.target.kind === "doc") {
      const d = docs.get(s.target.path);
      s.next({ id: idOf(s.target.path), exists: () => d !== undefined, data: () => clone(d) });
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
  const hear = () => {
    for (const s of [...subs]) if (!s.dead) fire(s, false);
  };
  const notify = () => queueMicrotask(hear);
  const chat = (id) => docs.get("chats/" + id) || { members: [] };
  const apply = (cur, data) => {
    const out = clone(cur) || {};
    for (const k of Object.keys(data)) {
      const parts = k.split(".");
      let o = out;
      for (let i = 0; i < parts.length - 1; i++) {
        if (typeof o[parts[i]] !== "object" || o[parts[i]] === null) o[parts[i]] = {};
        o = o[parts[i]];
      }
      const last = parts[parts.length - 1];
      if (data[k] && data[k].__del) delete o[last];
      else o[last] = clone(data[k]);
    }
    return out;
  };
  const affected = (a, b) => {
    // the top-level keys that differ (like request.resource.data.diff(resource.data).affectedKeys())
    const ks = new Set([...Object.keys(a || {}), ...Object.keys(b || {})]);
    return [...ks].filter((k) => JSON.stringify((a || {})[k]) !== JSON.stringify((b || {})[k]));
  };
  /** firestore.rules, written out again: what a person may write. Returns "" when it is allowed, else the reason. */
  const rules = (u, kind, path, before, after) => {
    const m = /^gcalls\/([^/]+)$/.exec(path);
    if (m) {
      if (kind === "create") {
        if (after.host !== u) return "host is not me";
        if (after.state !== "active") return "state";
        const c = chat(after.chat);
        if (!c.members.includes(u) || c.group !== true) return "not a member of a group";
        if (!after.parts || Object.keys(after.parts).join() !== u) return "parts must hold only me";
        return "";
      }
      if (!before) return "no document";
      if (!chat(before.chat).members.includes(u)) return "not a member";
      const keys = affected(after, before);
      if (!keys.every((k) => ["parts", "state", "dur", "con"].includes(k))) return "keys " + keys;
      if (keys.includes("parts")) {
        const pk = affected(after.parts, before.parts);
        if (!pk.every((k) => k === u)) return "someone else's entry " + pk;
        if (before.state !== "active") return "call is over";
        if (Object.keys(after.parts).length > 24) return "too many entries";
      }
      if (keys.includes("state") && after.state !== "ended") return "state must become ended";
      if (keys.includes("con") && before.con !== undefined) return "con is set once";
      return "";
    }
    const s = /^gcalls\/([^/]+)\/sig\/[^/]+$/.exec(path);
    if (s) {
      const g = docs.get("gcalls/" + s[1]);
      if (!g) return "no call";
      const mem = chat(g.chat).members;
      if (!mem.includes(u) || after.from !== u || !mem.includes(after.to) || after.to === u || g.state !== "active") return "sig";
      return "";
    }
    const c = /^chats\/([^/]+)$/.exec(path);
    if (c) {
      if (!before || !before.members.includes(u)) return "not a member";
      const keys = affected(after, before);
      return keys.every((k) => k === "gcall") ? "" : "chat keys " + keys;
    }
    const mm = /^chats\/([^/]+)\/messages\/[^/]+$/.exec(path);
    if (mm) return chat(mm[1]).members.includes(u) && after.from === u ? "" : "message";
    return "unknown path " + path;
  };
  const store = {
    docs,
    writes,
    violations,
    failWrites: null, // (path) => true to refuse
    silentDocs: false, // true: a document listener never hears anything (a bad network)
    ackGate: null, // a promise: a write is on the screen at once but is answered by the server only when this is settled
    seed(path, data) {
      docs.set(path, clone(data));
      notify();
    },
    client(u) {
      const put = (kind, path, next) => {
        if (store.failWrites && store.failWrites(path, u)) return Promise.reject(Object.assign(new Error("denied"), { code: "permission-denied" }));
        const before = docs.get(path);
        const after = kind === "create" ? clone(next) : apply(before, next);
        const why = rules(u, kind, path, before, kind === "create" ? next : after);
        writes.push({ by: u, kind, path, data: clone(next), why });
        if (why) {
          violations.push({ by: u, path, why });
          return Promise.reject(Object.assign(new Error("denied: " + why), { code: "permission-denied" }));
        }
        docs.set(path, after);
        notify();
        return store.ackGate ? store.ackGate.then(() => {}) : Promise.resolve();
      };
      const fsM = {
        collection: (db, ...p) => ({ kind: "col", path: p.join("/") }),
        doc: (a, ...p) => (a && a.kind === "col" ? { kind: "doc", path: a.path + "/auto" + ++seq, id: "auto" + seq } : { kind: "doc", path: p.join("/"), id: p[p.length - 1] }),
        where: (field, op, value) => ({ t: "where", field, op, value }),
        query: (col, ...cs) => ({ kind: "q", path: col.path, cs }),
        serverTimestamp: () => ({ sv: "ts" }),
        deleteField: () => DEL,
        setDoc: (ref, data) => put("create", ref.path, data),
        updateDoc: (ref, data) => {
          if (!docs.has(ref.path)) return Promise.reject(new Error("no document"));
          return put("update", ref.path, data);
        },
        async addDoc(col, data) {
          const path = col.path + "/c" + ++seq;
          await put("create", path, data);
          return { id: idOf(path) };
        },
        onSnapshot(target, next, err) {
          // a message to me only: the rules let a person read the talk that is addressed to them
          if (target.kind === "q" && /\/sig$/.test(target.path)) {
            const w = (target.cs || []).find((c) => c.t === "where" && c.field === "to");
            if (!w || w.op !== "==" || w.value !== u) {
              violations.push({ by: u, path: target.path, why: "a query on sig must ask for to == me" });
              queueMicrotask(() => err && err(new Error("denied")));
              return () => {};
            }
          }
          if (store.silentDocs && target.kind === "doc" && /^gcalls\//.test(target.path)) return () => {};
          const s = { target, next, err, last: "", seen: new Map(), dead: false };
          subs.add(s);
          queueMicrotask(() => {
            if (!s.dead) fire(s, true);
          });
          return () => {
            s.dead = true;
            subs.delete(s);
          };
        },
      };
      return { db: {}, fsM, auth: { currentUser: { getIdToken: async () => "token" } } };
    },
    collection: (colPath) => listOf(colPath),
    get: (path) => clone(docs.get(path)),
    listeners: () => [...subs].filter((s) => !s.dead).length,
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

// ---------------------------------------------------------------------------------------------- a pretend browser, one for each person
let peerSeq = 0;
function makeWorld(owner) {
  const peers = [];
  const w = { peers, gum: [], fail: null, failVideo: null, locals: [], made: 0 };
  const track = (kind) => ({ kind, enabled: true, stopped: false, stop() { this.stopped = true; } });
  const stream = (kinds) => {
    const s = { tracks: kinds.map(track), getTracks: () => s.tracks, getAudioTracks: () => s.tracks.filter((t) => t.kind === "audio"), getVideoTracks: () => s.tracks.filter((t) => t.kind === "video"), addTrack: (t) => s.tracks.push(t) };
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
      this.params = [];
      this.no = peerSeq++; // every peer of every person has addresses of its own
      w.made++;
      peers.push(this);
    }
    addTrack(t) { this.tracks.push(t); }
    getSenders() {
      return this.tracks.map((t) => ({ track: t, getParameters: () => ({ encodings: [] }), setParameters: async (p) => { this.params.push({ kind: t.kind, ...p.encodings[0] }); } }));
    }
    async createOffer() { return { type: "offer", sdp: "offer from=" + owner + " kinds=" + this.tracks.map((t) => t.kind).join(",") }; }
    async createAnswer() { return { type: "answer", sdp: "answer from=" + owner + " kinds=" + this.tracks.map((t) => t.kind).join(",") }; }
    async setLocalDescription(d) {
      this.localDescription = d;
      for (let i = 1; i <= 3; i++) queueMicrotask(() => this.onicecandidate && this.onicecandidate({ candidate: { candidate: "candidate:" + i + " 1 udp 2122260223 192.0.2." + (this.no * 10 + i) + " " + (50000 + this.no * 10 + i) + " typ host", sdpMid: "0", sdpMLineIndex: 0 } }));
      queueMicrotask(() => this.onicecandidate && this.onicecandidate({ candidate: null }));
    }
    async setRemoteDescription(d) {
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
    get from() { return (/from=(\S+)/.exec((this.remoteDescription || {}).sdp || "") || [])[1] || ""; } // whose description this peer has taken
    get to() { return (/from=(\S+)/.exec((this.localDescription || {}).sdp || "") || [])[1] || ""; }
  }
  w.rtc = {
    Peer,
    Stream: function () { return stream([]); },
    async getUserMedia(c) {
      w.gum.push(c);
      if (w.fail) throw Object.assign(new Error("x"), { name: w.fail });
      if (c.video && w.failVideo) throw Object.assign(new Error("x"), { name: w.failVideo });
      const s = stream(c.video ? ["audio", "video"] : ["audio"]);
      w.locals.push(s);
      return s;
    },
  };
  return w;
}

/** Everybody on one Firestore, one clock, each with a browser of their own and the chat list that the page watches. */
function crowd(uids, opts = {}) {
  const store = opts.store || makeStore();
  const clock = opts.clock || makeClock();
  const requests = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    requests.push({ url, headers: init.headers, body: JSON.parse(init.body) });
    return { ok: true, json: async () => ({}) };
  };
  const members = opts.members || uids;
  if (!store.docs.has("chats/" + CHAT)) store.seed("chats/" + CHAT, { group: true, title: "Երևանցիներ", members, admins: [uids[0]], names: NAMES, status: "active" });
  const people = {};
  const mk = (u, extra = {}) => {
    const states = [];
    const world = makeWorld(u);
    const banners = [];
    let busy = false;
    const f = store.client(u);
    const gc = makeGroupCalls({ f, uid: u, name: NAMES[u], worker: WORKER, rtc: world.rtc, timers: clock, now: clock.now, resolveName: (id, claimed, chat) => NAMES[id] || claimed, isBusy: () => busy, onChange: (s) => states.push(s), ...(opts.extra || {}), ...extra });
    // the page's chat list: my chats, live
    f.fsM.onSnapshot(f.fsM.query(f.fsM.collection(f.db, "chats"), f.fsM.where("members", "array-contains", u)), (snap) => gc.chatsChanged(snap.docs.map((d) => ({ id: d.id, ...d.data() }))), () => {});
    const p = { uid: u, gc, world, states, banners, f, busy: (v) => (busy = v), last: () => states[states.length - 1] || gc.state(), phase: () => gc.state().phase };
    return p;
  };
  for (const u of uids) people[u] = mk(u);
  return { store, clock, requests, people, mk, done: () => (globalThis.fetch = realFetch) };
}
const connectAll = (p) => {
  for (const x of p.world.peers) if (!x.closed && x.iceConnectionState !== "connected") x.ice("connected");
};
const openPeers = (p) => p.world.peers.filter((x) => !x.closed);
const gdoc = (c) => c.store.collection("gcalls")[0];
const sigs = (c, id) => c.store.collection("gcalls/" + id + "/sig").map((x) => x.data);

// ---------------------------------------------------------------------------------------------- tests
test("small helpers: the pointer in a chat, the quality for the number of people, the numbers", () => {
  assert.equal(MAX_PARTS, 6);
  assert.deepEqual([RING_MS, RING_SHOW_MS, BEAT_MS, LIVE_MS, FIRST_SIGHT_MS, ALONE_MS, CONNECT_MS, GRACE_MS, RETRY_MS, RETRIES, BATCH_MS, DEAD_MS, NOTE_MS, NOTE_LONG_MS], [45000, 90000, 25000, 75000, 240000, 20000, 30000, 8000, 3000, 2, 250, 180000, 1800, 5000]);
  assert.equal(pointerOf(null), null);
  assert.equal(pointerOf({}), null);
  assert.equal(pointerOf({ gcall: "x" }), null);
  assert.equal(pointerOf({ gcall: { id: "" } }), null);
  assert.equal(pointerOf({ gcall: { id: "a/b" } }), null, "an id with a slash is not a document of ours");
  assert.equal(pointerOf({ gcall: { id: "x".repeat(65) } }), null);
  assert.deepEqual(pointerOf({ gcall: { id: "c1", host: A, hostName: "Արամ", kind: "video", ts: 5 } }), { id: "c1", host: A, hostName: "Արամ", video: true, ts: 5 });
  assert.deepEqual(pointerOf({ gcall: { id: "c1", host: A, kind: "audio", ts: "no" } }), { id: "c1", host: A, hostName: "", video: false, ts: 0 });
  assert.deepEqual([1, 2, 3, 4, 5, 6].map((n) => qualityFor(n)), [{ maxBitrate: 0, scale: 1 }, { maxBitrate: 0, scale: 1 }, { maxBitrate: 500000, scale: 1 }, { maxBitrate: 300000, scale: 1.5 }, { maxBitrate: 200000, scale: 2 }, { maxBitrate: 200000, scale: 2 }]);
});

test("who is there: an entry counts while its sign of life changes (my clock), a wrong clock elsewhere does not matter, oldest first", () => {
  const clock = makeClock();
  const lv = makeLiveness(clock.now);
  const t = clock.t;
  const part = (sid, j, at, extra = {}) => ({ sid, j, at, name: "n", m: false, cam: false, ...extra });
  const parts = { [B]: part("sb", t - 1000, t - 500), [A]: part("sa", t - 5000, t - 400, { m: true, cam: true }), [C]: part("sc", t - 1000, t - 100) };
  const got = lv.live(parts);
  assert.deepEqual(got.map((p) => p.uid), [A, B, C], "oldest first by j, then by uid (B before C at the same j)");
  assert.deepEqual([got[0].m, got[0].cam, got[1].m, got[1].cam], [true, true, false, false]);
  // junk is left out
  assert.deepEqual(lv.live({ x: null, y: "s", z: { sid: "" }, w: { at: 1 } }), []);
  assert.deepEqual(lv.live(null), []);
  // a sign that does not change for LIVE_MS: not counted any more; when it changes, counted again
  const same = { [A]: part("sa", t, t) };
  assert.equal(lv.live(same).length, 1);
  clock.t += LIVE_MS - 1;
  assert.equal(lv.live(same).length, 1);
  clock.t += 1;
  assert.equal(lv.live(same).length, 0, "no sign of life for LIVE_MS");
  assert.equal(lv.live({ [A]: part("sa", t, t + 1) }).length, 1, "a changed sign brings the person back");
  // an entry seen for the first time with a very old sign (a person who left long ago) does not count, however long we look
  const lv2 = makeLiveness(clock.now);
  const old = { [B]: part("sb", t - 3600000, clock.t - FIRST_SIGHT_MS - 1) };
  assert.equal(lv2.live(old).length, 0);
  assert.equal(lv2.live({ [B]: part("sb", t - 3600000, clock.t - FIRST_SIGHT_MS + 5000) }).length, 1, "but its next sign of life (someone with a wrong clock is alive) brings it back");
  // a person whose own clock is far off, but who keeps changing the sign, counts after its first beat
  const lv3 = makeLiveness(clock.now);
  assert.equal(lv3.live({ [C]: part("sc", 1, clock.t + 3600000) }).length, 0, "first sight: an hour ahead is not believed");
  assert.equal(lv3.live({ [C]: part("sc", 1, clock.t + 3600000 + BEAT_MS) }).length, 1, "the sign changed: alive");
  // a new session of the same person is another entry
  const lv4 = makeLiveness(clock.now);
  assert.equal(lv4.live({ [A]: part("s1", 1, clock.t) })[0].sid, "s1");
  assert.equal(lv4.live({ [A]: part("s2", 2, clock.t) })[0].sid, "s2");
});

test("a whole group voice call among three: the exact documents, the push, the ring, the pairs, the addresses, the leaving, the line in the chat", async () => {
  const c = crowd([A, B, C]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    const cc = c.people[C];
    const r = await a.gc.start({ chatId: CHAT, title: "Երևանցիներ", video: false });
    assert.equal(r.ok, true);
    await flush();
    // the call document: exactly what the phone writes and reads
    const g = gdoc(c);
    assert.equal(g.id, r.id);
    assert.deepEqual(Object.keys(g.data).sort(), ["chat", "host", "hostName", "kind", "parts", "state", "title", "ts"]);
    assert.deepEqual({ chat: g.data.chat, host: g.data.host, hostName: g.data.hostName, kind: g.data.kind, state: g.data.state, title: g.data.title, ts: g.data.ts }, { chat: CHAT, host: A, hostName: "Արամ", kind: "audio", state: "active", title: "Երևանցիներ", ts: c.clock.t });
    assert.deepEqual(Object.keys(g.data.parts), [A]);
    const me = g.data.parts[A];
    assert.deepEqual(Object.keys(me).sort(), ["at", "cam", "j", "m", "name", "sid"]);
    assert.deepEqual({ j: me.j, at: me.at, name: me.name, m: me.m, cam: me.cam }, { j: c.clock.t, at: c.clock.t, name: "Արամ", m: false, cam: false });
    assert.ok(me.sid.length >= 8);
    // the sign in the chat, and the push to the others' phones (the chat id of the group, the sign of the kind)
    assert.deepEqual(c.store.get("chats/" + CHAT).gcall, { id: r.id, host: A, hostName: "Արամ", kind: "audio", ts: c.clock.t });
    assert.deepEqual(c.requests.map((x) => x.body), [{ chatId: CHAT, type: "call", title: "Արամ", body: "\u{1F4DE}" }]);
    assert.equal(c.requests[0].headers.Authorization, "Bearer token");
    assert.equal(c.requests[0].url, WORKER);
    // the others ring, the one who called does not
    assert.equal(b.phase(), PHASE.RINGING);
    assert.equal(cc.phase(), PHASE.RINGING);
    assert.equal(a.phase(), PHASE.ACTIVE);
    const ring = b.gc.state();
    assert.deepEqual([ring.callId, ring.chatId, ring.title, ring.video, ring.hostUid, ring.hostName], [r.id, CHAT, "Երևանցիներ", false, A, "Արամ"]);
    assert.deepEqual(ring.ringIn, [{ uid: A, name: "Արամ" }]);
    assert.equal(b.world.gum.length, 0, "nothing is asked of the microphone before the person joins");
    // Bob joins (later than Aram: Bob offers)
    c.clock.t += 1000;
    assert.deepEqual(await b.gc.join(), { ok: true, id: r.id });
    await flush();
    assert.deepEqual(b.world.gum, [{ audio: true, video: false }]);
    assert.equal(b.phase(), PHASE.ACTIVE);
    const parts = c.store.get("gcalls/" + r.id).parts;
    assert.deepEqual(Object.keys(parts).sort(), [A, B]);
    assert.equal(parts[B].j, parts[A].j + 1000);
    assert.equal(parts[B].name, "Բոբ");
    // one pair: Bob made one offer, Aram one answer, in the messages addressed to the other
    const all = sigs(c, r.id);
    const offers = all.filter((x) => x.k === "offer");
    const answers = all.filter((x) => x.k === "answer");
    assert.equal(offers.length, 1);
    assert.equal(answers.length, 1);
    assert.deepEqual([offers[0].from, offers[0].to, offers[0].fsid, offers[0].tsid], [B, A, parts[B].sid, parts[A].sid]);
    assert.deepEqual([answers[0].from, answers[0].to, answers[0].fsid, answers[0].tsid, answers[0].lid], [A, B, parts[A].sid, parts[B].sid, offers[0].lid]);
    assert.deepEqual(Object.keys(offers[0]).sort(), ["at", "from", "fsid", "k", "lid", "sdp", "to", "tsid"]);
    assert.equal(offers[0].sdp, "offer from=" + B + " kinds=audio");
    // the addresses: batched in a few documents, each side's own, none refused
    const cands = all.filter((x) => x.k === "cand");
    assert.ok(cands.length >= 2 && cands.length <= 4, "the addresses go in few documents, not one each: " + cands.length);
    assert.deepEqual(Object.keys(cands[0]).sort(), ["at", "c", "from", "fsid", "k", "lid", "to", "tsid"]);
    assert.deepEqual(Object.keys(cands[0].c[0]).sort(), ["i", "m", "s"]);
    assert.equal(cands.flatMap((x) => (x.from === B ? x.c : [])).length, 3, "all three of Bob's addresses, in one or two documents");
    assert.equal(cands.flatMap((x) => (x.from === A ? x.c : [])).length, 3);
    const pa = openPeers(a);
    const pb = openPeers(b);
    assert.equal(pa.length, 1);
    assert.equal(pb.length, 1);
    assert.equal(pa[0].from, B, "Aram's pair took Bob's offer");
    assert.equal(pb[0].from, A, "and Bob's took Aram's answer");
    assert.equal(pa[0].added.length, 3);
    assert.equal(pb[0].added.length, 3);
    const own = (p) => [1, 2, 3].map((i) => "192.0.2." + (p.no * 10 + i));
    assert.deepEqual(pa[0].added.map((x) => x.candidate.split(" ")[4]).sort(), own(pb[0]).sort(), "Aram got Bob's addresses");
    assert.deepEqual(pb[0].added.map((x) => x.candidate.split(" ")[4]).sort(), own(pa[0]).sort(), "and Bob got Aram's, none of his own");
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
    // not connected yet: the screen says so; then both are connected
    assert.equal(a.gc.state().startedAt, 0);
    connectAll(a);
    connectAll(b);
    assert.ok(a.gc.state().startedAt > 0);
    assert.deepEqual(a.gc.state().people.map((p) => [p.uid, p.name, p.state]), [[B, "Բոբ", "connected"]]);
    assert.deepEqual(b.gc.state().people.map((p) => [p.uid, p.name, p.state]), [[A, "Արամ", "connected"]]);
    assert.equal(a.gc.state().count, 2);
    // the length of the call starts when two are together: written once, by the first one there
    await flush();
    const con = c.store.get("gcalls/" + r.id).con;
    assert.equal(con, c.clock.t);
    assert.equal(c.store.writes.filter((w) => w.kind === "update" && "con" in w.data).length, 1);
    assert.equal(c.store.writes.find((w) => "con" in w.data).by, A);
    // Carol joins: she is the latest, so she makes an offer to both; there are three pairs in all
    c.clock.t += 2000;
    assert.equal((await cc.gc.join()).ok, true);
    await flush();
    const offers2 = sigs(c, r.id).filter((x) => x.k === "offer");
    assert.deepEqual(offers2.map((x) => x.from + ">" + x.to).sort(), [B + ">" + A, C + ">" + A, C + ">" + B].sort());
    assert.equal(openPeers(a).length, 2);
    assert.equal(openPeers(b).length, 2);
    assert.equal(openPeers(cc).length, 2);
    connectAll(a);
    connectAll(b);
    connectAll(cc);
    for (const p of [a, b, cc]) {
      assert.equal(p.gc.state().count, 3);
      assert.equal(p.gc.state().people.length, 2);
      assert.ok(p.gc.state().people.every((x) => x.state === "connected" && x.stream));
    }
    assert.equal(c.store.writes.filter((w) => w.kind === "update" && "con" in w.data).length, 1, "the moment is not written again");
    // with three people the picture is made lighter (a mesh sends it twice): only video is concerned, so no sender here is touched in a voice call
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
    // Carol leaves: her entry goes, the others let her pair go, the call goes on
    cc.gc.leave();
    await flush();
    assert.equal(cc.phase(), PHASE.ENDED);
    assert.deepEqual(Object.keys(c.store.get("gcalls/" + r.id).parts).sort(), [A, B]);
    assert.equal(c.store.get("gcalls/" + r.id).state, "active");
    assert.equal(openPeers(a).length, 1);
    assert.equal(openPeers(b).length, 1);
    assert.deepEqual(a.gc.state().people.map((p) => p.uid), [B]);
    assert.ok(cc.world.peers.every((x) => x.closed), "her pairs are closed");
    assert.ok(cc.world.locals.every((s) => s.tracks.every((t) => t.stopped)), "her microphone is let go");
    assert.ok(c.store.get("chats/" + CHAT).gcall, "the sign stays while others are in");
    // Bob leaves: Aram is alone; after ALONE_MS she goes too, and the last one out closes the call
    const tLeft = c.clock.t;
    b.gc.leave();
    await flush();
    assert.equal(a.phase(), PHASE.ACTIVE);
    await c.clock.advance(ALONE_MS + 100);
    assert.equal(a.phase(), PHASE.ENDED);
    const end = c.store.get("gcalls/" + r.id);
    assert.equal(end.state, "ended");
    assert.deepEqual(end.parts, {});
    assert.equal(end.dur, Math.floor((tLeft + ALONE_MS - con) / 1000), "the length is counted from the moment two were together");
    assert.equal(c.store.get("chats/" + CHAT).gcall, undefined, "the sign is taken out of the chat");
    const lines = c.store.collection("chats/" + CHAT + "/messages").map((x) => x.data);
    assert.equal(lines.length, 1);
    assert.deepEqual({ from: lines[0].from, kind: lines[0].kind, code: lines[0].code, vid: lines[0].vid, text: lines[0].text }, { from: A, kind: "call", code: "call_done", vid: false, text: "" });
    assert.equal(lines[0].dur, end.dur);
    assert.ok(end.dur > 0);
    assert.deepEqual(lines[0].ts, { sv: "ts" });
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
    // nothing is left running for the call that is over
    await c.clock.advance(NOTE_MS + 500);
    for (const p of [a, b, cc]) assert.equal(p.phase(), PHASE.IDLE);
  } finally {
    c.done();
  }
});

test("a video call: the picture is asked for, the camera can be turned off and on (written into my entry), a person without a camera joins with the microphone", async () => {
  const c = crowd([A, B, C]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    const cc = c.people[C];
    assert.equal((await a.gc.start({ chatId: CHAT, title: "Երևանցիներ", video: true })).ok, true);
    await flush();
    assert.equal(c.store.get("gcalls/" + gdoc(c).id).kind, "video");
    assert.equal(c.requests[0].body.body, "\u{1F4F9}");
    assert.deepEqual(a.world.gum, [{ audio: true, video: { width: { ideal: 480 }, height: { ideal: 360 }, frameRate: { ideal: 20 } } }], "a smaller picture than a one-to-one call: up to five are sent");
    assert.equal(c.store.get("gcalls/" + gdoc(c).id).parts[A].cam, true);
    assert.equal(b.gc.state().video, true);
    c.clock.t += 500;
    assert.equal((await b.gc.join()).ok, true);
    // Carol has no camera: the microphone alone, and the camera button is not offered
    cc.world.failVideo = "NotFoundError";
    c.clock.t += 500;
    assert.equal((await cc.gc.join()).ok, true);
    await flush();
    assert.equal(cc.gc.state().noCamera, true);
    assert.equal(cc.gc.state().cameraOn, false);
    assert.deepEqual(cc.world.gum.map((x) => !!x.video), [true, false]);
    assert.equal(c.store.get("gcalls/" + gdoc(c).id).parts[C].cam, false);
    cc.gc.toggleCamera();
    assert.equal(cc.gc.state().cameraOn, false, "nothing to turn on");
    // Aram turns her camera off and on, her microphone off and on: tracks and entry follow
    a.gc.toggleCamera();
    await flush();
    assert.equal(a.gc.state().cameraOn, false);
    assert.equal(a.world.locals[0].getVideoTracks()[0].enabled, false);
    assert.equal(c.store.get("gcalls/" + gdoc(c).id).parts[A].cam, false);
    assert.deepEqual(b.gc.state().people.find((p) => p.uid === A).cam, false, "the others see that the camera is off");
    a.gc.toggleCamera();
    await flush();
    assert.equal(c.store.get("gcalls/" + gdoc(c).id).parts[A].cam, true);
    a.gc.toggleMute();
    await flush();
    assert.equal(a.gc.state().muted, true);
    assert.equal(a.world.locals[0].getAudioTracks()[0].enabled, false);
    assert.equal(c.store.get("gcalls/" + gdoc(c).id).parts[A].m, true);
    assert.equal(b.gc.state().people.find((p) => p.uid === A).muted, true);
    a.gc.toggleMute();
    await flush();
    assert.equal(c.store.get("gcalls/" + gdoc(c).id).parts[A].m, false);
    // the video that is sent is made lighter for three people (the senders of the pairs are told)
    connectAll(a);
    connectAll(b);
    connectAll(cc);
    await flush();
    const lastParams = (p) => p.world.peers.flatMap((x) => x.params).filter((x) => x.kind === "video").slice(-1)[0];
    assert.deepEqual(lastParams(a), { kind: "video", maxBitrate: 500000, scaleResolutionDownBy: 1 });
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("nobody comes: the one who started gives up after RING_MS, the call is closed, the sign goes, a 'missed' line is written", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    assert.equal((await a.gc.start({ chatId: CHAT, title: "Երևանցիներ", video: false })).ok, true);
    await flush();
    assert.equal(b.phase(), PHASE.RINGING);
    await c.clock.advance(RING_MS - 1000);
    assert.equal(a.phase(), PHASE.ACTIVE);
    await c.clock.advance(1200);
    assert.equal(a.phase(), PHASE.ENDED);
    assert.equal(a.gc.state().note, "noanswer");
    const g = gdoc(c).data;
    assert.equal(g.state, "ended");
    assert.deepEqual(g.parts, {});
    assert.equal(c.store.get("chats/" + CHAT).gcall, undefined);
    const lines = c.store.collection("chats/" + CHAT + "/messages").map((x) => x.data);
    assert.deepEqual(lines.map((x) => [x.from, x.kind, x.code, x.dur]), [[A, "call", "call_missed", 0]]);
    // the ring stops for Bob, with no note and no line of his own
    assert.equal(b.phase(), PHASE.IDLE);
    assert.equal(c.store.collection("chats/" + CHAT + "/messages").length, 1);
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("a hang-up before anybody came is a cancelled call; a refusal writes nothing and the call is not rung for again, but the chat still offers it", async () => {
  const c = crowd([A, B, C]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    const cc = c.people[C];
    await a.gc.start({ chatId: CHAT, title: "Երևանցիներ", video: false });
    await flush();
    const before = c.store.writes.length;
    b.gc.decline();
    assert.equal(b.phase(), PHASE.IDLE);
    assert.equal(c.store.writes.length, before, "a refusal writes nothing");
    // the chat list changes again (a message): Bob is not rung for the same call twice
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), lastText: "hi" });
    await flush();
    assert.equal(b.phase(), PHASE.IDLE);
    // but the chat shows that a call goes on, and Bob can still join it
    const seen = [];
    b.gc.watchBanner(CHAT, (x) => seen.push(x));
    await flush();
    assert.equal(seen[seen.length - 1].callId, gdoc(c).id);
    assert.equal(seen[seen.length - 1].count, 1);
    assert.deepEqual(seen[seen.length - 1].people, [{ uid: A, name: "Արամ" }]);
    assert.equal(seen[seen.length - 1].mine, false);
    assert.equal((await b.gc.join(CHAT)).ok, true);
    await flush();
    assert.equal(b.phase(), PHASE.ACTIVE);
    assert.equal(seen[seen.length - 1].mine, true, "once I am in, the banner says it is mine (the page hides it)");
    // Carol rings, and rings no more once the call is over
    assert.equal(cc.phase(), PHASE.RINGING);
    b.gc.leave();
    await flush();
    a.gc.leave();
    await flush();
    assert.equal(cc.phase(), PHASE.IDLE, "the call is over: the ring stops");
    assert.deepEqual(c.store.collection("chats/" + CHAT + "/messages").map((x) => x.data.code), ["call_done"]);
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("a call that came long ago is not rung for (the banner offers it); a call whose document is gone, or over, stops the ring", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const b = c.people[B];
    c.store.seed("gcalls/old1", { chat: CHAT, host: A, hostName: "Արամ", title: "Երևանցիներ", kind: "audio", state: "active", ts: c.clock.t - RING_SHOW_MS - 5000, parts: { [A]: { sid: "sa", j: c.clock.t - 100000, at: c.clock.t, name: "Արամ", m: false, cam: false } } });
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "old1", host: A, hostName: "Արամ", kind: "audio", ts: c.clock.t - RING_SHOW_MS - 5000 } });
    await flush();
    assert.equal(b.phase(), PHASE.IDLE, "too old to ring");
    const seen = [];
    b.gc.watchBanner(CHAT, (x) => seen.push(x));
    await flush();
    assert.equal(seen[seen.length - 1].callId, "old1");
    // a fresh one rings, and the document turning "ended" stops the ring
    c.store.seed("gcalls/new1", { chat: CHAT, host: A, hostName: "Արամ", title: "Երևանցիներ", kind: "video", state: "active", ts: c.clock.t, parts: { [A]: { sid: "sa", j: c.clock.t, at: c.clock.t, name: "Արամ", m: false, cam: true } } });
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "new1", host: A, hostName: "Արամ", kind: "video", ts: c.clock.t } });
    await flush();
    assert.equal(b.phase(), PHASE.RINGING);
    assert.equal(b.gc.state().video, true);
    c.store.seed("gcalls/new1", { ...c.store.get("gcalls/new1"), state: "ended" });
    await flush();
    assert.equal(b.phase(), PHASE.IDLE);
    // the sign leaves the chat while ringing: the ring stops
    c.store.seed("gcalls/new2", { chat: CHAT, host: A, hostName: "Արամ", title: "x", kind: "audio", state: "active", ts: c.clock.t, parts: { [A]: { sid: "sa", j: c.clock.t, at: c.clock.t, name: "Արամ", m: false, cam: false } } });
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "new2", host: A, hostName: "Արամ", kind: "audio", ts: c.clock.t } });
    await flush();
    assert.equal(b.phase(), PHASE.RINGING);
    const g = { ...c.store.get("chats/" + CHAT) };
    delete g.gcall;
    c.store.docs.set("chats/" + CHAT, g);
    c.store.seed("chats/other", { group: true, members: [A, B], title: "o" }); // anything that makes the list change
    await flush();
    assert.equal(b.phase(), PHASE.IDLE);
    // a call nobody is in, and the ring ends by itself after its time
    c.store.seed("gcalls/new3", { chat: CHAT, host: A, hostName: "Արամ", title: "x", kind: "audio", state: "active", ts: c.clock.t, parts: { [A]: { sid: "sa", j: c.clock.t, at: c.clock.t, name: "Արամ", m: false, cam: false } } });
    c.store.seed("chats/" + CHAT, { ...g, gcall: { id: "new3", host: A, hostName: "Արամ", kind: "audio", ts: c.clock.t } });
    await flush();
    assert.equal(b.phase(), PHASE.RINGING);
    await c.clock.advance(RING_SHOW_MS + 1000);
    assert.equal(b.phase(), PHASE.IDLE, "the ring does not go on for ever");
  } finally {
    c.done();
  }
});

test("busy: while a one-to-one call goes on the page does not ring, does not start, does not join; a one-to-one call is refused while a group call goes on", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    b.busy(true);
    await a.gc.start({ chatId: CHAT, title: "Երևանցիներ", video: false });
    await flush();
    assert.equal(b.phase(), PHASE.IDLE, "busy: no ring");
    assert.deepEqual(await b.gc.start({ chatId: CHAT, title: "x", video: false }), { ok: false, why: "busy" });
    assert.deepEqual(await b.gc.join(CHAT), { ok: false, why: "busy" });
    assert.equal(b.world.gum.length, 0);
    // a group call is going on at Aram's: a one-to-one call that comes in is refused (the real makeCalls, the same busy hook)
    const calls = [];
    const docs = new Map([["calls/k1", { caller: B, callerName: "Բոբ", callee: A, calleeName: "Արամ", kind: "audio", state: "ringing", ts: c.clock.t, offer: { type: "offer", sdp: "o" } }]]);
    const fsM = {
      collection: () => ({ kind: "col", path: "calls" }),
      doc: (a2, ...p) => ({ kind: "doc", path: p.join("/"), id: p[p.length - 1] }),
      where: (f1, op, v) => ({ f1, op, v }),
      query: (col, ...cs) => ({ kind: "q", cs }),
      onSnapshot: (t, next) => { queueMicrotask(() => next({ docs: [{ id: "k1", data: () => docs.get("calls/k1") }] })); return () => {}; },
      updateDoc: async (ref, data) => { calls.push({ path: ref.path, data }); },
    };
    const one = makeCalls({ f: { db: {}, fsM }, uid: A, name: "Արամ", rtc: makeWorld(A).rtc, timers: c.clock, now: c.clock.now, isBusy: () => a.gc.state().phase === PHASE.ACTIVE });
    one.listen();
    await flush();
    assert.deepEqual(calls, [{ path: "calls/k1", data: { state: "declined" } }], "refused as busy");
    assert.equal(one.state().phase, "idle");
    one.stop();
  } finally {
    c.done();
  }
});

test("a person who is refused the microphone: nothing is written for a join, the reason is told; no camera and microphone at all: the same", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    a.world.fail = "NotAllowedError";
    const r = await a.gc.start({ chatId: CHAT, title: "x", video: false });
    assert.deepEqual(r, { ok: false, why: "denied" });
    assert.equal(a.gc.state().note, "denied");
    assert.equal(c.store.collection("gcalls").length, 0, "no call was made");
    assert.equal(c.store.get("chats/" + CHAT).gcall, undefined);
    assert.equal(c.requests.length, 0, "and nobody was rung");
    await c.clock.advance(NOTE_LONG_MS + 100);
    assert.equal(a.phase(), PHASE.IDLE);
    a.world.fail = null;
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    b.world.fail = "NotFoundError";
    const before = c.store.writes.length;
    assert.deepEqual(await b.gc.join(), { ok: false, why: "noMedia" });
    assert.equal(c.store.writes.length, before, "joining writes nothing when there is no microphone");
    assert.equal(b.gc.state().note, "noMedia");
    b.world.fail = "NotAllowedError";
    await c.clock.advance(NOTE_LONG_MS + 100);
    assert.equal(b.phase(), PHASE.IDLE);
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("two people come at the same moment: exactly one offer for the pair, by the same rule on both sides; the one who was there waits", async () => {
  const c = crowd([A, B, C]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    const cc = c.people[C];
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    // Bob and Carol join in the same millisecond, neither sees the other's entry when it makes up its mind
    const id = gdoc(c).id;
    const jb = b.gc.join();
    const jc = cc.gc.join();
    await Promise.all([jb, jc]);
    await flush();
    const offers = sigs(c, id).filter((x) => x.k === "offer").map((x) => x.from + ">" + x.to).sort();
    // the later (j, uid) offers: Bob and Carol have the same j, so Carol (the larger uid) offers to Bob
    assert.deepEqual(offers, [B + ">" + A, C + ">" + A, C + ">" + B].sort());
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
    for (const p of [a, b, cc]) assert.equal(openPeers(p).length, 2, "two pairs each");
    connectAll(a);
    connectAll(b);
    connectAll(cc);
    for (const p of [a, b, cc]) assert.ok(p.gc.state().people.every((x) => x.state === "connected"));
  } finally {
    c.done();
  }
});

test("an offer that comes before the call document shows the person is kept and answered when the document arrives; addresses that come before their offer are kept too", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    const id = gdoc(c).id;
    assert.equal((await b.gc.join()).ok, true);
    await flush();
    // Bob offered; now a new person (C, not in the document yet) offers to Aram with her addresses first
    const sidA = c.store.get("gcalls/" + id).parts[A].sid;
    const cl = c.store.client(C);
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), members: [A, B, C] });
    await flush();
    await cl.fsM.addDoc(cl.fsM.collection(cl.db, "gcalls", id, "sig"), { from: C, to: A, fsid: "sc1", tsid: sidA, lid: "L1", k: "cand", c: [{ m: "0", i: 0, s: "candidate:9 1 udp 1 192.0.2.99 5000 typ host" }], at: c.clock.t });
    await cl.fsM.addDoc(cl.fsM.collection(cl.db, "gcalls", id, "sig"), { from: C, to: A, fsid: "sc1", tsid: sidA, lid: "L1", k: "offer", sdp: "offer from=" + C + " kinds=audio", at: c.clock.t });
    await flush();
    assert.equal(openPeers(a).length, 1, "no pair for a person who is not in the call document");
    // her entry arrives
    c.clock.t += 10;
    await cl.fsM.updateDoc(cl.fsM.doc(cl.db, "gcalls", id), { ["parts." + C]: { sid: "sc1", j: c.clock.t, at: c.clock.t, name: "Կարոլ", m: false, cam: false } });
    await flush();
    assert.equal(openPeers(a).length, 2, "now the offer is taken");
    const pc = openPeers(a).find((x) => x.from === C);
    assert.ok(pc);
    assert.deepEqual(pc.added.map((x) => x.candidate), ["candidate:9 1 udp 1 192.0.2.99 5000 typ host"], "the address that came first is added after the description");
    assert.ok(sigs(c, id).some((x) => x.k === "answer" && x.from === A && x.to === C && x.lid === "L1" && x.tsid === "sc1"));
    // an offer for a session that is not mine (an older join of mine) is dropped
    const before = openPeers(a).length;
    await cl.fsM.addDoc(cl.fsM.collection(cl.db, "gcalls", id, "sig"), { from: C, to: A, fsid: "sc1", tsid: "an-old-session", lid: "L2", k: "offer", sdp: "offer from=" + C + " kinds=audio", at: c.clock.t });
    await flush();
    assert.equal(openPeers(a).length, before);
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("a pair that does not connect: lost, tried again by the one who offers (RETRIES times), then left lost; one that drops comes back within the grace time or is lost", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    c.clock.t += 100;
    await b.gc.join();
    await flush();
    const id = gdoc(c).id;
    // a connection that comes back within the grace time
    connectAll(a);
    connectAll(b);
    openPeers(b)[0].ice("disconnected");
    await c.clock.advance(GRACE_MS - 500);
    openPeers(b)[0].ice("connected");
    await c.clock.advance(GRACE_MS * 2);
    assert.equal(b.gc.state().people[0].state, "connected");
    // dropped for good: lost, and Bob (who offers) tries again with a new try number
    const first = sigs(c, id).filter((x) => x.k === "offer");
    openPeers(b)[0].ice("failed");
    assert.equal(b.gc.state().people[0].state, "lost");
    assert.equal(b.gc.state().people[0].stream, null);
    await c.clock.advance(RETRY_MS + 200);
    const second = sigs(c, id).filter((x) => x.k === "offer");
    assert.equal(second.length, first.length + 1);
    assert.notEqual(second[second.length - 1].lid, first[0].lid);
    assert.equal(b.gc.state().people[0].state, "connecting");
    assert.equal(openPeers(a).filter((x) => x.from === B).length, 1, "Aram let the old pair go and took the new offer");
    connectAll(a);
    connectAll(b);
    assert.equal(a.gc.state().people[0].state, "connected");
    // tries run out: never connects, three tries in all, then it stays lost (the call document still shows the person)
    openPeers(b)[0].ice("failed");
    await c.clock.advance(RETRY_MS + 100);
    openPeers(b)[0].ice("failed");
    await c.clock.advance(RETRY_MS + 100);
    const n = sigs(c, id).filter((x) => x.k === "offer").length;
    assert.equal(n, 3, "the first, and two more");
    await c.clock.advance(RETRY_MS * 3);
    assert.equal(sigs(c, id).filter((x) => x.k === "offer").length, 3, "no fourth try");
    assert.equal(b.gc.state().people[0].state, "lost");
    // a pair that never gets going is lost after CONNECT_MS
    const d2 = crowd([A, B]);
    try {
      await flush();
      const a2 = d2.people[A];
      const b2 = d2.people[B];
      await a2.gc.start({ chatId: CHAT, title: "x", video: false });
      await flush();
      d2.clock.t += 100;
      await b2.gc.join();
      await flush();
      await d2.clock.advance(CONNECT_MS + RETRY_MS + 200);
      assert.equal(b2.gc.state().people[0].state === "lost" || b2.gc.state().people[0].state === "connecting", true);
      assert.equal(sigs(d2, gdoc(d2).id).filter((x) => x.k === "offer").length >= 2, true, "a new try was made");
    } finally {
      d2.done();
    }
  } finally {
    c.done();
  }
});

test("a person who closes the page without leaving is not counted after LIVE_MS and their pair is let go; the beat keeps the others in", async () => {
  const c = crowd([A, B], { members: [A, B, C] });
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    const dead = { setTimeout: () => 0, clearTimeout: () => {} }; // Carol's page: nothing she has set up ever fires (it is gone)
    const cc = c.mk(C, { timers: dead });
    await flush();
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    c.clock.t += 100;
    await b.gc.join();
    c.clock.t += 100;
    await cc.gc.join();
    await flush();
    connectAll(a);
    connectAll(b);
    connectAll(cc);
    assert.equal(a.gc.state().count, 3);
    await c.clock.advance(BEAT_MS * 2 + 1000);
    assert.equal(a.gc.state().count, 3, "not yet: LIVE_MS has not passed");
    const beats = c.store.writes.filter((w) => w.kind === "update" && Object.keys(w.data).some((k) => k.endsWith(".at")));
    assert.ok(beats.length >= 4, "the beat is written");
    assert.deepEqual([...new Set(beats.map((w) => w.by))].sort(), [A, B], "by those who are there");
    assert.ok(beats.every((w) => Object.keys(w.data).length === 1 && Object.keys(w.data)[0] === "parts." + w.by + ".at"), "only into their own entries");
    await c.clock.advance(LIVE_MS);
    assert.equal(a.gc.state().count, 2, "Carol is not counted any more");
    assert.deepEqual(a.gc.state().people.map((p) => p.uid), [B]);
    assert.equal(openPeers(a).length, 1, "her pair is let go");
    assert.equal(a.phase(), PHASE.ACTIVE);
    // she comes back (her beat changes): counted again, a new pair
    const f = c.store.client(C);
    await f.fsM.updateDoc(f.fsM.doc(f.db, "gcalls", gdoc(c).id), { ["parts." + C + ".at"]: c.clock.t });
    await flush();
    assert.equal(a.gc.state().count, 3);
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("a seventh person is turned away before the microphone is asked for; when seven end up in at once, the latest one leaves by itself", async () => {
  const six = [A, B, C, D, E, F];
  const c = crowd([...six, G]);
  try {
    await flush();
    await c.people[A].gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    for (const u of [B, C, D, E]) {
      c.clock.t += 10;
      assert.equal((await c.people[u].gc.join()).ok, true, u);
    }
    // F and G both see five in the call and both come in; the call document cannot refuse the second
    c.clock.t += 10;
    await Promise.all([c.people[F].gc.join(), c.people[G].gc.join()]);
    await flush();
    assert.equal(c.people[G].phase(), PHASE.ENDED, "the later one by (j, uid) leaves by itself");
    assert.equal(c.people[G].gc.state().note, "full");
    assert.equal(c.people[F].phase(), PHASE.ACTIVE);
    assert.deepEqual(Object.keys(c.store.get("gcalls/" + gdoc(c).id).parts).sort(), six, "its entry is gone");
    assert.equal(openPeers(c.people[G]).length, 0, "it made no pairs");
    for (const u of six) {
      assert.equal(c.people[u].gc.state().count, 6);
      assert.equal(openPeers(c.people[u]).length, 5, "every one of the six has five pairs");
    }
    // now the call is full: a seventh is turned away before anything is asked of the microphone
    c.people[G].world.gum.length = 0;
    await c.clock.advance(NOTE_LONG_MS + 100);
    const r = await c.people[G].gc.join(CHAT);
    assert.deepEqual(r, { ok: false, why: "full" });
    assert.equal(c.people[G].world.gum.length, 0);
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("the same account joins on a second device: the first one steps out without touching the entry; leaving the page ends my part", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    c.clock.t += 50;
    await b.gc.join();
    await flush();
    const id = gdoc(c).id;
    // Bob's other device (a second page of the same account) takes the call
    const oldSid = c.store.get("gcalls/" + id).parts[B].sid;
    const other = c.mk(B);
    await flush();
    c.clock.t += 50;
    assert.equal((await other.gc.join(CHAT)).ok, true);
    await flush();
    assert.equal(b.phase(), PHASE.ENDED);
    assert.equal(b.gc.state().note, "elsewhere");
    assert.notEqual(c.store.get("gcalls/" + id).parts[B].sid, oldSid, "the entry is the second device's");
    assert.equal(other.phase(), PHASE.ACTIVE);
    assert.equal(Object.keys(c.store.get("gcalls/" + id).parts).length, 2, "the entry was not deleted by the first one");
    assert.equal(openPeers(a).filter((x) => x.from === B).length, 1, "Aram has one pair with Bob again, with the new device");
    // the page of the second device goes away
    other.gc.stop();
    await flush();
    assert.deepEqual(Object.keys(c.store.get("gcalls/" + id).parts), [A]);
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("the banner of the open chat: the call, the people in it, full, gone; a call nobody is in is closed by whoever looks after DEAD_MS", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    const seen = [];
    const stop = b.gc.watchBanner(CHAT, (x) => seen.push(x));
    await flush();
    assert.equal(seen.length, 0, "no call: nothing to tell");
    await a.gc.start({ chatId: CHAT, title: "Երևանցիներ", video: true });
    await flush();
    assert.deepEqual(seen[seen.length - 1], { callId: gdoc(c).id, chatId: CHAT, video: true, hostName: "Արամ", people: [{ uid: A, name: "Արամ" }], count: 1, full: false, mine: false });
    c.clock.t += 10;
    await b.gc.join(CHAT);
    await flush();
    assert.equal(seen[seen.length - 1].count, 2);
    assert.equal(seen[seen.length - 1].mine, true);
    const n = seen.length;
    await flush();
    assert.equal(seen.length, n, "told only when it changes");
    stop();
    // a watcher that was stopped hears nothing more
    a.gc.leave();
    await flush();
    assert.equal(seen.length, n);
    // a dead call: the entries' signs stopped long ago, the pointer is still in the chat
    const c2 = crowd([A, B]);
    try {
      await flush();
      const b2 = c2.people[B];
      c2.store.seed("gcalls/dead1", { chat: CHAT, host: A, hostName: "Արամ", title: "x", kind: "audio", state: "active", ts: c2.clock.t, parts: { [A]: { sid: "sa", j: c2.clock.t, at: c2.clock.t, name: "Արամ", m: false, cam: false } } });
      c2.store.seed("chats/" + CHAT, { ...c2.store.get("chats/" + CHAT), gcall: { id: "dead1", host: A, hostName: "Արամ", kind: "audio", ts: c2.clock.t } });
      await flush();
      const seen2 = [];
      b2.gc.watchBanner(CHAT, (x) => seen2.push(x));
      await flush();
      assert.equal(seen2[seen2.length - 1].count, 1);
      await c2.clock.advance(LIVE_MS + 25000);
      assert.equal(seen2[seen2.length - 1], null, "nobody gives a sign of life: the banner goes");
      assert.ok(c2.store.get("chats/" + CHAT).gcall, "but a young call is not closed yet");
      await c2.clock.advance(DEAD_MS);
      assert.equal(c2.store.get("gcalls/dead1").state, "ended", "an old one is closed");
      assert.equal(c2.store.get("chats/" + CHAT).gcall, undefined, "and its sign is taken out of the chat");
      assert.equal(c2.store.violations.length, 0, JSON.stringify(c2.store.violations));
    } finally {
      c2.done();
    }
  } finally {
    c.done();
  }
});

test("a person who is not a member of the group never learns of its call; the pointer of another chat is not mine; writes are only into my own entry", async () => {
  const c = crowd([A, B, C], { members: [A, B] });
  try {
    await flush();
    const cc = c.people[C];
    await c.people[A].gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    assert.equal(cc.phase(), PHASE.IDLE, "the chat list of C does not hold the group");
    assert.deepEqual(await cc.gc.join(CHAT), { ok: false, why: "ended" });
    // what a client must never be able to do, checked against the rules written out in this file
    const id = gdoc(c).id;
    const fc = c.store.client(C);
    await assert.rejects(fc.fsM.updateDoc(fc.fsM.doc(fc.db, "gcalls", id), { ["parts." + C]: { sid: "x", j: 1, at: 1, name: "c", m: false, cam: false } }), /denied/);
    const fb = c.store.client(B);
    await assert.rejects(fb.fsM.updateDoc(fb.fsM.doc(fb.db, "gcalls", id), { ["parts." + A]: fb.fsM.deleteField() }), /someone else's entry/);
    await assert.rejects(fb.fsM.updateDoc(fb.fsM.doc(fb.db, "gcalls", id), { state: "active", host: B }), /keys|state/);
    await assert.rejects(fb.fsM.addDoc(fb.fsM.collection(fb.db, "gcalls", id, "sig"), { from: A, to: B, k: "offer" }), /denied/);
    await assert.rejects(fb.fsM.addDoc(fb.fsM.collection(fb.db, "gcalls", id, "sig"), { from: B, to: C, k: "offer" }), /denied/, "a message to a person outside the group");
    await assert.rejects(fb.fsM.setDoc(fb.fsM.doc(fb.db, "gcalls", "forged"), { chat: CHAT, host: A, state: "active", parts: { [A]: {} } }), /host/);
    // a query on the messages of somebody else is refused
    const errs = [];
    fb.fsM.onSnapshot(fb.fsM.query(fb.fsM.collection(fb.db, "gcalls", id, "sig"), fb.fsM.where("to", "==", A)), () => {}, (e) => errs.push(e));
    await flush();
    assert.equal(errs.length, 1);
  } finally {
    c.done();
  }
});

test("firestore.rules: the group call rules are there, and what the page writes is what they allow (a person's own entry; members only; the pointer is an ordinary chat field)", () => {
  const rules = readFileSync(new URL("../firestore.rules", import.meta.url), "utf8");
  assert.match(rules, /match \/gcalls\/\{gid\}/);
  assert.match(rules, /match \/sig\/\{s\}/);
  assert.match(rules, /resource\.data\.to == u\(\)/, "a message is read by the one it is for");
  assert.match(rules, /nd\(\)\.from == u\(\)/);
  assert.match(rules, /ownKey\(nd\(\)\.parts, resource\.data\.parts\)/, "a person writes only their own entry");
  assert.match(rules, /nd\(\)\.host == u\(\)/);
  // the pointer: a chat's "gcall" is not among the protected keys of a chat, so any member may write it
  const protectedKeys = /!keys\(\)\.hasAny\(\[([^\]]*)\]\)/.exec(rules);
  assert.ok(protectedKeys);
  assert.ok(!protectedKeys[1].includes("gcall"));
});

// ---------------------------------------------------------------------------------------------- more: the awkward moments
const sigDoc = (c, as, id, o) => {
  const f = c.store.client(as);
  return f.fsM.addDoc(f.fsM.collection(f.db, "gcalls", id, "sig"), { at: c.clock.t, ...o });
};

test("an offer that comes while my own entry is still on its way to the server is answered when I am in (a person whose clock is behind joins later but has the earlier j)", async () => {
  const c = crowd([A, B, C]);
  try {
    await flush();
    const a = c.people[A];
    const cc = c.mk(C, { now: () => c.clock.t - 5000 }); // a clock 5 s behind: Carol's j is earlier than Aram's although she comes later
    await flush();
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    let release;
    c.store.ackGate = new Promise((r) => (release = r));
    const joining = cc.gc.join();
    await flush();
    assert.equal(cc.phase(), PHASE.JOINING, "the server has not answered yet");
    const id = gdoc(c).id;
    assert.ok(sigs(c, id).some((x) => x.k === "offer" && x.from === A && x.to === C), "Aram, who sees the entry at once, has made the offer (his j is the later)");
    release();
    c.store.ackGate = null;
    assert.equal((await joining).ok, true);
    await flush();
    assert.equal(openPeers(cc).length, 1, "the offer that came early was kept and taken");
    assert.ok(sigs(c, id).some((x) => x.k === "answer" && x.from === C && x.to === A));
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("an offer for another session of mine, a late answer of an older try, the same offer twice, an offer that crosses mine: none of them makes a second pair", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    c.clock.t += 10;
    await b.gc.join();
    await flush();
    const id = gdoc(c).id;
    const parts = c.store.get("gcalls/" + id).parts;
    const [sa, sb] = [parts[A].sid, parts[B].sid];
    const myOffer = sigs(c, id).find((x) => x.k === "offer" && x.from === B);
    // a late answer for another try of mine: not taken (the pair's description stays what the real answer made it)
    const pbB = openPeers(b)[0];
    const had = pbB.remoteDescription;
    await sigDoc(c, A, id, { from: A, to: B, fsid: sa, tsid: sb, lid: "stale", k: "answer", sdp: "answer from=" + A + " kinds=stale" });
    await flush();
    assert.equal(pbB.remoteDescription, had);
    // an offer for a session of mine that is gone
    const answersBefore = sigs(c, id).filter((x) => x.k === "answer").length;
    await sigDoc(c, A, id, { from: A, to: B, fsid: sa, tsid: "old-session", lid: "L2", k: "offer", sdp: "offer from=" + A + " kinds=audio" });
    await flush();
    assert.equal(sigs(c, id).filter((x) => x.k === "answer").length, answersBefore, "not answered");
    // an offer from Bob (the later one, so it would be taken) for a session of Aram's that is gone
    const peersAtA = a.world.peers.length;
    await sigDoc(c, B, id, { from: B, to: A, fsid: sb, tsid: "old-session", lid: "L9", k: "offer", sdp: "offer from=" + B + " kinds=audio" });
    await flush();
    assert.equal(a.world.peers.length, peersAtA, "an offer for another session of mine makes no pair");
    assert.equal(sigs(c, id).filter((x) => x.k === "answer").length, answersBefore, "and is not answered");
    // an offer from Aram that crosses Bob's own (Bob has the later j, so his offer stands)
    await sigDoc(c, A, id, { from: A, to: B, fsid: sa, tsid: sb, lid: "cross", k: "offer", sdp: "offer from=" + A + " kinds=audio" });
    await flush();
    assert.equal(sigs(c, id).filter((x) => x.k === "answer").length, answersBefore, "Bob keeps his own offer");
    assert.equal(openPeers(b).length, 1);
    // the same offer twice (Aram's side: Bob's offer again)
    const aBefore = openPeers(a).length;
    const peersMade = a.world.peers.length;
    await sigDoc(c, B, id, { from: B, to: A, fsid: sb, tsid: sa, lid: myOffer.lid, k: "offer", sdp: myOffer.sdp });
    await flush();
    assert.equal(a.world.peers.length, peersMade, "no new pair for the same offer");
    assert.equal(openPeers(a).length, aBefore);
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("an offer from a person the call document does not show for 30 s is dropped, not answered later", async () => {
  const c = crowd([A, B], { members: [A, B, C] });
  try {
    await flush();
    const a = c.people[A];
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    const id = gdoc(c).id;
    const sa = c.store.get("gcalls/" + id).parts[A].sid;
    await sigDoc(c, C, id, { from: C, to: A, fsid: "sc1", tsid: sa, lid: "L1", k: "offer", sdp: "offer from=" + C + " kinds=audio" });
    await flush();
    await c.clock.advance(31000);
    const f = c.store.client(C);
    await f.fsM.updateDoc(f.fsM.doc(f.db, "gcalls", id), { ["parts." + C]: { sid: "sc1", j: c.clock.t, at: c.clock.t, name: "Կարոլ", m: false, cam: false } });
    await flush();
    assert.equal(sigs(c, id).filter((x) => x.k === "answer").length, 0, "too old: not answered");
    assert.equal(openPeers(a).length, 0);
  } finally {
    c.done();
  }
});

test("a refused camera is a refusal (the microphone alone is for a camera that is missing or busy); a join of a call that is over, empty or never answered by the server fails with nothing asked of the person", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    a.world.failVideo = "NotAllowedError";
    assert.deepEqual(await a.gc.start({ chatId: CHAT, title: "x", video: true }), { ok: false, why: "denied" });
    assert.deepEqual(a.world.gum.map((x) => !!x.video), [true], "the microphone alone was not tried");
    await c.clock.advance(NOTE_LONG_MS + 100);
    // a sign in the chat for a call that is over / has nobody in / whose document is slow
    c.store.seed("gcalls/over1", { chat: CHAT, host: A, hostName: "Արամ", title: "x", kind: "audio", state: "ended", ts: c.clock.t, parts: { [A]: { sid: "s", j: 1, at: c.clock.t, name: "Արամ", m: false, cam: false } } });
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "over1", host: A, hostName: "Արամ", kind: "audio", ts: c.clock.t - RING_SHOW_MS - 1 } });
    await flush();
    assert.deepEqual(await b.gc.join(CHAT), { ok: false, why: "ended" });
    assert.equal(b.world.gum.length, 0);
    assert.equal(b.gc.state().note, "ended");
    await c.clock.advance(NOTE_MS + 100);
    c.store.seed("gcalls/empty1", { chat: CHAT, host: A, hostName: "Արամ", title: "x", kind: "audio", state: "active", ts: c.clock.t, parts: {} });
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "empty1", host: A, hostName: "Արամ", kind: "audio", ts: c.clock.t - RING_SHOW_MS - 1 } });
    await flush();
    assert.deepEqual(await b.gc.join(CHAT), { ok: false, why: "ended" });
    assert.equal(b.world.gum.length, 0);
    await c.clock.advance(NOTE_MS + 100);
    // the document does not answer (a bad network): the join gives up after FIRST_DOC_MS and does not hang
    c.store.seed("gcalls/slow1", { chat: CHAT, host: A, hostName: "Արամ", title: "x", kind: "audio", state: "active", ts: c.clock.t, parts: { [A]: { sid: "s", j: 1, at: c.clock.t, name: "Արամ", m: false, cam: false } } });
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "slow1", host: A, hostName: "Արամ", kind: "audio", ts: c.clock.t - RING_SHOW_MS - 1 } });
    await flush();
    c.store.silentDocs = true;
    const p = b.gc.join(CHAT);
    await c.clock.advance(8100);
    const got = await Promise.race([p, new Promise((r) => setTimeout(() => r("hung"), 500))]);
    assert.deepEqual(got, { ok: false, why: "ended" });
    assert.equal(b.world.gum.length, 0);
  } finally {
    c.done();
  }
});

test("the call is ended by somebody else while I am in it (a stale call closed by a watcher): I leave without writing anything", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    c.clock.t += 10;
    await b.gc.join();
    await flush();
    const id = gdoc(c).id;
    const writes = c.store.writes.length;
    c.store.seed("gcalls/" + id, { ...c.store.get("gcalls/" + id), state: "ended" });
    await flush();
    assert.equal(a.phase(), PHASE.ENDED);
    assert.equal(b.phase(), PHASE.ENDED);
    assert.equal(c.store.writes.length, writes, "nothing written by those who are told it is over");
    assert.ok(a.world.peers.every((x) => x.closed) && b.world.peers.every((x) => x.closed));
  } finally {
    c.done();
  }
});

test("the video that is sent: lighter for three, lighter still for four and more, and back to full when people leave; not touched again while nothing changes", async () => {
  const c = crowd([A, B, C, D]);
  try {
    await flush();
    const a = c.people[A];
    await a.gc.start({ chatId: CHAT, title: "x", video: true });
    await flush();
    const vid = (p) => p.world.peers.flatMap((x) => x.params).filter((x) => x.kind === "video");
    for (const u of [B, C, D]) {
      c.clock.t += 10;
      await c.people[u].gc.join();
      await flush();
      for (const x of [A, B, C, D]) connectAll(c.people[x]);
    }
    await flush();
    // what the senders of the pairs that are open were told last
    const lastOf = (p) => openPeers(p).map((x) => x.params.filter((y) => y.kind === "video").slice(-1)[0]);
    const all = (p, want) => assert.deepEqual(lastOf(p), openPeers(p).map(() => want));
    all(a, { kind: "video", maxBitrate: 300000, scaleResolutionDownBy: 1.5 }); // four people
    const n = vid(a).length;
    await c.clock.advance(BEAT_MS * 3);
    assert.equal(vid(a).length, n, "the senders are not told again while nothing changes");
    c.people[D].gc.leave();
    await flush();
    all(a, { kind: "video", maxBitrate: 500000, scaleResolutionDownBy: 1 }); // three
    c.people[C].gc.leave();
    await flush();
    all(a, { kind: "video", scaleResolutionDownBy: 1 }); // two: no limit at all
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("a pair that drops stays for GRACE_MS and is then lost; 'completed' is as good as 'connected'", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    c.clock.t += 10;
    await b.gc.join();
    await flush();
    openPeers(a)[0].ice("completed");
    openPeers(b)[0].ice("completed");
    assert.equal(a.gc.state().people[0].state, "connected");
    openPeers(a)[0].ice("disconnected");
    await c.clock.advance(GRACE_MS - 100);
    assert.equal(a.gc.state().people[0].state, "connected", "within the grace time");
    await c.clock.advance(300);
    assert.equal(a.gc.state().people[0].state, "lost");
  } finally {
    c.done();
  }
});

test("lines in the chat are written by the one who closes the call: a person who is not the host and finds no 'two together' writes none; my own account on another device does not ring", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const b = c.people[B];
    // the host's page is gone without a word (no "con" was ever written); Bob is the only one left in
    c.store.seed("gcalls/gone1", { chat: CHAT, host: A, hostName: "Արամ", title: "x", kind: "audio", state: "active", ts: c.clock.t, parts: { [A]: { sid: "sa", j: c.clock.t, at: c.clock.t, name: "Արամ", m: false, cam: false } } });
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "gone1", host: A, hostName: "Արամ", kind: "audio", ts: c.clock.t } });
    await flush();
    assert.equal((await b.gc.join()).ok, true);
    await flush();
    const f = c.store.client(A);
    await f.fsM.updateDoc(f.fsM.doc(f.db, "gcalls", "gone1"), { ["parts." + A]: f.fsM.deleteField() });
    await flush();
    b.gc.leave();
    await flush();
    assert.equal(c.store.get("gcalls/gone1").state, "ended", "the last one out closes it");
    assert.equal(c.store.collection("chats/" + CHAT + "/messages").length, 0, "a person who is not the host writes no missed or cancelled line");
    // my own account on another device: the call I started elsewhere does not ring here
    c.store.seed("gcalls/mine1", { chat: CHAT, host: B, hostName: "Բոբ", title: "x", kind: "audio", state: "active", ts: c.clock.t, parts: { [B]: { sid: "sb", j: c.clock.t, at: c.clock.t, name: "Բոբ", m: false, cam: false } } });
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "mine1", host: B, hostName: "Բոբ", kind: "audio", ts: c.clock.t } });
    await flush();
    const other = c.mk(B);
    await flush();
    assert.equal(other.phase(), PHASE.IDLE);
    // but Aram's page rings for it
    const a = c.people[A];
    assert.equal(a.phase(), PHASE.RINGING);
  } finally {
    c.done();
  }
});

test("leaving while the call is still being made undoes it; the last one out does not wipe the sign of a newer call; everybody leaving at once is closed by whoever looks", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    let release;
    c.store.ackGate = new Promise((r) => (release = r));
    const starting = a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    a.gc.leave();
    release();
    c.store.ackGate = null;
    assert.deepEqual(await starting, { ok: false, why: "gone" });
    await flush();
    const g = gdoc(c).data;
    assert.equal(g.state, "ended", "the call that was being made is closed");
    assert.deepEqual(g.parts, {});
    assert.equal(c.store.get("chats/" + CHAT).gcall, undefined, "and its sign is taken back");
    await c.clock.advance(NOTE_MS + 100);
    // the last one out leaves the sign of a newer call alone
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    const mine = c.store.get("chats/" + CHAT).gcall;
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "newer", host: B, hostName: "Բոբ", kind: "audio", ts: c.clock.t } });
    await flush();
    a.gc.leave();
    await flush();
    assert.equal(c.store.get("chats/" + CHAT).gcall.id, "newer");
    assert.ok(mine.id !== "newer");
    // everybody left in the same instant: the document has no entries and is still "active"; Bob, who looks at the chat, closes it
    c.store.seed("gcalls/zero1", { chat: CHAT, host: A, hostName: "Արամ", title: "x", kind: "audio", state: "active", ts: c.clock.t, parts: {} });
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "zero1", host: A, hostName: "Արամ", kind: "audio", ts: c.clock.t } });
    await flush();
    assert.equal(c.store.get("gcalls/zero1").state, "ended", "closed at once by the one who was rung");
    assert.equal(c.store.get("chats/" + CHAT).gcall, undefined);
    assert.equal(b.phase(), PHASE.IDLE);
    c.store.seed("gcalls/zero2", { chat: CHAT, host: A, hostName: "Արամ", title: "x", kind: "audio", state: "active", ts: c.clock.t - 1000000, parts: {} });
    c.store.seed("chats/" + CHAT, { ...c.store.get("chats/" + CHAT), gcall: { id: "zero2", host: A, hostName: "Արամ", kind: "audio", ts: c.clock.t } });
    await flush();
    const seen = [];
    b.gc.watchBanner(CHAT, (x) => seen.push(x));
    await flush();
    assert.equal(c.store.get("gcalls/zero2").state, "ended", "closed at once by the one whose chat is open");
  } finally {
    c.done();
  }
});

test("the seventh entry in the document (seven came at once): the six who are in do not make a pair with it", async () => {
  const six = [A, B, C, D, E, F];
  const c = crowd([...six, G]);
  try {
    await flush();
    await c.people[A].gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    for (const u of six.slice(1)) {
      c.clock.t += 10;
      await c.people[u].gc.join();
    }
    await flush();
    const id = gdoc(c).id;
    const f = c.store.client(G);
    await f.fsM.updateDoc(f.fsM.doc(f.db, "gcalls", id), { ["parts." + G]: { sid: "late", j: c.clock.t + 99, at: c.clock.t, name: "Գոռ", m: false, cam: false } });
    await flush();
    for (const u of six) {
      assert.equal(c.people[u].gc.state().count, 6);
      assert.equal(c.people[u].gc.state().people.some((p) => p.uid === G), false);
      assert.equal(openPeers(c.people[u]).length, 5);
    }
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

test("an answer that belongs to another try of the pair is not taken, even when the pair is waiting for an answer", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    c.store.failWrites = (path, u) => u === A && /\/sig\//.test(path); // Aram's answer is lost on the way
    c.clock.t += 10;
    await b.gc.join();
    await flush();
    c.store.failWrites = null;
    const id = gdoc(c).id;
    const parts = c.store.get("gcalls/" + id).parts;
    const pb = openPeers(b)[0];
    assert.equal(pb.remoteDescription, null, "Bob is waiting for an answer");
    await sigDoc(c, A, id, { from: A, to: B, fsid: parts[A].sid, tsid: parts[B].sid, lid: "an-older-try", k: "answer", sdp: "answer from=" + A + " kinds=audio" });
    await flush();
    assert.equal(pb.remoteDescription, null, "an answer of another try is not taken");
    const mine = sigs(c, id).find((x) => x.k === "offer");
    await sigDoc(c, A, id, { from: A, to: B, fsid: parts[A].sid, tsid: parts[B].sid, lid: mine.lid, k: "answer", sdp: "answer from=" + A + " kinds=audio" });
    await flush();
    assert.ok(pb.remoteDescription, "the right one is");
  } finally {
    c.done();
  }
});

test("a person who comes back with a new session and the earlier j: the pair of the old session is closed and the one who offers (now Aram) makes a new offer", async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    await a.gc.start({ chatId: CHAT, title: "x", video: false });
    await flush();
    c.clock.t += 10;
    await b.gc.join();
    await flush();
    const id = gdoc(c).id;
    const old = openPeers(a)[0];
    const other = c.mk(B, { now: () => c.clock.t - 100000 }); // a clock far behind: this session of Bob's has the smaller j
    await flush();
    assert.equal((await other.gc.join(CHAT)).ok, true);
    await flush();
    assert.equal(old.closed, true, "Aram let the pair with the old session go");
    const offersFromA = sigs(c, id).filter((x) => x.k === "offer" && x.from === A);
    assert.equal(offersFromA.length, 1, "and, being the later one now, made the offer");
    assert.equal(openPeers(other).length, 1, "the new session answered");
    assert.equal(c.store.violations.length, 0, JSON.stringify(c.store.violations));
  } finally {
    c.done();
  }
});

// ---------------------------------------------------------------------------------------------- the phone (call/GroupCallRules.kt, call/GroupCallManager.kt)
// The phone and the page are in one call together, so they must write the same documents, read the same fields and use the same numbers and rules.
// These tests read the phone's two files as text (they are skipped when the files are not here). The Kotlin could not be compiled or run where this
// was written: what is checked here is that the two sides agree, not that the Kotlin works.
const PHONE_CALL = new URL("../app/src/main/java/am/khosq/app/call/", import.meta.url);
const phoneFile = (f) => (existsSync(new URL(f, PHONE_CALL)) ? readFileSync(new URL(f, PHONE_CALL), "utf8") : null);
const KT_RULES = phoneFile("GroupCallRules.kt");
const KT_MANAGER = phoneFile("GroupCallManager.kt");
const JS_SRC = readFileSync(new URL("../docs/gcalls.js", import.meta.url), "utf8");
const ktNumber = (t) => Number(t.replace(/_/g, "").replace(/L$/, ""));

/** The keys of one hashMapOf<String, Any>(...) of the Kotlin text: the strings that stand in front of " to " at the top level of the brackets. */
function ktKeys(src, marker, after = "") {
  const base = after ? src.indexOf(after) : 0;
  assert.ok(base >= 0, "the phone has: " + after);
  const at = src.indexOf(marker, base);
  assert.ok(at >= 0, "the phone has: " + marker);
  let i = src.indexOf("(", at);
  let depth = 0;
  const keys = [];
  for (; i < src.length; i++) {
    const ch = src[i];
    if (ch === "(") depth++;
    else if (ch === ")") {
      depth--;
      if (depth === 0) break;
    } else if (ch === '"') {
      let j = i + 1;
      while (src[j] !== '"') j += src[j] === "\\" ? 2 : 1;
      if (depth === 1 && /^\s+to\s/.test(src.slice(j + 1, j + 6))) keys.push(src.slice(i + 1, j));
      i = j;
    }
  }
  return keys.sort();
}

test("the phone's numbers are the page's (GroupCallRules.kt)", { skip: KT_RULES === null }, () => {
  const kt = {};
  for (const m of KT_RULES.matchAll(/const val (\w+) = ([0-9_]+)L?/g)) kt[m[1]] = ktNumber(m[2]);
  const js = { MAX_PARTS, RING_MS, RING_SHOW_MS, BEAT_MS, LIVE_MS, FIRST_SIGHT_MS, ALONE_MS, CONNECT_MS, GRACE_MS, RETRY_MS, RETRIES, BATCH_MS, DEAD_MS, NOTE_MS, NOTE_LONG_MS, FIRST_DOC_MS };
  assert.deepEqual(kt, js);
});

test("the phone tells a sender of a picture the same as the page does for 2, 3, 4, 5 and 6 people (GroupCallRules.kt)", { skip: KT_RULES === null }, () => {
  const body = KT_RULES.slice(KT_RULES.indexOf("fun qualityFor"), KT_RULES.indexOf("/** The sign in a chat document"));
  const kt = [...body.matchAll(/Quality\(([0-9_]+), ([0-9.]+)\)/g)].map((m) => ({ maxBitrate: ktNumber(m[1]), scale: Number(m[2]) }));
  assert.equal(kt.length, 4);
  assert.deepEqual(kt[0], qualityFor(1));
  assert.deepEqual(kt[0], qualityFor(2));
  assert.deepEqual(kt[1], qualityFor(3));
  assert.deepEqual(kt[2], qualityFor(4));
  assert.deepEqual(kt[3], qualityFor(5));
  assert.deepEqual(kt[3], qualityFor(6));
  assert.match(body, /n <= 2 ->/);
  assert.match(body, /n == 3 ->/);
  assert.match(body, /n == 4 ->/);
});

test("who makes the offer, who is alive and in what order people stand: the same lines on both sides (GroupCallRules.kt)", { skip: KT_RULES === null }, () => {
  const flat = (t) => t.replace(/\s+/g, " ");
  // the one who came later offers (larger j, then larger uid)
  assert.match(flat(KT_RULES), /fun iOffer\(myJ: Long, myUid: String, pJ: Long, pUid: String\): Boolean = myJ > pJ \|\| \(myJ == pJ && myUid > pUid\)/);
  assert.match(flat(JS_SRC), /const iOffer = \(me, p\) => me\.j > p\.j \|\| \(me\.j === p\.j && uid > p\.uid\);/);
  // an entry seen for the first time counts only if its sign of life is recent, else it is old at once
  assert.match(flat(KT_RULES), /if \(Math\.abs\(t - at\) > FIRST_SIGHT_MS\) t - LIVE_MS - 1 else t/);
  assert.match(flat(JS_SRC), /Math\.abs\(t - at\) > FIRST_SIGHT_MS \? t - LIVE_MS - 1 : t/);
  // a new sign of life (a different "at") starts the count again, on my own clock
  assert.match(flat(KT_RULES), /else if \(s\.at != at\) \{ seen\[key\] = Seen\(at, t\) \}/);
  assert.match(flat(JS_SRC), /else if \(s\.at !== at\) seen\.set\(key, \{ at, t \}\);/);
  // an entry that has not moved for LIVE_MS does not count
  assert.match(flat(KT_RULES), /if \(t - cur\.t >= LIVE_MS\) continue/);
  assert.match(flat(JS_SRC), /if \(t - seen\.get\(key\)\.t >= LIVE_MS\) continue;/);
  // oldest first: by j, then by uid; names are cut at 60
  assert.match(flat(KT_RULES), /compareBy<Person>\(\{ it\.j \}, \{ it\.uid \}\)/);
  assert.match(flat(JS_SRC), /a\.j - b\.j \|\| \(a\.uid < b\.uid \? -1 : a\.uid > b\.uid \? 1 : 0\)/);
  assert.match(flat(KT_RULES), /\.take\(60\)/);
  assert.match(flat(JS_SRC), /cut\(str\(p\.name\), 60\)/);
  // the muted and the camera flags are "exactly true"
  assert.match(flat(KT_RULES), /muted = p\["m"\] == true/);
  assert.match(flat(KT_RULES), /cam = p\["cam"\] == true/);
  assert.match(flat(JS_SRC), /m: p\.m === true, cam: p\.cam === true/);
});

test("the sign in a chat document is read in the same way on both sides (GroupCallRules.kt)", { skip: KT_RULES === null }, () => {
  const flat = (t) => t.replace(/\s+/g, " ");
  assert.match(flat(KT_RULES), /if \(id\.isEmpty\(\) \|\| id\.length > 64 \|\| id\.contains\('\/'\)\) return null/);
  assert.match(flat(JS_SRC), /if \(!id \|\| id\.length > 64 \|\| id\.includes\("\/"\)\) return null;/);
  assert.match(flat(KT_RULES), /video = g\["kind"\] == "video"/);
  for (const [raw, want] of [
    [{ gcall: { id: "x1", host: "H", hostName: "Հ", kind: "video", ts: 5 } }, { id: "x1", host: "H", hostName: "Հ", video: true, ts: 5 }],
    [{ gcall: { id: "x1", kind: "audio" } }, { id: "x1", host: "", hostName: "", video: false, ts: 0 }],
    [{ gcall: { id: "a/b" } }, null],
    [{ gcall: { id: "" } }, null],
    [{}, null],
  ]) assert.deepEqual(pointerOf(raw), want);
});

test("the phone writes the same fields as the page: the call, my entry, the sign in the chat, the messages between two devices and the chat line (GroupCallManager.kt)", { skip: KT_MANAGER === null }, async () => {
  const c = crowd([A, B]);
  try {
    await flush();
    const a = c.people[A];
    const b = c.people[B];
    await a.gc.start({ chatId: CHAT, title: "x", video: true });
    await flush();
    c.clock.t += 10;
    await b.gc.join();
    await flush();
    const g = gdoc(c);
    // the call document
    assert.deepEqual(ktKeys(KT_MANAGER, "val data = hashMapOf<String, Any>(").filter((k) => k !== "parts"), Object.keys(g.data).filter((k) => !["parts", "con"].includes(k)).sort());
    assert.ok(ktKeys(KT_MANAGER, "val data = hashMapOf<String, Any>(").includes("parts"));
    // an entry of a person
    assert.deepEqual(ktKeys(KT_MANAGER, "val part = hashMapOf<String, Any>("), Object.keys(g.data.parts[A]).sort());
    // the sign in the chat
    assert.deepEqual(ktKeys(KT_MANAGER, 'hashMapOf<String, Any>("id" to id'), Object.keys(c.store.get("chats/" + CHAT).gcall).sort());
    // the messages between two devices: what every one holds, and what an offer and a reply add
    const all = sigs(c, g.id);
    const offer = all.find((x) => x.k === "offer");
    const answer = all.find((x) => x.k === "answer");
    assert.ok(offer && answer, "an offer and an answer were written");
    const base = ktKeys(KT_MANAGER, "val body = hashMapOf<String, Any>(");
    assert.deepEqual(base, ["at", "from", "fsid", "k", "lid", "to", "tsid"]);
    assert.deepEqual(Object.keys(offer).sort(), [...base, "sdp"].sort());
    assert.deepEqual(Object.keys(answer).sort(), [...base, "sdp"].sort());
    assert.match(KT_MANAGER, /body\["sdp"\] = sdp/);
    assert.match(KT_MANAGER, /body\["c"\] = c/);
    assert.match(JS_SRC, /if \(o\.sdp\) body\.sdp = o\.sdp;/);
    assert.match(JS_SRC, /if \(o\.c\) body\.c = o\.c;/);
    // an address: { m, i, s }
    assert.deepEqual(ktKeys(KT_MANAGER, 'hashMapOf<String, Any>("m" to (c.sdpMid'), ["i", "m", "s"]);
    assert.match(JS_SRC, /link\.out\.push\(\{ m: str\(e\.candidate\.sdpMid\), i: Number\(e\.candidate\.sdpMLineIndex\) \|\| 0, s: e\.candidate\.candidate \}\);/);
    // the chat line: A (the host) cancels at once
    const c2 = crowd([A, B]);
    try {
      await flush();
      await c2.people[A].gc.start({ chatId: CHAT, title: "x", video: false });
      await flush();
      c2.people[A].gc.leave();
      await flush();
      const line = c2.store.collection("chats/" + CHAT + "/messages")[0];
      assert.ok(line, "a line was written");
      assert.deepEqual(ktKeys(KT_MANAGER, "hashMapOf<String, Any>(", "private fun logCall"), Object.keys(line.data).sort());
      assert.equal(line.data.code, "call_cancelled");
    } finally {
      c2.done();
    }
  } finally {
    c.done();
  }
});

test("the phone ends a call, closes it and writes the chat line in the same cases as the page (GroupCallManager.kt)", { skip: KT_MANAGER === null }, () => {
  const flat = (t) => t.replace(/\s+/g, " ");
  const kt = flat(KT_MANAGER);
  // the codes of the chat line
  for (const code of ["call_done", "call_missed", "call_cancelled"]) {
    assert.ok(kt.includes('"' + code + '"'), "the phone writes " + code);
    assert.ok(JS_SRC.includes('"' + code + '"'), "the page writes " + code);
  }
  assert.doesNotMatch(kt, /call_declined/, "nobody refuses a group call out loud: nothing is written for it");
  // the document is closed by the last one out, with "ended" and the length
  assert.match(kt, /"state" to "ended", "dur" to sec/);
  assert.match(flat(JS_SRC), /state: "ended"/);
  // the other people of a call are seen from the entries that are alive, the same maximum
  assert.match(kt, /people\.size >= GroupCallRules\.MAX_PARTS && people\.none \{ it\.uid == myUid \}/);
  assert.match(flat(JS_SRC), /MAX_PARTS/);
  // "con" (two were together) is marked by the first of the people there, and a call that had people ends when one is left for ALONE_MS
  assert.match(kt, /live\.size >= 2 && num\(d\["con"\]\) == 0L && live\[0\]\.uid == myUid/);
  assert.match(flat(JS_SRC), /live\.length >= 2 && !doc\.con && live\[0\]\.uid === uid/);
  assert.match(kt, /live\.size == 1 && num\(doc\?\.get\("con"\)\) > 0L/);
  assert.match(flat(JS_SRC), /live\.length === 1 && doc\.con/);
  // the same words for why a call ended
  for (const note of ["ended", "full", "denied", "noMedia", "error", "noanswer", "elsewhere"]) {
    assert.ok(kt.includes('"' + note + '"'), "the phone has the note " + note);
    assert.ok(JS_SRC.includes('"' + note + '"'), "the page has the note " + note);
  }
  // the phone asks the Worker for a push in the same way
  assert.match(kt, /PushClient\.notify\(chatId, "call"/);
  assert.match(flat(JS_SRC), /type: "call"/);
});
