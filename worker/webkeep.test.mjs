// Forwarding, starred messages and the pinned message on the web page (docs/send.js forward / setPinned / canForward, docs/stars.js, E2E.sealSelf).
// What is written must be what the phone writes (Repository.forward / setStar / removeStar / setPinned) and what the Firestore rules allow.
// Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { makeE2E, toB64, fromB64 } from "../docs/e2e.js";
import { makeSender, canForward, whyNot, cut } from "../docs/send.js";
import { makeStars, starFor, starKey, STAR_TEXT_MAX, STAR_STICKER_MAX, STAR_LIMIT } from "../docs/stars.js";

const pins = () => {
  const m = new Map();
  return { get: async (u) => m.get(u), set: async (u, t) => void m.set(u, t) };
};
async function people() {
  const mk = async (uid) => {
    const pair = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
    const pub = toB64(new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)));
    const priv = await crypto.subtle.importKey("pkcs8", await crypto.subtle.exportKey("pkcs8", pair.privateKey), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
    return { uid, pub, priv };
  };
  const all = { a: await mk("Aa00000000000000000000000001"), b: await mk("Bb00000000000000000000000002"), c: await mk("Cc00000000000000000000000003") };
  const dir = Object.fromEntries(Object.values(all).map((p) => [p.uid, p.pub]));
  const make = (p, keys = dir) => makeE2E({ uid: p.uid, key: p.priv, pub: p.pub, publicOf: async (u) => keys[u] ?? null, pins: pins() });
  return { ...all, e: { a: make(all.a), b: make(all.b), c: make(all.c) }, make, dir };
}
const dmId = (x, y) => (x < y ? x + "_" + y : y + "_" + x);
const DEL = { sv: "del" };

/** A pretend Firestore that writes down what it is asked to write; a Bytes value holds its bytes. */
function fake() {
  const log = [];
  const docs = new Map();
  const ref = (path) => ({ kind: "doc", path, id: path[path.length - 1] });
  let n = 0;
  const state = { fail: false, snaps: [] };
  const fsM = {
    collection: (db, ...path) => ({ kind: "col", path }),
    doc: (a, ...path) => (a && a.kind === "col" ? ref([...a.path, "auto" + ++n]) : ref(path)),
    query: (c, ...cs) => ({ kind: "q", path: c.path, cs }),
    orderBy: (field, dir) => ({ t: "order", field, dir }),
    limit: (k) => ({ t: "limit", n: k }),
    serverTimestamp: () => ({ sv: "ts" }),
    increment: (k) => ({ sv: "inc", n: k }),
    deleteField: () => DEL,
    Bytes: { fromUint8Array: (u8) => ({ isBytes: true, u8 }) },
    writeBatch: () => {
      const ops = [];
      return {
        set(r, d) { ops.push({ kind: "set", path: r.path.join("/"), data: d }); return this; },
        update(r, d) { ops.push({ kind: "update", path: r.path.join("/"), data: d }); return this; },
        commit: async () => void log.push(...ops),
      };
    },
    updateDoc: async (r, d) => {
      if (state.fail) throw new Error("denied");
      log.push({ kind: "update", path: r.path.join("/"), data: d });
    },
    setDoc: async (r, d) => {
      if (state.fail) throw new Error("denied");
      log.push({ kind: "set", path: r.path.join("/"), data: d });
      docs.set(r.path.join("/"), d);
    },
    deleteDoc: async (r) => {
      if (state.fail) throw new Error("denied");
      log.push({ kind: "delete", path: r.path.join("/") });
      docs.delete(r.path.join("/"));
    },
    onSnapshot: (q, next, err) => {
      state.snaps.push({ q, next, err });
      return () => {};
    },
  };
  return { f: { db: {}, auth: { currentUser: { getIdToken: async () => "ID" } }, fsM }, log, docs, state };
}
const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, ...Array.from({ length: 300 }, (_, i) => (i * 7 + 3) & 255), 0xff, 0xd9]);
const M4A = Uint8Array.from([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, ...Array.from({ length: 500 }, (_, i) => (i * 13 + 5) & 255)]);
const WAVE = "5844889758577569678469996968976537893878";
const has = (hay, needle) => Buffer.from(hay).includes(Buffer.from(needle));
const msg = (kind, extra = {}) => ({ id: "m1", from: "x", kind, ts: 1000, deleted: false, ...extra });
const readable = (text, bytes = null) => ({ ok: true, text, reply: "", bytes, votes: {} });

