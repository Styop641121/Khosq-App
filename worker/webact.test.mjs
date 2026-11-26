// The web page's actions on a message that is already there (docs/send.js): a reaction, changing my own text, "delete for everyone".
// What is written must be what the phone writes (Repository.react / editMessage / deleteForAll); nothing readable may be written.
// Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { makeE2E, toB64 } from "../docs/e2e.js";
import { makeSender, canEdit, canDelete, isActionable, lastReal, REACTIONS, EDIT_MS, MAX_TEXT } from "../docs/send.js";

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

/** A pretend Firestore that only writes down what it is asked to write. */
function fake() {
  const log = [];
  const ref = (path) => ({ kind: "doc", path, id: path[path.length - 1] });
  const fsM = {
    collection: (db, ...path) => ({ kind: "col", path }),
    doc: (a, ...path) => (a && a.kind === "col" ? ref([...a.path, "auto1"]) : ref(path)),
    serverTimestamp: () => ({ sv: "ts" }),
    increment: (n) => ({ sv: "inc", n }),
    deleteField: () => ({ sv: "del" }),
    writeBatch: () => {
      const ops = [];
      return {
        set(r, d) { ops.push({ kind: "set", path: r.path.join("/"), data: d }); return this; },
        update(r, d) { ops.push({ kind: "update", path: r.path.join("/"), data: d }); return this; },
        commit: async () => void log.push(...ops),
      };
    },
    updateDoc: async (r, d) => void log.push({ kind: "update", path: r.path.join("/"), data: d }),
  };
  return { f: { db: {}, auth: null, fsM }, log };
}
const DEL = { sv: "del" };
const mine = (uid, over = {}) => ({ id: "m1", from: uid, kind: "text", enc: true, ts: Date.now() - 1000, text: "old text", deleted: false, reactions: {}, ...over });

test("which messages can be changed, deleted, reacted to", () => {
  const u = "Aa1";
  const now = 1_000_000_000_000;
  const m = mine(u, { ts: now - 1000 });
  assert.equal(canEdit(m, u, now), true);
  assert.equal(canEdit({ ...m, ts: now - EDIT_MS + 1 }, u, now), true, "14:59.999");
  assert.equal(canEdit({ ...m, ts: now - EDIT_MS }, u, now), false, "15:00 and later: no (the phone's rule is '< 15 minutes')");
  assert.equal(canEdit({ ...m, from: "Bb2" }, u, now), false, "not mine");
  assert.equal(canEdit({ ...m, kind: "img" }, u, now), false, "only a text");
  assert.equal(canEdit({ ...m, enc: false }, u, now), false, "a message that was never sealed is not rewritten by the page");
  assert.equal(canEdit({ ...m, deleted: true }, u, now), false);
  assert.equal(canEdit({ ...m, kind: "call" }, u, now), false);
  assert.equal(canDelete(m, u), true);
  assert.equal(canDelete({ ...m, ts: 1 }, u), true, "deleting has no time limit");
  assert.equal(canDelete({ ...m, from: "Bb2" }, u), false);
  assert.equal(canDelete({ ...m, kind: "img" }, u), true, "any kind of my own message");
  assert.equal(canDelete({ ...m, deleted: true }, u), false);
  for (const k of ["call", "sys"]) assert.equal(isActionable({ ...m, kind: k }), false);
  assert.equal(isActionable(null), false);
  assert.deepEqual(REACTIONS, ["👍", "❤️", "😂", "😮", "😢", "🙏"]);
});

test("the newest message of the kinds that show in the chat list", () => {
  const L = (...kinds) => kinds.map((k, i) => ({ id: "x" + i, kind: k }));
  assert.equal(lastReal(L("text", "img", "call")).id, "x1", "a call line is not a message");
  assert.equal(lastReal(L("text", "sys")).id, "x0");
  assert.equal(lastReal(L("text", "sos")).id, "x0", "an SOS card is not one of the kinds the phone counts");
  assert.equal(lastReal(L("poll", "sticker")).id, "x1");
  assert.equal(lastReal([]), null);
  assert.equal(lastReal(L("sys", "call")), null);
});

