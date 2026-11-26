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
  assert.equal(e2e.keyChanged(ids.bob), false);
  // the server now says Carol's key is Bob's
  const swapped = fx.users[ids.carol].pk;
  const key = await crypto.subtle.importKey("pkcs8", fromB64(me.priv), { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
  const p2 = pins();
  p2.map.set(ids.bob, fx.users[ids.bob].pk);
  const e2 = makeE2E({ uid: me.uid, key, pub: me.pub, publicOf: async () => swapped, pins: p2 });
  assert.equal(await e2.refreshPeer(ids.bob), true);
  assert.equal(e2.keyChanged(ids.bob), true);
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
