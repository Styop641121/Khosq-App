// A photo and a voice note written by the web page (docs/send.js sendMedia, docs/media.js). What is written must be what the phone writes
// (Repository.sendMedia): the bytes sealed with the message key, field "d", and nothing readable. Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { makeE2E, toB64 } from "../docs/e2e.js";
import { makeSender, MAX_MEDIA } from "../docs/send.js";
import { fitSide, waveOf, voiceMime, PHOTO_SIDE, VOICE_CAP } from "../docs/media.js";

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

/** A pretend Firestore that writes down what it is asked to write; a Bytes value holds its bytes. */
function fake() {
  const log = [];
  const ref = (path) => ({ kind: "doc", path, id: path[path.length - 1] });
  let n = 0;
  const fsM = {
    collection: (db, ...path) => ({ kind: "col", path }),
    doc: (a, ...path) => (a && a.kind === "col" ? ref([...a.path, "auto" + ++n]) : ref(path)),
    serverTimestamp: () => ({ sv: "ts" }),
    increment: (k) => ({ sv: "inc", n: k }),
    deleteField: () => ({ sv: "del" }),
    Bytes: { fromUint8Array: (u8) => ({ isBytes: true, u8 }) },
    writeBatch: () => {
      const ops = [];
      return {
        set(r, d) { ops.push({ kind: "set", path: r.path.join("/"), data: d }); return this; },
        update(r, d) { ops.push({ kind: "update", path: r.path.join("/"), data: d }); return this; },
        commit: async () => void log.push(...ops),
      };
    },
    updateDoc: async () => {},
  };
  return { f: { db: {}, auth: { currentUser: { getIdToken: async () => "ID" } }, fsM }, log };
}
const DEL = { sv: "del" };
const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, ...Array.from({ length: 300 }, (_, i) => (i * 7 + 3) & 255), 0xff, 0xd9]);
const M4A = Uint8Array.from([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, ...Array.from({ length: 500 }, (_, i) => (i * 13 + 5) & 255)]);
const WAVE = "5844889758577569678469996968976537893878";
const has = (hay, needle) => Buffer.from(hay).includes(Buffer.from(needle));

test("a photo in a one-to-one chat: the same fields as the phone, the bytes sealed, the other person opens them", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: dmId(p.a.uid, p.b.uid) };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const r = await s.sendMedia(chat, "img", JPEG, 0, "");
  assert.equal(r.ok, true);
  await r.taken;
  assert.equal(log.length, 2, "the message and the chat's line, in one batch");
  const [msg, line] = log;
  assert.equal(msg.kind, "set");
  assert.match(msg.path, /^chats\/.+\/messages\/auto\d+$/);
  assert.deepEqual(Object.keys(msg.data).sort(), ["data", "dur", "e", "from", "kind", "text", "ts"]);
  assert.equal(msg.data.from, p.a.uid);
  assert.equal(msg.data.text, "");
  assert.equal(msg.data.kind, "img");
  assert.equal(msg.data.dur, 0);
  assert.equal(msg.data.e, true);
  assert.deepEqual(msg.data.ts, { sv: "ts" });
  const sealed = msg.data.data.u8;
  assert.equal(sealed.length, 12 + JPEG.length + 16, "iv + cipher text + tag, as the phone's");
  assert.ok(!has(sealed, JPEG.slice(0, 12)), "the picture is not in the clear");
  assert.deepEqual(line.data, {
    lastText: "📷",
    lastEnc: false,
    lastW: DEL,
    lastKind: "img",
    lastTs: line.data.lastTs,
    lastFrom: p.a.uid,
    ["unread." + p.b.uid]: { sv: "inc", n: 1 },
  });
  assert.ok(Math.abs(line.data.lastTs - Date.now()) < 5000);
  // Bob opens it with his own key and the page's own code, I open it too
  const kb = await p.e.b.chatKey(chat.id);
  assert.deepEqual(await p.e.b.openBytes(kb, chat.id, p.a.uid, "d", sealed), JPEG);
  assert.deepEqual(await p.e.a.openBytes(await p.e.a.chatKey(chat.id), chat.id, p.a.uid, "d", sealed), JPEG);
  // and it is bound to its chat, its sender and its field
  assert.equal(await p.e.b.openBytes(kb, chat.id, p.a.uid, "t", sealed), null);
  assert.equal(await p.e.b.openBytes(kb, chat.id, p.b.uid, "d", sealed), null);
  assert.equal(await p.e.b.openBytes(kb, dmId(p.a.uid, p.c.uid), p.a.uid, "d", sealed), null);
});