test("a reaction: one field of the message, my own, put on or taken off", async () => {
  const { f, log } = fake();
  const s = makeSender({ f, uid: "Aa1", name: "A", e2e: null, worker: "" });
  const chat = { id: "Aa1_Bb2" };
  let r = s.react(chat, mine("Bb2"), "👍");
  assert.equal(r.ok, true);
  await r.committed;
  assert.deepEqual(log[0], { kind: "update", path: "chats/Aa1_Bb2/messages/m1", data: { "reactions.Aa1": "👍" } });
  r = s.react(chat, mine("Bb2", { reactions: { Aa1: "👍", Bb2: "❤️" } }), "👍");
  await r.committed;
  assert.deepEqual(log[1].data, { "reactions.Aa1": DEL }, "the same emoji again takes it back");
  r = s.react(chat, mine("Bb2", { reactions: { Aa1: "👍" } }), "🙏");
  await r.committed;
  assert.deepEqual(log[2].data, { "reactions.Aa1": "🙏" }, "another emoji replaces it");
  assert.equal(s.react(chat, mine("Bb2"), "🤡").ok, false, "only the phone's six");
  assert.equal(s.react(chat, mine("Bb2", { deleted: true }), "👍").ok, false);
  assert.equal(s.react(chat, mine("Bb2", { kind: "sys" }), "👍").ok, false);
  assert.equal(log.length, 3, "nothing else was written");
});

test("changing a text in a one-to-one chat: the same key, the other person opens it, the chat-list line only for the newest", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: dmId(p.a.uid, p.b.uid) };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const seal = await p.e.a.begin(chat.id, "m1", [p.b.uid]);
  const m = mine(p.a.uid, { text: "before" });
  let r = await s.edit(chat, m, "  after Բարև 👋\nline 2  ", true);
  assert.equal(r.ok, true);
  await r.committed;
  assert.equal(log.length, 2);
  assert.equal(log[0].path, "chats/" + chat.id + "/messages/m1");
  assert.deepEqual(Object.keys(log[0].data).sort(), ["edited", "text"]);
  assert.equal(log[0].data.edited, true);
  assert.equal(log[1].path, "chats/" + chat.id);
  assert.deepEqual(Object.keys(log[1].data).sort(), ["lastEnc", "lastKind", "lastText"], "no wraps, no timestamp, no counter: the same as the phone");
  assert.equal(log[1].data.lastEnc, true);
  assert.equal(log[1].data.lastKind, "text");
  const kb = await p.e.b.chatKey(chat.id);
  assert.equal(await p.e.b.openText(kb, chat.id, p.a.uid, "t", log[0].data.text), "after Բարև 👋\nline 2", "trimmed, opened by the other person");
  assert.equal(await p.e.b.openText(kb, chat.id, p.a.uid, "l", log[1].data.lastText), "after Բարև 👋\nline 2");
  assert.equal(await p.e.b.openText(kb, chat.id, p.a.uid, "t", await p.e.a.sealText(seal.key, chat.id, p.a.uid, "t", "x")), "x", "the original key is the same one");
  assert.ok(!JSON.stringify(log).includes("after") && !JSON.stringify(log).includes("line 2"), "nothing readable was written");
  // not the newest message: the message only
  log.length = 0;
  r = await s.edit(chat, m, "again", false);
  await r.committed;
  assert.equal(log.length, 1);
  assert.equal(log[0].path.includes("/messages/"), true);
});

test("changing a text is refused when it must be, and then nothing is written", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: dmId(p.a.uid, p.b.uid) };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const m = mine(p.a.uid, { text: "same text" });
  const why = async (mm, text, last = true, c = chat) => (await s.edit(c, mm, text, last)).why;
  assert.equal(await why(m, "same text"), "same");
  assert.equal(await why(m, "  same text \n"), "same", "the spaces around do not make it a change");
  assert.equal(await why(m, "   "), "empty");
  assert.equal(await why(m, "x".repeat(MAX_TEXT + 1)), "long");
  assert.equal((await s.edit(chat, m, "x".repeat(MAX_TEXT), false)).ok, true, "4000 is the limit, as on the phone");
  log.length = 0;
  assert.equal(await why({ ...m, ts: Date.now() - EDIT_MS - 1000 }, "new"), "late");
  assert.equal(await why({ ...m, from: p.b.uid }, "new"), "no");
  assert.equal(await why({ ...m, enc: false }, "new"), "no", "an unsealed message is not rewritten");
  assert.equal(await why({ ...m, kind: "img" }, "new"), "no");
  assert.equal(await why({ ...m, deleted: true }, "new"), "no");
  // the other person has no key: nothing can be sealed, so nothing is sent (not even in the clear)
  const lonely = { ...p.dir };
  delete lonely[p.b.uid];
  const s2 = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.make(p.a, lonely), worker: "" });
  assert.equal((await s2.edit(chat, m, "new", true)).why, "noKey");
  assert.equal(log.length, 0, "no write in any of these cases");
});

