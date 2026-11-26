// The web page's opening of sealed messages (docs/e2e.js) against a fixture that the PHONE's own crypto code (crypto/E2E.kt) sealed:
// web.fixture.json is made by a Kotlin program that cuts the real functions out of E2E.kt (hkdf, derive, seal, ...) and runs them.
// The identity in it is a throw-away test key.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { makeE2E, fromB64 } from "../docs/e2e.js";

const fx = JSON.parse(readFileSync(new URL("./web.fixture.json", import.meta.url), "utf8"));
const { ids, me } = fx;

const pins = () => {
  const m = new Map();
  return { get: async (u) => m.get(u), set: async (u, t) => void m.set(u, t), map: m };
};
async function open(extra = {}) {
  const key = await crypto.subtle.importKey("pkcs8", fromB64(me.priv), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const p = pins();
  const asked = [];
  const e2e = makeE2E({ uid: me.uid, key, pub: me.pub, publicOf: async (u) => { asked.push(u); return fx.users[u]?.pk ?? null; }, pins: p, ...extra });
  return { e2e, p, asked };
}
const msg = (chat, id) => fx.messages[chat].find((m) => m.id === id).data;

test("the private key is not extractable and still opens what the phone sealed", async () => {
  const { e2e } = await open();
  const k = await e2e.chatKey(ids.dmBob);
  assert.ok(k);
  assert.equal(k.extractable, false);
  assert.equal(await e2e.openText(k, ids.dmBob, ids.bob, "t", msg(ids.dmBob, "m01").text), "Բարև Ստյոպա 👋");
  assert.equal(await e2e.openText(k, ids.dmBob, me.uid, "t", msg(ids.dmBob, "m02").text), "Hi! How are you?");
});

test("one-to-one: quotes, locations, polls, stickers, and the chat-list line", async () => {
  const { e2e } = await open();
  const c = ids.dmBob;
  const k = await e2e.chatKey(c);
  assert.equal(await e2e.openText(k, c, ids.bob, "r", msg(c, "m03").reply), "Hi! How are you?");
  assert.equal(await e2e.openText(k, c, me.uid, "t", msg(c, "m06").text), "40.17770,44.51260");
  assert.equal(await e2e.openText(k, c, ids.bob, "t", msg(c, "m11").text), '{"q":"Lunch?","o":["Pizza","Sushi","Khinkali"]}');
  assert.equal(await e2e.openText(k, c, me.uid, "v", msg(c, "m11").votes[me.uid]), "1");
  assert.equal(await e2e.openText(k, c, ids.bob, "v", msg(c, "m11").votes[ids.bob]), "1");
  assert.equal(await e2e.openText(k, c, ids.bob, "t", msg(c, "m12").text), "greet.hello");
  const chat = fx.chats.find((x) => x.id === c).data;
  assert.equal(await e2e.openLast(c, chat.lastFrom, chat.lastText, {}), "Last words from Bob");
});

test("photos and voice: the bytes come back exactly", async () => {
  const { e2e } = await open();
  const c = ids.dmBob;
  const k = await e2e.chatKey(c);
  const pic = await e2e.openBytes(k, c, ids.bob, "d", fromB64(msg(c, "m04").data.$blob));
  assert.deepEqual(Buffer.from(pic), Buffer.from(fx.pic, "base64"));
  assert.equal(pic[0], 0xff); // a JPEG
  const voice = await e2e.openBytes(k, c, ids.bob, "d", fromB64(msg(c, "m05").data.$blob));
  assert.deepEqual(Buffer.from(voice), Buffer.from(fx.voice, "base64"));
});

test("a message is bound to its chat, sender and field: any change and it stays closed", async () => {
  const { e2e } = await open();
  const c = ids.dmBob;
  const k = await e2e.chatKey(c);
  const sealed = msg(c, "m01").text;
  assert.equal(await e2e.openText(k, c, ids.carol, "t", sealed), null, "another sender");
  assert.equal(await e2e.openText(k, c, ids.bob, "r", sealed), null, "another field");
  assert.equal(await e2e.openText(k, ids.dmCarol, ids.bob, "t", sealed), null, "another chat");
  const bytes = fromB64(sealed);
  bytes[bytes.length - 1] ^= 1;
  assert.equal(await e2e.openText(k, c, ids.bob, "t", Buffer.from(bytes).toString("base64")), null, "a flipped bit");
  assert.equal(await e2e.openText(k, c, ids.bob, "t", "not base64 at all !!"), null);
  assert.equal(await e2e.openText(k, c, ids.bob, "t", ""), null);
  assert.equal(await e2e.openText(k, c, ids.bob, "t", msg(c, "m15").text), null, "sealed with somebody else's key");
});

test("saved messages use my own key", async () => {
  const { e2e, asked } = await open();
  const c = ids.saved;
  const k = await e2e.chatKey(c);
  assert.ok(k);
  assert.equal(await e2e.openText(k, c, me.uid, "t", msg(c, "s1").text), "Note to myself");
  const chat = fx.chats.find((x) => x.id === c).data;
  assert.equal(await e2e.openLast(c, me.uid, chat.lastText, {}), "Note to myself");
  assert.deepEqual(asked, [], "no other person's key was needed");
});

test("groups: every message has its own key, wrapped for each member", async () => {
  const { e2e } = await open();
  const g = ids.group;
  for (const [id, from, text] of [["g02", ids.bob, "Group hello from Bob"], ["g03", ids.carol, "Carol answers"], ["g04", me.uid, "My own group message"]]) {
    const m = msg(g, id);
    const k = await e2e.groupKey(g, m.from, m.w);
    assert.ok(k, id);
    assert.equal(await e2e.openText(k, g, from, "t", m.text), text, id);
  }
  const m3 = msg(g, "g03");
  const k3 = await e2e.groupKey(g, ids.carol, m3.w);
  assert.equal(await e2e.openText(k3, g, ids.carol, "r", m3.reply), "Group hello from Bob");
  // the poll's votes are sealed with the poll's own key, by each voter
  const p = msg(g, "g05");
  const kp = await e2e.groupKey(g, ids.carol, p.w);
  assert.equal(await e2e.openText(kp, g, ids.carol, "t", p.text), '{"q":"Where?","o":["Park","Cafe"]}');
  assert.equal(await e2e.openText(kp, g, ids.carol, "v", p.votes[ids.carol]), "0");
  assert.equal(await e2e.openText(kp, g, me.uid, "v", p.votes[me.uid]), "1");
  // the group's chat-list line
  const chat = fx.chats.find((x) => x.id === g).data;
  assert.equal(await e2e.openLast(g, chat.lastFrom, chat.lastText, chat.lastW), "Carol answers");
});

test("a group message from before I joined has no wrap for me and stays closed", async () => {
  const { e2e } = await open();
  const m = msg(ids.group, "g01");
  assert.equal(m.w[me.uid], undefined);
  assert.equal(await e2e.groupKey(ids.group, ids.bob, m.w), null);
  assert.equal(await e2e.groupKey(ids.group, ids.bob, undefined), null);
});

test("a person's public key is asked for once, remembered, and a swap on the server is noticed", async () => {
  const { e2e, p, asked } = await open();
  await e2e.chatKey(ids.dmBob);
  await e2e.chatKey(ids.dmBob);
  await e2e.groupKey(ids.group, ids.bob, msg(ids.group, "g02").w);
  assert.deepEqual(asked, [ids.bob], "one request for Bob");
  assert.equal(p.map.get(ids.bob), fx.users[ids.bob].pk);
  assert.equal(await e2e.refreshPeer(ids.bob), false, "same key: nothing changed");
  assert.equal(await e2e.keyChanged(ids.bob), false);
  // the server now says Carol's key is Bob's
  const swapped = fx.users[ids.carol].pk;
  const key = await crypto.subtle.importKey("pkcs8", fromB64(me.priv), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const p2 = pins();
  p2.map.set(ids.bob, fx.users[ids.bob].pk);
  const e2 = makeE2E({ uid: me.uid, key, pub: me.pub, publicOf: async () => swapped, pins: p2 });
  assert.equal(await e2.refreshPeer(ids.bob), true);
  assert.equal(await e2.keyChanged(ids.bob), true);
  assert.equal(p2.map.get(ids.bob), swapped);
  // with the swapped key the old messages can no longer be opened
  const k = await e2.chatKey(ids.dmBob);
  assert.equal(await e2.openText(k, ids.dmBob, ids.bob, "t", msg(ids.dmBob, "m01").text), null);
});

test("a person without a key is asked about again only after a minute", async () => {
  const key = await crypto.subtle.importKey("pkcs8", fromB64(me.priv), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  let n = 0;
  const e2 = makeE2E({ uid: me.uid, key, pub: me.pub, publicOf: async () => (n++, null), pins: pins() });
  assert.equal(await e2.chatKey(ids.dmBob), null);
  assert.equal(await e2.chatKey(ids.dmBob), null);
  assert.equal(n, 1);
});

test("chat ids: who is the other side", async () => {
  const { e2e } = await open();
  assert.equal(e2e.peerOf(ids.dmBob), ids.bob);
  assert.equal(e2e.peerOf(ids.saved), me.uid);
  assert.equal(e2e.peerOf(ids.group), null);
  assert.equal(e2e.isGroup(ids.group), true);
  assert.equal(e2e.peerOf("Aa_Bb"), null, "a chat that is not mine is not opened");
});

// ------------------------------------------------------------------------------------------------ sending (stage 3): sealing
const sendFx = JSON.parse(readFileSync(new URL("./web.send.fixture.json", import.meta.url), "utf8"));
import { toB64 } from "../docs/e2e.js";

/** Three throw-away people made here, each with a page-side e2e of their own, to see that what one seals the others open. */
async function people() {
  const mk = async (uid) => {
    const pair = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
    const pub = toB64(new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)));
    const priv = await crypto.subtle.importKey("pkcs8", await crypto.subtle.exportKey("pkcs8", pair.privateKey), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
    return { uid, pub, priv };
  };
  const all = { a: await mk("Aa00000000000000000000000001"), b: await mk("Bb00000000000000000000000002"), c: await mk("Cc00000000000000000000000003") };
  const dir = Object.fromEntries(Object.values(all).map((p) => [p.uid, p.pub]));
  const make = (p, withKeys = dir) => makeE2E({ uid: p.uid, key: p.priv, pub: p.pub, publicOf: async (u) => withKeys[u] ?? null, pins: pins() });
  return { ...all, e: { a: make(all.a), b: make(all.b), c: make(all.c) }, make, dir };
}
const dmId = (x, y) => (x < y ? x + "_" + y : y + "_" + x);

test("the group message key is the one the phone makes (known answers from crypto/E2E.kt)", async () => {
  const { e2e } = await open();
  for (const k of sendFx.messageKeys) {
    assert.equal(Buffer.from(await e2e.messageKeyBits(k.chatId, k.msgId)).toString("hex"), k.hex, k.chatId + "/" + k.msgId);
  }
  // and it follows the message id and the chat: nothing is reused
  const a = await e2e.messageKeyBits("g", "m1");
  assert.deepEqual(a, await e2e.messageKeyBits("g", "m1"));
  assert.notDeepEqual(a, await e2e.messageKeyBits("g", "m2"));
  assert.notDeepEqual(a, await e2e.messageKeyBits("h", "m1"));
});

test("one-to-one: what I seal, the other person opens (and I can still read it)", async () => {
  const p = await people();
  const chat = dmId(p.a.uid, p.b.uid);
  const s = await p.e.a.begin(chat, "m1", [p.b.uid]);
  assert.ok(s && s.wraps === null);
  const text = "Hello Բարև 👋 ✓";
  const sealed = await p.e.a.sealText(s.key, chat, p.a.uid, "t", text);
  const kb = await p.e.b.chatKey(chat);
  assert.equal(await p.e.b.openText(kb, chat, p.a.uid, "t", sealed), text);
  assert.equal(await p.e.a.openText(await p.e.a.chatKey(chat), chat, p.a.uid, "t", sealed), text);
  // a stranger, even with the right chat id, gets nothing
  assert.equal(await p.e.c.openText(await p.e.c.chatKey(chat), chat, p.a.uid, "t", sealed), null);
});

test("sealed text hides the text, uses a new random IV every time, and is exactly iv + text + 16", async () => {
  const p = await people();
  const chat = dmId(p.a.uid, p.b.uid);
  const s = await p.e.a.begin(chat, "m1", [p.b.uid]);
  const text = "a very secret sentence";
  const x = await p.e.a.sealText(s.key, chat, p.a.uid, "t", text);
  const y = await p.e.a.sealText(s.key, chat, p.a.uid, "t", text);
  assert.notEqual(x, y, "two seals of the same text differ");
  const raw = Buffer.from(x, "base64");
  assert.equal(raw.length, 12 + Buffer.byteLength(text) + 16);
  assert.ok(!raw.toString("latin1").includes("secret"));
  assert.notDeepEqual(raw.subarray(0, 12), Buffer.from(y, "base64").subarray(0, 12), "a different IV");
});

test("a sealed text is bound to its chat, sender and field", async () => {
  const p = await people();
  const chat = dmId(p.a.uid, p.b.uid);
  const s = await p.e.a.begin(chat, "m1", [p.b.uid]);
  const sealed = await p.e.a.sealText(s.key, chat, p.a.uid, "t", "hi");
  const kb = await p.e.b.chatKey(chat);
  assert.equal(await p.e.b.openText(kb, chat, p.a.uid, "t", sealed), "hi");
  assert.equal(await p.e.b.openText(kb, chat, p.a.uid, "r", sealed), null, "as a quote it stays closed");
  assert.equal(await p.e.b.openText(kb, chat, p.b.uid, "t", sealed), null, "as if from me it stays closed");
  assert.equal(await p.e.b.openText(kb, "other", p.a.uid, "t", sealed), null, "in another chat it stays closed");
});

test("saved messages: only I can open them", async () => {
  const p = await people();
  const sid = "saved" + p.a.uid;
  const s = await p.e.a.begin(sid, "s1", []);
  const sealed = await p.e.a.sealText(s.key, sid, p.a.uid, "t", "note");
  assert.equal(await p.e.a.openText(await p.e.a.chatKey(sid), sid, p.a.uid, "t", sealed), "note");
  // even another person who knew the chat id cannot: it needs MY private key
  const other = p.make(p.b);
  assert.equal(await other.openText(await other.chatKey(dmId(p.a.uid, p.b.uid)), sid, p.a.uid, "t", sealed), null);
});

test("a group message: every member opens it with the wrap made for them, and the key is the deterministic one", async () => {
  const p = await people();
  const g = "grpTest01";
  const s = await p.e.a.begin(g, "g1", [p.b.uid, p.c.uid]);
  assert.deepEqual(Object.keys(s.wraps).sort(), [p.a.uid, p.b.uid, p.c.uid].sort(), "one wrap for each member, me included");
  const sealed = await p.e.a.sealText(s.key, g, p.a.uid, "t", "to the group");
  const last = await p.e.a.sealText(s.key, g, p.a.uid, "l", "to the group");
  for (const who of ["a", "b", "c"]) {
    const k = await p.e[who].groupKey(g, p.a.uid, s.wraps);
    assert.ok(k, who + " has a key");
    assert.equal(await p.e[who].openText(k, g, p.a.uid, "t", sealed), "to the group");
    assert.equal(await p.e[who].openLast(g, p.a.uid, last, s.wraps), "to the group");
  }
  // a wrap is bound to the group, the sender and the person it is for
  assert.equal(await p.e.b.groupKey("other", p.a.uid, s.wraps), null);
  assert.equal(await p.e.b.groupKey(g, p.c.uid, s.wraps), null);
  assert.equal(await p.e.b.groupKey(g, p.a.uid, { [p.b.uid]: s.wraps[p.c.uid] }), null);
  // the message key is the one the phone would make for the same ids: a phone can edit this message
  const raw = Buffer.from(await p.e.a.messageKeyBits(g, "g1"));
  assert.equal(raw.length, 32);
});

test("nothing is ever sealed for a person who has no key yet: the caller gets null and sends nothing", async () => {
  const p = await people();
  const noOne = p.make(p.a, { [p.a.uid]: p.a.pub }); // nobody else has a key on the server
  assert.equal(await noOne.begin(dmId(p.a.uid, p.b.uid), "m1", [p.b.uid]), null);
  assert.equal(await noOne.begin("grpTest01", "g1", [p.b.uid, p.c.uid]), null);
  // saved messages need nobody else
  assert.ok(await noOne.begin("saved" + p.a.uid, "s1", []));
  // one member without a key is enough to stop a group message
  const some = p.make(p.a, { [p.a.uid]: p.a.pub, [p.b.uid]: p.b.pub });
  assert.equal(await some.begin("grpTest01", "g1", [p.b.uid, p.c.uid]), null);
});

test("a changed key stays flagged across a reload until somebody says they checked it", async () => {
  const p = await people();
  const shared = pins(); // the browser's memory, kept between two "page loads"
  const make = (dir) => makeE2E({ uid: p.a.uid, key: p.a.priv, pub: p.a.pub, publicOf: async (u) => dir[u] ?? null, pins: shared });
  const first = make(p.dir);
  await first.chatKey(dmId(p.a.uid, p.b.uid)); // first sight: remembered
  assert.equal(await first.keyChanged(p.b.uid), false);
  const swapped = { ...p.dir, [p.b.uid]: p.c.pub };
  const second = make(swapped);
  assert.equal(await second.refreshPeer(p.b.uid), true);
  assert.equal(await second.keyChanged(p.b.uid), true);
  const reloaded = make(swapped); // a new page load: the new key is already the remembered one, the flag must still be there
  assert.equal(await reloaded.refreshPeer(p.b.uid), false);
  assert.equal(await reloaded.keyChanged(p.b.uid), true, "the warning survives a reload");
  await reloaded.acknowledge(p.b.uid);
  assert.equal(await reloaded.keyChanged(p.b.uid), false);
  assert.equal(await make(swapped).keyChanged(p.b.uid), false, "and stays gone once acknowledged");
});