// ------------------------------------------------------------------------------------------------------------------------ what can be forwarded
test("canForward: a text, a photo, a voice note, a place and a sticker I can read; not a poll, an SOS card, a call line, a deleted one or one I cannot open", () => {
  assert.equal(canForward(msg("text"), readable("hello")), true);
  assert.equal(canForward(msg("loc"), readable("40.1,44.5")), true);
  assert.equal(canForward(msg("sticker"), readable("hi")), true);
  assert.equal(canForward(msg("img"), readable("", JPEG)), true);
  assert.equal(canForward(msg("voice"), readable("", M4A)), true);
  assert.equal(canForward(msg("poll"), readable('{"q":"?","o":["a","b"]}')), false, "the page does not show polls yet, so it does not copy one");
  assert.equal(canForward(msg("sos"), readable("sos")), false, "an SOS card means something only where it was sent");
  assert.equal(canForward(msg("call"), readable("x")), false);
  assert.equal(canForward(msg("sys"), readable("x")), false);
  assert.equal(canForward(msg("text", { deleted: true }), readable("hello")), false);
  assert.equal(canForward(msg("text"), { ...readable("hello"), ok: false }), false, "sealed and cannot be opened");
  assert.equal(canForward(msg("text"), readable("")), false, "nothing to copy");
  assert.equal(canForward(msg("img"), readable("", new Uint8Array(0))), false);
  assert.equal(canForward(msg("img"), readable("", null)), false);
  assert.equal(canForward(msg("voice"), readable("text only")), false);
  assert.equal(canForward(msg("text"), null), false);
  assert.equal(canForward(null, readable("x")), false);
});

// ------------------------------------------------------------------------------------------------------------------------ forwarding
test("forward a text into a one-to-one chat: the same fields as the phone, marked fwd, sealed for that chat, the line is the text", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: dmId(p.a.uid, p.b.uid), members: [p.a.uid, p.b.uid], status: "active" };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const m = msg("text", { from: p.c.uid, reply: "sealed quote", replyFrom: p.b.uid, edited: true, reactions: { [p.b.uid]: "👍" } });
  const r = await s.forward(chat, m, readable("The meeting is at 5 ✅"));
  assert.equal(r.ok, true);
  await r.taken;
  assert.equal(log.length, 2, "the message and the chat's line, in one batch");
  const [wr, line] = log;
  assert.equal(wr.kind, "set");
  assert.match(wr.path, new RegExp("^chats/" + chat.id + "/messages/auto\\d+$"));
  assert.deepEqual(Object.keys(wr.data).sort(), ["dur", "e", "from", "fwd", "kind", "text", "ts"], "no quote, no reactions, no 'changed' mark are copied");
  assert.equal(wr.data.from, p.a.uid, "from me, whoever wrote the original");
  assert.equal(wr.data.fwd, true);
  assert.equal(wr.data.e, true);
  assert.equal(wr.data.kind, "text");
  assert.equal(wr.data.dur, 0);
  assert.deepEqual(wr.data.ts, { sv: "ts" });
  assert.ok(!wr.data.text.includes("meeting"), "sealed, not readable");
  const kb = await p.e.b.chatKey(chat.id);
  assert.equal(await p.e.b.openText(kb, chat.id, p.a.uid, "t", wr.data.text), "The meeting is at 5 ✅", "Bob opens it with his own key");
  assert.equal(await p.e.b.openText(kb, chat.id, p.a.uid, "r", wr.data.text), null, "bound to its field");
  assert.equal(line.kind, "update");
  assert.equal(line.path, "chats/" + chat.id);
  assert.deepEqual(Object.keys(line.data).sort(), ["lastEnc", "lastFrom", "lastKind", "lastText", "lastTs", "lastW", "unread." + p.b.uid]);
  assert.equal(line.data.lastEnc, true);
  assert.equal(line.data.lastKind, "text");
  assert.equal(line.data.lastFrom, p.a.uid);
  assert.deepEqual(line.data.lastW, DEL, "a one-to-one chat has no wraps");
  assert.deepEqual(line.data["unread." + p.b.uid], { sv: "inc", n: 1 });
  assert.equal(await p.e.b.openLast(chat.id, p.a.uid, line.data.lastText, {}), "The meeting is at 5 ✅");
  assert.ok(Math.abs(line.data.lastTs - Date.now()) < 5000);
});