test("changing a text in a group: the message key is made again, so the ORIGINAL wraps still open it", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: "grpTest01", group: true };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const seal = await p.e.a.begin(chat.id, "m1", [p.b.uid, p.c.uid]);
  const original = await p.e.a.sealText(seal.key, chat.id, p.a.uid, "t", "v1");
  const r = await s.edit(chat, mine(p.a.uid, { id: "m1" }), "v2 for all", true);
  await r.committed;
  assert.equal(log.length, 2);
  assert.deepEqual(Object.keys(log[1].data).sort(), ["lastEnc", "lastKind", "lastText"], "the wraps in the chat document stay");
  for (const who of ["b", "c"]) {
    const k = await p.e[who].groupKey(chat.id, p.a.uid, seal.wraps);
    assert.equal(await p.e[who].openText(k, chat.id, p.a.uid, "t", original), "v1");
    assert.equal(await p.e[who].openText(k, chat.id, p.a.uid, "t", log[0].data.text), "v2 for all", who + " opens the changed text with the old wrap");
    assert.equal(await p.e[who].openLast(chat.id, p.a.uid, log[1].data.lastText, seal.wraps), "v2 for all");
  }
  assert.ok(!JSON.stringify(log).includes("v2 for all"));
});

test("delete for everyone: the phone's write, plus the chat-list line when it is the newest text", async () => {
  const { f, log } = fake();
  const s = makeSender({ f, uid: "Aa1", name: "A", e2e: null, worker: "" });
  const chat = { id: "Aa1_Bb2" };
  // an older message: the message only
  let r = s.remove(chat, mine("Aa1"), false);
  assert.equal(r.ok, true);
  await r.committed;
  assert.equal(log.length, 1);
  assert.deepEqual(log[0], { kind: "update", path: "chats/Aa1_Bb2/messages/m1", data: { deleted: true, text: "", data: DEL, reply: DEL } });
  // the newest text: its sealed line must not stay readable in the chat list
  log.length = 0;
  r = s.remove(chat, mine("Aa1"), true);
  await r.committed;
  assert.equal(log.length, 2);
  assert.deepEqual(log[1], { kind: "update", path: "chats/Aa1_Bb2", data: { lastText: "", lastEnc: false, lastW: DEL, lastKind: "deleted" } });
  // the newest message is a photo: its line is only a word, nothing to clear
  log.length = 0;
  r = s.remove(chat, mine("Aa1", { kind: "img" }), true);
  await r.committed;
  assert.equal(log.length, 1);
  // not mine, already deleted, a call line
  assert.equal(s.remove(chat, mine("Bb2"), true).ok, false);
  assert.equal(s.remove(chat, mine("Aa1", { deleted: true }), true).ok, false);
  assert.equal(s.remove(chat, mine("Aa1", { kind: "call" }), true).ok, false);
  assert.equal(log.length, 1);
});

test("a message that is still being sent is tracked until the server has it", async () => {
  const p = await people();
  const { f } = fake();
  let release;
  f.fsM.writeBatch = () => {
    const b = { set() { return b; }, update() { return b; }, commit: () => new Promise((res) => (release = res)) };
    return b;
  };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const chat = { id: dmId(p.a.uid, p.b.uid), members: [p.a.uid, p.b.uid] };
  const r = await s.sendText(chat, "hello", null);
  assert.equal(r.ok, true);
  assert.equal(s.sending.has(r.id), true, "while the server has not taken it");
  release();
  await r.taken;
  assert.equal(s.sending.has(r.id), false);
});

// ---------------------------------------------------------------------------------------------- the push for a reaction
/** A sender whose reaction write is held until the test lets it go, and whose pushes are written down. */
function pushRig({ reject = false, fetchFails = false } = {}) {
  const { f, log } = fake();
  const asked = [];
  let gate;
  const held = new Promise((res) => (gate = res));
  f.fsM.updateDoc = async (r, d) => {
    await held;
    if (reject) throw new Error("refused");
    log.push({ kind: "update", path: r.path.join("/"), data: d });
  };
  f.auth = { currentUser: { getIdToken: async () => "ID-TOKEN" } };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    if (fetchFails) throw new Error("offline");
    asked.push({ url: String(url), headers: opts.headers, body: JSON.parse(opts.body) });
    return new Response("ok", { status: 200 });
  };
  const done = () => (globalThis.fetch = realFetch);
  return { f, log, asked, let: gate, done };
}