test("a voice note: the length and the bars are written, only valid bars", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: dmId(p.a.uid, p.b.uid) };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  let r = await s.sendMedia(chat, "voice", M4A, 7, WAVE);
  await r.taken;
  assert.deepEqual(Object.keys(log[0].data).sort(), ["data", "dur", "e", "from", "kind", "text", "ts", "wave"]);
  assert.equal(log[0].data.kind, "voice");
  assert.equal(log[0].data.dur, 7);
  assert.equal(log[0].data.wave, WAVE);
  assert.equal(log[1].data.lastText, "🎤");
  assert.equal(log[1].data.lastKind, "voice");
  assert.deepEqual(await p.e.b.openBytes(await p.e.b.chatKey(chat.id), chat.id, p.a.uid, "d", log[0].data.data.u8), M4A);
  // bars that are not 40 digits 1..9 are left out (the phone's player would not draw them); the length is a whole number of seconds
  for (const bad of ["", "123", "0".repeat(40), "a".repeat(40), WAVE + "5", null, undefined]) {
    log.length = 0;
    r = await s.sendMedia(chat, "voice", M4A, 3, bad);
    await r.taken;
    assert.equal("wave" in log[0].data, false, JSON.stringify(bad));
  }
  for (const [dur, want] of [[2.9, 2], [0, 0], [-4, 0], [99999, 3600], ["x", 0], [12, 12]]) {
    log.length = 0;
    await (await s.sendMedia(chat, "voice", M4A, dur, WAVE)).taken;
    assert.equal(log[0].data.dur, want, String(dur));
  }
  // a photo has no length and no bars, whatever is passed
  log.length = 0;
  await (await s.sendMedia(chat, "img", JPEG, 9, WAVE)).taken;
  assert.equal(log[0].data.dur, 0);
  assert.equal("wave" in log[0].data, false);
});

test("a photo in a group: the key is the message's own, wrapped for every member; each of them opens it", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: "grp1", group: true, members: [p.a.uid, p.b.uid, p.c.uid] };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  const r = await s.sendMedia(chat, "img", JPEG, 0, "");
  await r.taken;
  const [msg, line] = log;
  assert.deepEqual(Object.keys(msg.data.w).sort(), [p.a.uid, p.b.uid, p.c.uid].sort());
  assert.equal(msg.data.data.u8.length, 12 + JPEG.length + 16);
  for (const who of ["a", "b", "c"]) {
    const k = await p.e[who].groupKey(chat.id, p.a.uid, msg.data.w);
    assert.ok(k, who);
    assert.deepEqual(await p.e[who].openBytes(k, chat.id, p.a.uid, "d", msg.data.data.u8), JPEG, who);
  }
  assert.equal(line.data.lastEnc, false);
  assert.deepEqual(line.data.lastW, DEL, "no wraps in the line: it is not sealed text");
  assert.deepEqual(line.data["unread." + p.b.uid], { sv: "inc", n: 1 });
  assert.deepEqual(line.data["unread." + p.c.uid], { sv: "inc", n: 1 });
  assert.equal("unread." + p.a.uid in line.data, false);
});

test("saved messages: only I can open it, no counter, no push", async () => {
  const p = await people();
  const { f, log } = fake();
  const asked = [];
  const real = globalThis.fetch;
  globalThis.fetch = async (u, o) => (asked.push([String(u), o]), new Response("ok"));
  try {
    const chat = { id: dmId(p.a.uid, p.a.uid), saved: true, members: [p.a.uid] };
    const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "https://w.example/" });
    const r = await s.sendMedia(chat, "img", JPEG, 0, "");
    await r.committed;
    assert.deepEqual(Object.keys(log[1].data).filter((k) => k.startsWith("unread")), []);
    assert.equal(asked.length, 0);
    const k = await p.e.a.chatKey(chat.id);
    assert.deepEqual(await p.e.a.openBytes(k, chat.id, p.a.uid, "d", log[0].data.data.u8), JPEG);
  } finally {
    globalThis.fetch = real;
  }
});

test("the push for a photo is the push for a message: after the server has it, the name, no text", async () => {
  const p = await people();
  const { f } = fake();
  const asked = [];
  const real = globalThis.fetch;
  globalThis.fetch = async (u, o) => (asked.push({ url: String(u), headers: o.headers, body: JSON.parse(o.body) }), new Response("ok"));
  try {
    const chat = { id: dmId(p.a.uid, p.b.uid) };
    const s = makeSender({ f, uid: p.a.uid, name: "Ani", e2e: p.e.a, worker: "https://w.example/" });
    let let_go;
    const held = new Promise((res) => (let_go = res));
    const wb = f.fsM.writeBatch;
    f.fsM.writeBatch = () => {
      const b = wb();
      const commit = b.commit;
      b.commit = async () => (await held, commit());
      return b;
    };
    const r = await s.sendMedia(chat, "img", JPEG, 0, "");
    assert.equal(s.sending.has(r.id), true, "tracked as 'sending' until the server has it");
    await new Promise((x) => setTimeout(x, 10));
    assert.equal(asked.length, 0, "no push before the server has the message");
    let_go();
    await r.committed;
    assert.equal(s.sending.has(r.id), false);
    f.fsM.writeBatch = wb;
    assert.equal(asked.length, 1);
    assert.deepEqual(asked[0].body, { chatId: chat.id, type: "msg", title: "Ani", body: "" });
    assert.equal(asked[0].headers.Authorization, "Bearer ID");
    // a group: the group's title
    const g = { id: "grp1", group: true, title: "Family", members: [p.a.uid, p.b.uid, p.c.uid] };
    await (await s.sendMedia(g, "voice", M4A, 4, WAVE)).committed;
    assert.deepEqual(asked[1].body, { chatId: "grp1", type: "msg", title: "Family", body: "" });
  } finally {
    globalThis.fetch = real;
  }
});