test("forward: the line of a long text is its first 80 characters, never half an emoji", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: dmId(p.a.uid, p.b.uid), members: [p.a.uid, p.b.uid] };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const long = "x".repeat(79) + "😀" + "tail";
  const r = await s.forward(chat, msg("text"), readable(long));
  await r.taken;
  assert.equal(await p.e.b.openText(await p.e.b.chatKey(chat.id), chat.id, p.a.uid, "t", log[0].data.text), long, "the message itself is whole");
  const l = await p.e.b.openLast(chat.id, p.a.uid, log[1].data.lastText, {});
  assert.equal(l, "x".repeat(79), "the emoji would be cut in half: it is left out");
  assert.equal(l, cut(long, 80));
});

test("forward a photo: the bytes sealed with the message key, field d, the line is the sign of its kind, not sealed", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: dmId(p.a.uid, p.b.uid), members: [p.a.uid, p.b.uid] };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const r = await s.forward(chat, msg("img", { dur: 0 }), readable("", JPEG));
  await r.taken;
  const [wr, line] = log;
  assert.deepEqual(Object.keys(wr.data).sort(), ["data", "dur", "e", "from", "fwd", "kind", "text", "ts"]);
  assert.equal(wr.data.kind, "img");
  assert.equal(wr.data.text, "");
  assert.equal(wr.data.fwd, true);
  const sealed = wr.data.data.u8;
  assert.equal(sealed.length, 12 + JPEG.length + 16);
  assert.ok(!has(sealed, JPEG.slice(0, 12)));
  assert.deepEqual(await p.e.b.openBytes(await p.e.b.chatKey(chat.id), chat.id, p.a.uid, "d", sealed), JPEG);
  assert.deepEqual(line.data, { lastText: "📷", lastEnc: false, lastW: DEL, lastKind: "img", lastTs: line.data.lastTs, lastFrom: p.a.uid, ["unread." + p.b.uid]: { sv: "inc", n: 1 } });
});

test("forward a voice note: the length (0..3600 whole seconds) and the bars (40 digits 1..9) go with it, nothing else is invented", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: dmId(p.a.uid, p.b.uid), members: [p.a.uid, p.b.uid] };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  let r = await s.forward(chat, msg("voice", { dur: 7, wave: WAVE }), readable("", M4A));
  await r.taken;
  assert.deepEqual(Object.keys(log[0].data).sort(), ["data", "dur", "e", "from", "fwd", "kind", "text", "ts", "wave"]);
  assert.equal(log[0].data.dur, 7);
  assert.equal(log[0].data.wave, WAVE);
  assert.equal(log[1].data.lastText, "🎤");
  assert.equal(log[1].data.lastKind, "voice");
  assert.deepEqual(await p.e.b.openBytes(await p.e.b.chatKey(chat.id), chat.id, p.a.uid, "d", log[0].data.data.u8), M4A);
  log.length = 0;
  const again = async (m) => {
    const rr = await s.forward(chat, m, readable("", M4A));
    await rr.taken;
    const [w] = log.splice(0, 2);
    return w.data;
  };
  let d = await again(msg("voice", { dur: 99999, wave: "123" }));
  assert.equal(d.dur, 3600);
  assert.equal("wave" in d, false, "a wrong length of bars is left out");
  d = await again(msg("voice", { dur: -4, wave: "" }));
  assert.equal(d.dur, 0);
  d = await again(msg("voice", { dur: 6.9, wave: "0".repeat(40) }));
  assert.equal(d.dur, 6);
  assert.equal("wave" in d, false, "a zero is not a bar");
  d = await again(msg("voice", { dur: 3, wave: "9".repeat(40) }));
  assert.equal(d.wave, "9".repeat(40));
});