test("a reaction on somebody else's message asks for a push, once the server has it, with the emoji and nothing else", async () => {
  const rig = pushRig();
  try {
    const s = makeSender({ f: rig.f, uid: "Aa1", name: "Ani", e2e: null, worker: "https://w.example/" });
    const r = s.react({ id: "Aa1_Bb2" }, mine("Bb2"), "😂");
    await new Promise((x) => setTimeout(x, 10));
    assert.equal(rig.asked.length, 0, "not before the server has the reaction");
    rig.let();
    await r.committed;
    await r.pushed;
    assert.equal(rig.asked.length, 1);
    const q = rig.asked[0];
    assert.equal(q.url, "https://w.example/");
    assert.equal(q.headers.Authorization, "Bearer ID-TOKEN");
    assert.deepEqual(q.body, { chatId: "Aa1_Bb2", type: "react", title: "Ani", body: "😂", msgId: "m1" });
  } finally {
    rig.done();
  }
});

test("a reaction in a group: the title is the group's, and the message is named so the Worker can find its author", async () => {
  const rig = pushRig();
  try {
    const s = makeSender({ f: rig.f, uid: "Aa1", name: "Ani", e2e: null, worker: "https://w.example/" });
    const r = s.react({ id: "grp1", group: true, title: "Family" }, mine("Cc3", { id: "mm7" }), "❤️");
    rig.let();
    await r.pushed;
    assert.deepEqual(rig.asked[0].body, { chatId: "grp1", type: "react", title: "Family", body: "❤️", msgId: "mm7" });
  } finally {
    rig.done();
  }
});

test("another emoji in place of mine asks again; taking mine back, my own message and my saved messages ask for nothing", async () => {
  const rig = pushRig();
  try {
    rig.let();
    const s = makeSender({ f: rig.f, uid: "Aa1", name: "Ani", e2e: null, worker: "https://w.example/" });
    const chat = { id: "Aa1_Bb2" };
    await s.react(chat, mine("Bb2", { reactions: { Aa1: "👍" } }), "🙏").pushed;
    assert.equal(rig.asked.length, 1, "a replaced reaction is a new one");
    assert.equal(rig.asked[0].body.body, "🙏");
    await s.react(chat, mine("Bb2", { reactions: { Aa1: "👍" } }), "👍").pushed;
    assert.equal(rig.asked.length, 1, "taken back: nothing");
    await s.react(chat, mine("Aa1"), "👍").pushed;
    assert.equal(rig.asked.length, 1, "my own message: nothing");
    await s.react({ id: "Aa1_Aa1", saved: true }, mine("Aa1"), "👍").pushed;
    await s.react({ id: "Aa1_Aa1", saved: true }, mine("Bb2"), "👍").pushed; // nobody else is in that chat, whatever the message says
    assert.equal(rig.asked.length, 1, "saved messages: nothing");
  } finally {
    rig.done();
  }
});

test("a refused reaction asks for no push and the page still sees the refusal; a failing push breaks nothing", async () => {
  let rig = pushRig({ reject: true });
  try {
    const s = makeSender({ f: rig.f, uid: "Aa1", name: "Ani", e2e: null, worker: "https://w.example/" });
    const r = s.react({ id: "Aa1_Bb2" }, mine("Bb2"), "👍");
    rig.let();
    await assert.rejects(r.committed, /refused/);
    await r.pushed; // never rejects
    assert.equal(rig.asked.length, 0);
  } finally {
    rig.done();
  }
  rig = pushRig({ fetchFails: true });
  try {
    const s = makeSender({ f: rig.f, uid: "Aa1", name: "Ani", e2e: null, worker: "https://w.example/" });
    const r = s.react({ id: "Aa1_Bb2" }, mine("Bb2"), "👍");
    rig.let();
    await r.committed;
    await r.pushed;
    assert.equal(rig.log.length, 1, "the reaction itself is written");
  } finally {
    rig.done();
  }
  // no Worker address: no push, no request
  rig = pushRig();
  try {
    const s = makeSender({ f: rig.f, uid: "Aa1", name: "Ani", e2e: null, worker: "" });
    const r = s.react({ id: "Aa1_Bb2" }, mine("Bb2"), "👍");
    rig.let();
    await r.pushed;
    assert.equal(rig.asked.length, 0);
  } finally {
    rig.done();
  }
});