test("what is refused writes nothing: a wrong kind, no bytes, too big, no key for somebody, a closed chat", async () => {
  const p = await people();
  const { f, log } = fake();
  const chat = { id: dmId(p.a.uid, p.b.uid) };
  const s = makeSender({ f, uid: p.a.uid, name: "A", e2e: p.e.a, worker: "" });
  assert.equal((await s.sendMedia(chat, "text", JPEG, 0, "")).ok, false);
  assert.equal((await s.sendMedia(chat, "sticker", JPEG, 0, "")).ok, false);
  assert.equal((await s.sendMedia(chat, "img", "not bytes", 0, "")).ok, false);
  assert.equal((await s.sendMedia(chat, "img", [1, 2, 3], 0, "")).ok, false);
  assert.equal((await s.sendMedia(chat, "img", new Uint8Array(0), 0, "")).why, "empty");
  assert.equal((await s.sendMedia(chat, "img", new Uint8Array(MAX_MEDIA + 1), 0, "")).why, "big");
  assert.equal((await s.sendMedia(chat, "voice", new Uint8Array(MAX_MEDIA + 1), 5, WAVE)).why, "big");
  assert.equal((await s.sendMedia({ ...chat, blocked: { x: true } }, "img", JPEG, 0, "")).why, "blocked");
  assert.equal((await s.sendMedia({ ...chat, status: "pending", requester: p.b.uid }, "img", JPEG, 0, "")).why, "pending");
  assert.equal((await s.sendMedia({ id: "grp1", group: true, members: [p.a.uid, p.b.uid], sendMode: "admins", admins: [p.b.uid] }, "img", JPEG, 0, "")).why, "admins");
  assert.equal((await s.sendMedia({ id: "weird" }, "img", JPEG, 0, "")).why, "noChat");
  // somebody who has no key (an old version of the app): nothing is sent, not even in the clear
  const noKeyB = p.make(p.a, { [p.a.uid]: p.a.pub });
  const s2 = makeSender({ f, uid: p.a.uid, name: "A", e2e: noKeyB, worker: "" });
  assert.equal((await s2.sendMedia(chat, "img", JPEG, 0, "")).why, "noKey");
  assert.equal((await s2.sendMedia({ id: "grp1", group: true, members: [p.a.uid, p.b.uid, p.c.uid] }, "img", JPEG, 0, "")).why, "noKey");
  assert.equal(log.length, 0, "nothing was written");
  // the limit itself is allowed
  const ok = await s.sendMedia(chat, "img", new Uint8Array(MAX_MEDIA), 0, "");
  assert.equal(ok.ok, true);
});

test("the size of a picture: the longer side at most 1280, the shape kept, never made bigger", () => {
  assert.equal(PHOTO_SIDE, 1280);
  assert.deepEqual(fitSide(4000, 3000, 1280), { w: 1280, h: 960 });
  assert.deepEqual(fitSide(3000, 4000, 1280), { w: 960, h: 1280 });
  assert.deepEqual(fitSide(1280, 1280, 1280), { w: 1280, h: 1280 });
  assert.deepEqual(fitSide(800, 600, 1280), { w: 800, h: 600 });
  assert.deepEqual(fitSide(10000, 1, 1280), { w: 1280, h: 1 }, "a thin strip keeps one pixel");
  assert.deepEqual(fitSide(1281, 1000, 1280), { w: 1280, h: 999 });
});

test("the loudness bars are the phone's: the answers of the phone's own Kotlin code for 13 recordings", () => {
  const cases = JSON.parse(readFileSync(new URL("./web.media.fixture.json", import.meta.url), "utf8"));
  assert.ok(cases.length >= 13);
  for (const c of cases) assert.equal(waveOf(c.levels), c.wave, c.name);
  assert.equal(waveOf(null), "");
  // every bar is a digit 1..9 and there are 40
  for (const c of cases) if (c.levels.length) assert.match(waveOf(c.levels), /^[1-9]{40}$/);
});

test("the voice format: only what the phone plays (AAC in MP4)", () => {
  const MR = (...ok) => ({ isTypeSupported: (m) => ok.includes(m) });
  assert.equal(voiceMime(MR("audio/mp4;codecs=mp4a.40.2", "audio/mp4")), "audio/mp4;codecs=mp4a.40.2");
  assert.equal(voiceMime(MR("audio/mp4")), "audio/mp4");
  assert.equal(voiceMime(MR("audio/webm;codecs=opus", "audio/webm")), "", "a browser that records only WebM cannot make what the phone plays");
  assert.equal(voiceMime(MR()), "");
  assert.equal(voiceMime(null), "");
  assert.equal(voiceMime({}), "");
  assert.equal(voiceMime({ isTypeSupported: () => { throw new Error("boom"); } }), "");
  assert.ok(VOICE_CAP < MAX_MEDIA, "the recording stops before the limit of a message");
});