test("forward a place and a sticker: sealed text, the line is the sign of the kind", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: dmId(p.a.uid, p.b.uid), members: [p.a.uid, p.b.uid] };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const k = await p.e.b.chatKey(chat.id);
  let r = await s.forward(chat, msg("loc"), readable("40.1792,44.4991"));
  await r.taken;
  assert.equal(log[0].data.kind, "loc");
  assert.equal(await p.e.b.openText(k, chat.id, p.a.uid, "t", log[0].data.text), "40.1792,44.4991");
  assert.deepEqual([log[1].data.lastText, log[1].data.lastEnc, log[1].data.lastKind], ["📍", false, "loc"]);
  log.length = 0;
  r = await s.forward(chat, msg("sticker"), readable("hello_wave"));
  await r.taken;
  assert.equal(log[0].data.kind, "sticker");
  assert.equal(await p.e.b.openText(k, chat.id, p.a.uid, "t", log[0].data.text), "hello_wave");
  assert.deepEqual([log[1].data.lastText, log[1].data.lastEnc, log[1].data.lastKind], ["🙂", false, "sticker"]);
});

test("forward into a group: a message key of its own, wrapped for every member; each of them opens it; the line carries the same wraps", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: "grp1", group: true, members: [p.a.uid, p.b.uid, p.c.uid] };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  let r = await s.forward(chat, msg("text"), readable("for the group"));
  await r.taken;
  const [wr, line] = log;
  assert.deepEqual(Object.keys(wr.data.w).sort(), [p.a.uid, p.b.uid, p.c.uid].sort());
  assert.equal(wr.data.fwd, true);
  for (const who of ["a", "b", "c"]) {
    const k = await p.e[who].groupKey(chat.id, p.a.uid, wr.data.w);
    assert.equal(await p.e[who].openText(k, chat.id, p.a.uid, "t", wr.data.text), "for the group", who);
    assert.equal(await p.e[who].openLast(chat.id, p.a.uid, line.data.lastText, line.data.lastW), "for the group", who);
  }
  assert.deepEqual(line.data.lastW, wr.data.w);
  assert.deepEqual(line.data["unread." + p.b.uid], { sv: "inc", n: 1 });
  assert.deepEqual(line.data["unread." + p.c.uid], { sv: "inc", n: 1 });
  assert.equal("unread." + p.a.uid in line.data, false);
  log.length = 0;
  r = await s.forward(chat, msg("img"), readable("", JPEG));
  await r.taken;
  assert.deepEqual(log[0].data.w ? Object.keys(log[0].data.w).sort() : [], [p.a.uid, p.b.uid, p.c.uid].sort());
  assert.deepEqual(log[1].data.lastW, DEL, "a card line is not sealed: no wraps in it");
  const k = await p.e.c.groupKey(chat.id, p.a.uid, log[0].data.w);
  assert.deepEqual(await p.e.c.openBytes(k, chat.id, p.a.uid, "d", log[0].data.data.u8), JPEG);
});

test("forward into my saved messages: sealed with my own chat key, no counter, no push", async () => {
  const p = await people();
  const { f, log } = fake();
  const asked = [];
  const real = globalThis.fetch;
  globalThis.fetch = async (u, o) => (asked.push([String(u), o]), new Response("ok"));
  try {
    const chat = { id: "saved" + p.a.uid, group: true, saved: true, members: [p.a.uid] };
    const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "https://w.example/" });
    const r = await s.forward(chat, msg("text"), readable("note to self"));
    assert.equal(r.ok, true);
    await r.committed;
    assert.deepEqual(Object.keys(log[1].data).filter((k) => k.startsWith("unread")), []);
    assert.equal(asked.length, 0);
    assert.equal(await p.e.a.openText(await p.e.a.chatKey(chat.id), chat.id, p.a.uid, "t", log[0].data.text), "note to self");
  } finally {
    globalThis.fetch = real;
  }
});

test("forward: a push goes out for a chat with somebody else in it, after the server took the message", async () => {
  const p = await people();
  const { f } = fake();
  const asked = [];
  const real = globalThis.fetch;
  globalThis.fetch = async (u, o) => (asked.push([String(u), o]), new Response("ok"));
  try {
    const chat = { id: dmId(p.a.uid, p.b.uid), members: [p.a.uid, p.b.uid] };
    const s = makeSender({ f, uid: p.a.uid, name: "Ann", e2e: p.e.a, worker: "https://w.example/" });
    const r = await s.forward(chat, msg("text"), readable("secret words"));
    await r.committed;
    assert.equal(asked.length, 1);
    const body = JSON.parse(asked[0][1].body);
    assert.equal(body.chatId, chat.id);
    assert.equal(body.type, "msg");
    assert.ok(!asked[0][1].body.includes("secret"), "no text in the push");
  } finally {
    globalThis.fetch = real;
  }
});

test("forward is refused, and nothing is written, where the phone would not offer the chat or the rules would refuse", async () => {
  const p = await people();
  const { f, log } = fake();
  const me = p.a.uid;
  const s = makeSender({ f, uid: me, name: "A", e2e: p.e.a, worker: "" });
  const m = msg("text");
  const v = readable("x");
  const dm = (extra = {}) => ({ id: dmId(me, p.b.uid), members: [me, p.b.uid], ...extra });
  assert.deepEqual(await s.forward(dm({ blocked: { [p.b.uid]: true } }), m, v), { ok: false, why: "blocked" });
  assert.deepEqual(await s.forward(dm({ blocked: { [me]: true } }), m, v), { ok: false, why: "blocked" });
  assert.deepEqual(await s.forward(dm({ status: "pending", requester: p.b.uid }), m, v), { ok: false, why: "pending" }, "somebody's request to me is not a chat yet");
  assert.deepEqual(await s.forward(dm({ status: "pending", requester: me }), m, v), { ok: false, why: "pending" }, "a request of mine waiting for an answer: the phone does not offer it either");
  assert.deepEqual(await s.forward({ id: "grp1", group: true, members: [me, p.b.uid], sendMode: "admins", admins: [p.b.uid] }, m, v), { ok: false, why: "admins" });
  assert.deepEqual(await s.forward({ id: "weird", members: [me] }, m, v), { ok: false, why: "noChat" });
  assert.deepEqual(await s.forward(dm(), msg("poll"), v), { ok: false, why: "no" });
  assert.deepEqual(await s.forward(dm(), msg("sos"), v), { ok: false, why: "no" });
  assert.deepEqual(await s.forward(dm(), m, { ...v, ok: false }), { ok: false, why: "no" });
  assert.deepEqual(await s.forward(dm(), msg("text", { deleted: true }), v), { ok: false, why: "no" });
  assert.equal(log.length, 0);
  // an admin may write in an announcement group; an accepted chat of an old kind without a status counts as accepted
  assert.equal((await s.forward({ id: "grp1", group: true, members: [me, p.b.uid], sendMode: "admins", admins: [me] }, m, v)).ok, true);
  assert.equal((await s.forward(dm(), m, v)).ok, true);
});

test("forward: nothing is written in the clear when a person has no key; the whole thing is refused", async () => {
  const p = await people();
  const { f, log } = fake();
  const e = p.make(p.a, { [p.a.uid]: p.a.pub }); // nobody else has a key on the server
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: e, worker: "" });
  assert.deepEqual(await s.forward({ id: dmId(p.a.uid, p.b.uid), members: [p.a.uid, p.b.uid] }, msg("text"), readable("x")), { ok: false, why: "noKey" });
  assert.deepEqual(await s.forward({ id: "grp1", group: true, members: [p.a.uid, p.b.uid, p.c.uid] }, msg("img"), readable("", JPEG)), { ok: false, why: "noKey" });
  assert.equal(log.length, 0);
});

// ------------------------------------------------------------------------------------------------------------------------ pinning
test("setPinned: the chat's pin field holds the message id and nothing else; null takes it off with a field delete", async () => {
  const p = await people();
  const { f, log, state } = fake();
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const chat = { id: dmId(p.a.uid, p.b.uid), members: [p.a.uid, p.b.uid] };
  let r = s.setPinned(chat, "m7");
  assert.equal(r.ok, true);
  await r.committed;
  r = s.setPinned(chat, null);
  await r.committed;
  assert.deepEqual(log, [
    { kind: "update", path: "chats/" + chat.id, data: { pin: "m7" } },
    { kind: "update", path: "chats/" + chat.id, data: { pin: DEL } },
  ]);
  for (const bad of ["", undefined, 7, {}, ["m1"]]) assert.deepEqual(s.setPinned(chat, bad), { ok: false, why: "no" });
  assert.equal(log.length, 2);
  // an announcement group: only an admin pins (the phone's screen; the rules would let anybody)
  const grp = { id: "grp1", group: true, members: [p.a.uid, p.b.uid], sendMode: "admins", admins: [p.b.uid] };
  assert.deepEqual(s.setPinned(grp, "m1"), { ok: false, why: "admins" });
  assert.deepEqual(s.setPinned(grp, null), { ok: false, why: "admins" });
  assert.equal(s.setPinned({ ...grp, admins: [p.a.uid] }, "m1").ok, true);
  // pinning is allowed where writing is not (the phone's screen offers it in a blocked chat, and the rules allow the field)
  assert.equal(whyNot({ ...chat, blocked: { [p.b.uid]: true } }, p.a.uid), "blocked");
  assert.equal(s.setPinned({ ...chat, blocked: { [p.b.uid]: true } }, "m1").ok, true);
  // a refused write comes back as a rejected promise, so the page can say so
  state.fail = true;
  await assert.rejects(s.setPinned(chat, "m1").committed);
});

// ------------------------------------------------------------------------------------------------------------------------ the star
test("starKey: <chat id>~<message id>", () => {
  assert.equal(starKey("c1", "m9"), "c1~m9");
});

test("starFor: what is kept for each kind, as the phone keeps it", () => {
  const base = { chatId: "c1", chatName: "Bob", fromName: "Bob", mine: false, now: 5000 };
  const long = "y".repeat(700);
  assert.deepEqual(starFor({ ...base, m: msg("text", { id: "m1", ts: 123 }), view: readable("hello") }), {
    key: "c1~m1", chatId: "c1", chatName: "Bob", fromName: "Bob", mine: false, kind: "text", text: "hello", ts: 123, at: 5000,
  });
  assert.equal(starFor({ ...base, m: msg("text"), view: readable(long) }).text.length, STAR_TEXT_MAX, "a text is cut to 600");
  assert.equal(STAR_TEXT_MAX, 600);
  const emoji = "z".repeat(599) + "😀";
  assert.equal(starFor({ ...base, m: msg("text"), view: readable(emoji) }).text, "z".repeat(599), "never half an emoji");
  assert.equal(starFor({ ...base, m: msg("sticker"), view: readable("s".repeat(60)) }).text.length, STAR_STICKER_MAX, "a sticker's id is cut to 40");
  assert.equal(STAR_STICKER_MAX, 40);
  for (const kind of ["img", "voice", "loc", "poll"]) {
    const s = starFor({ ...base, m: msg(kind), view: readable("secret " + kind, kind === "img" ? JPEG : null) });
    assert.equal(s.text, "", kind + " keeps only its kind");
    assert.equal(s.kind, kind);
  }
  assert.equal(starFor({ ...base, mine: true, fromName: "Me", m: msg("text"), view: readable("a") }).mine, true);
  assert.equal(starFor({ ...base, chatName: undefined, fromName: undefined, m: msg("text"), view: readable("a") }).chatName, "");
  assert.equal(starFor({ ...base, m: msg("text", { ts: undefined }), view: readable("a") }).ts, 0);
});

test("starFor: not a deleted message, not an SOS card, a call line, a group line, nor one I cannot open", () => {
  const base = { chatId: "c1", chatName: "Bob", fromName: "Bob", mine: false, now: 5000 };
  assert.equal(starFor({ ...base, m: msg("text", { deleted: true }), view: readable("a") }), null);
  for (const kind of ["sos", "call", "sys"]) assert.equal(starFor({ ...base, m: msg(kind), view: readable("a") }), null, kind);
  assert.equal(starFor({ ...base, m: msg("text"), view: { ...readable("a"), ok: false } }), null);
  assert.equal(starFor({ ...base, m: msg("text"), view: null }), null);
  assert.equal(starFor({ ...base, m: null, view: readable("a") }), null);
});

test("stars.add: a private document in my own space, the text sealed with my own key (sealSelf, label star|<key>); nothing readable is written", async () => {
  const p = await people();
  const { f, log, docs } = fake();
  const st = makeStars({ f, uid: p.a.uid, e2e: p.e.a });
  const star = starFor({ chatId: "c1", chatName: "Bob", fromName: "Bob", mine: false, m: msg("text", { id: "m5", ts: 4242 }), view: readable("remember this"), now: 9999 });
  const r = await st.add(star);
  assert.equal(r.ok, true);
  await r.committed;
  assert.equal(log.length, 1);
  assert.equal(log[0].kind, "set");
  assert.equal(log[0].path, "prefs/" + p.a.uid + "/stars/c1~m5");
  assert.deepEqual(Object.keys(log[0].data).sort(), ["at", "chatId", "chatName", "enc", "fromName", "kind", "mine", "text", "ts"]);
  const d = log[0].data;
  assert.deepEqual([d.chatId, d.chatName, d.fromName, d.mine, d.kind, d.enc, d.ts, d.at], ["c1", "Bob", "Bob", false, "text", true, 4242, 9999]);
  assert.ok(!d.text.includes("remember"), "sealed");
  assert.equal(await p.e.a.openSelf("star|c1~m5", d.text), "remember this", "I open it");
  assert.equal(await p.e.a.openSelf("star|c1~m6", d.text), null, "bound to its star");
  assert.equal(await p.e.b.openSelf("star|c1~m5", d.text), null, "nobody else opens it, not even the person I wrote to");
  assert.equal(await p.e.a.openSelf("star|c1~m5", "!!not base64!!"), null);
  assert.equal(await p.e.a.openSelf("star|c1~m5", ""), null);
  assert.equal(docs.size, 1);
});

test("stars.add: a photo, voice note or place keeps no text, enc is false; a failure to seal writes nothing at all", async () => {
  const p = await people();
  const { f, log } = fake();
  const st = makeStars({ f, uid: p.a.uid, e2e: p.e.a });
  const img = starFor({ chatId: "c1", chatName: "Bob", fromName: "Bob", mine: false, m: msg("img", { id: "m2" }), view: readable("", JPEG), now: 1 });
  await (await st.add(img)).committed;
  assert.deepEqual([log[0].data.text, log[0].data.enc, log[0].data.kind], ["", false, "img"]);
  const broken = { ...p.e.a, sealSelf: async () => { throw new Error("no key"); } };
  const st2 = makeStars({ f, uid: p.a.uid, e2e: broken });
  const text = starFor({ chatId: "c1", chatName: "Bob", fromName: "Bob", mine: false, m: msg("text", { id: "m3" }), view: readable("private"), now: 1 });
  assert.deepEqual(await st2.add(text), { ok: false, why: "noKey" });
  const empty = { ...p.e.a, sealSelf: async () => "" };
  assert.deepEqual(await makeStars({ f, uid: p.a.uid, e2e: empty }).add(text), { ok: false, why: "noKey" });
  assert.equal(log.length, 1, "unlike the phone, the page never stores the text in the clear");
  for (const bad of [null, undefined, {}, { key: "nokeyhere" }, { key: 5 }]) assert.deepEqual(await st.add(bad), { ok: false, why: "no" });
});

test("stars.remove: deletes my copy only", async () => {
  const p = await people();
  const { f, log, state } = fake();
  const st = makeStars({ f, uid: p.a.uid, e2e: p.e.a });
  const r = st.remove("c1~m5");
  assert.equal(r.ok, true);
  await r.committed;
  assert.deepEqual(log, [{ kind: "delete", path: "prefs/" + p.a.uid + "/stars/c1~m5" }]);
  for (const bad of ["", "nokey", null, 3, undefined]) assert.deepEqual(st.remove(bad), { ok: false, why: "no" });
  state.fail = true;
  await assert.rejects(st.remove("c1~m6").committed, "a refused write is a rejected promise: the page says so");
});

test("stars.watch: the newest stars first, at most 300, texts opened (mine and the phone's), a damaged one shows without text, a newer snapshot wins", async () => {
  const p = await people();
  const { f, state } = fake();
  const st = makeStars({ f, uid: p.a.uid, e2e: p.e.a });
  const got = [];
  const errs = [];
  st.watch((l) => got.push(l), (e) => errs.push(e));
  const [{ q, next, err }] = state.snaps;
  assert.deepEqual(q.path, ["prefs", p.a.uid, "stars"]);
  assert.deepEqual(q.cs, [{ t: "order", field: "at", dir: "desc" }, { t: "limit", n: 300 }]);
  assert.equal(STAR_LIMIT, 300);
  const sealed = await p.e.a.sealSelf("star|c1~m1", "from the web");
  const docOf = (id, x) => ({ id, data: () => x });
  await next({
    docs: [
      docOf("c1~m1", { chatId: "c1", chatName: "Bob", fromName: "Bob", mine: false, kind: "text", text: sealed, enc: true, ts: 10, at: 30 }),
      docOf("c1~m2", { chatId: "c1", chatName: "Bob", fromName: "Me", mine: true, kind: "text", text: "clear text from an old phone", enc: false, ts: 11, at: 20 }),
      docOf("c1~m3", { chatId: "c1", chatName: "Bob", fromName: "Bob", kind: "text", text: "AAAA", enc: true, ts: 12, at: 10 }),
      docOf("c1~m4", { chatId: "c2", kind: "img", text: "", enc: false }),
      docOf("c1~m5", { chatId: "c2", kind: 5, text: 6, chatName: 7, ts: "x", at: null }),
    ],
  });
  assert.equal(got.length, 1);
  const l = got[0];
  assert.deepEqual(l.map((x) => x.key), ["c1~m1", "c1~m2", "c1~m3", "c1~m4", "c1~m5"], "in the order the server gave (newest star first)");
  assert.deepEqual(l[0], { key: "c1~m1", chatId: "c1", chatName: "Bob", fromName: "Bob", mine: false, kind: "text", text: "from the web", ts: 10, at: 30 });
  assert.equal(l[1].text, "clear text from an old phone");
  assert.equal(l[1].mine, true);
  assert.equal(l[2].text, "", "damaged: no text, the row is still there");
  assert.deepEqual([l[3].kind, l[3].text, l[3].mine, l[3].ts], ["img", "", false, 0]);
  assert.deepEqual([l[4].kind, l[4].text, l[4].chatName, l[4].ts, l[4].at], ["text", "", "", 0, 0], "odd field types become empty ones");
  // a slow older snapshot does not overwrite a newer one
  const slow = p.make(p.a);
  const gate = {};
  gate.p = new Promise((r) => (gate.go = r));
  const real = slow.openSelf;
  let first = true;
  const e2 = { ...slow, openSelf: async (a, b) => { if (first) { first = false; await gate.p; } return real(a, b); } };
  const st2 = makeStars({ f, uid: p.a.uid, e2e: e2 });
  const seen = [];
  st2.watch((x) => seen.push(x.map((y) => y.key)), () => {});
  const n2 = state.snaps[1].next;
  const s1 = await slow.sealSelf("star|a~1", "one");
  const slowPass = n2({ docs: [docOf("a~1", { text: s1, enc: true, kind: "text" })] });
  await n2({ docs: [docOf("b~2", { kind: "img" })] });
  gate.go();
  await slowPass;
  assert.deepEqual(seen, [["b~2"]], "only the newest snapshot is handed over");
  // an error from the server is passed on
  err(new Error("denied"));
  assert.equal(errs.length, 1);
});

// ------------------------------------------------------------------------------------------------------------------------ the phone's stars
test("the page opens stars that the PHONE's own code sealed (web.stars.fixture.json: E2E.sealSelf cut out of E2E.kt), and nobody else does", async () => {
  const fxs = JSON.parse(readFileSync(new URL("./web.stars.fixture.json", import.meta.url), "utf8"));
  const base = JSON.parse(readFileSync(new URL("./web.fixture.json", import.meta.url), "utf8")).me;
  const key = await crypto.subtle.importKey("pkcs8", fromB64(base.priv), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const mine = makeE2E({ uid: base.uid, key, pub: base.pub, publicOf: async () => null, pins: pins() });
  assert.equal(fxs.rows.length, 4);
  const p = await people();
  for (const row of fxs.rows) {
    assert.equal(await mine.openSelf(row.label, row.sealed), row.text, row.label);
    assert.equal(await mine.openSelf(row.label + "x", row.sealed), null, "bound to its label");
    assert.equal(await p.e.b.openSelf(row.label, row.sealed), null, "not another person's");
    // and the page's own seal of the same text is the same shape the phone's is (iv 12 + text + tag 16), under the same label
    const again = await mine.sealSelf(row.label, row.text);
    assert.equal(fromB64(again).length, fromB64(row.sealed).length);
    assert.notEqual(again, row.sealed, "a fresh iv every time");
    assert.equal(await mine.openSelf(row.label, again), row.text);
  }
  assert.equal(fxs.rows[2].text.length, 600, "the longest star the phone keeps");
  // the watch of the list hands those texts over as they are
  const { f, state } = fake();
  const st = makeStars({ f, uid: base.uid, e2e: mine });
  const got = [];
  st.watch((l) => got.push(l), () => {});
  await state.snaps[0].next({ docs: fxs.rows.map((r) => ({ id: r.label.slice(5), data: () => ({ chatId: "c", kind: "text", text: r.sealed, enc: true, ts: 1, at: 2 }) })) });
  assert.deepEqual(got[0].map((x) => x.text), fxs.rows.map((r) => r.text));
});
