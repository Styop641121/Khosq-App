// A push for a reaction (type "react"), asked for by the phone or by the web page. Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import worker from "./worker.js";

const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const ENV = { SA_JSON: JSON.stringify({ project_id: "demo", client_email: "sa@demo.iam.gserviceaccount.com", private_key: privateKey.export({ type: "pkcs8", format: "pem" }) }), API_KEY: "KEY" };
const WEB = "https://styop641121.github.io";
const str = (v) => ({ stringValue: v });

/** A pretend network: the chat document, its messages, the push tokens and FCM. Returns what was asked of it. */
function net({ uid = "A", chat, messages = {} }) {
  const seen = { fcm: [], reads: [] };
  globalThis.fetch = async (url, opts = {}) => {
    url = String(url);
    const ok = (j) => new Response(JSON.stringify(j), { status: 200 });
    if (url.includes("identitytoolkit")) return ok({ users: [{ localId: uid }] });
    if (url.includes("oauth2.googleapis.com")) return ok({ access_token: "t", expires_in: 3600 });
    const m = /\/documents\/chats\/([^/]+)\/messages\/([^/?]+)/.exec(url);
    if (m) {
      seen.reads.push(decodeURIComponent(m[2]));
      const d = messages[decodeURIComponent(m[2])];
      return d ? ok({ fields: d }) : new Response("{}", { status: 404 });
    }
    if (url.includes("/documents/chats/")) {
      const f = { members: { arrayValue: { values: chat.members.map(str) } } };
      if (chat.names) f.names = { mapValue: { fields: Object.fromEntries(Object.entries(chat.names).map(([k, v]) => [k, str(v)])) } };
      if (chat.title) f.title = str(chat.title);
      if (chat.blocked) f.blocked = { mapValue: { fields: { [chat.blocked]: { booleanValue: true } } } };
      return ok({ fields: f });
    }
    if (url.includes("/documents/tokens/")) return ok({ fields: { fcmToken: str("tok-" + decodeURIComponent(url.split("/").pop())) } });
    if (url.includes("fcm.googleapis.com")) {
      seen.fcm.push(JSON.parse(opts.body).message);
      return ok({});
    }
    throw new Error("unexpected " + url);
  };
  return seen;
}

/** A stored message: who wrote it, the reactions on it, deleted or not. */
const message = ({ from, reactions = {}, deleted = false }) => ({
  from: str(from),
  ...(deleted ? { deleted: { booleanValue: true } } : {}),
  ...(Object.keys(reactions).length ? { reactions: { mapValue: { fields: Object.fromEntries(Object.entries(reactions).map(([k, v]) => [k, str(v)])) } } } : {}),
});

const react = (body, { origin } = {}) =>
  worker.fetch(
    new Request("https://w.example/", {
      method: "POST",
      headers: { Authorization: "Bearer x", "Content-Type": "application/json", ...(origin ? { Origin: origin } : {}) },
      body: JSON.stringify({ type: "react", chatId: "A_B", msgId: "m1", title: "Ani", body: "👍", ...body }),
    }),
    ENV,
  );

const DM = { members: ["A", "B"], names: { A: "Ani", B: "Bob" } };
const FROM_B = { m1: message({ from: "B", reactions: { A: "👍" } }) };

test("a reaction on somebody's message tells that person, with the emoji and my name", async () => {
  const seen = net({ chat: DM, messages: FROM_B });
  const r = await react({});
  assert.equal(r.status, 200);
  assert.equal(seen.fcm.length, 1);
  const m = seen.fcm[0];
  assert.equal(m.token, "tok-B");
  assert.deepEqual(m.data, { type: "react", chatId: "A_B", from: "A", title: "Ani", who: "Ani", group: "0", body: "👍", msgId: "m1" });
  for (const v of Object.values(m.data)) assert.equal(typeof v, "string"); // FCM data holds strings only
  assert.equal(m.android.priority, "HIGH");
});

test("in a group only the author of the message is told, and the title is the group's", async () => {
  const seen = net({
    uid: "A",
    chat: { members: ["A", "B", "C", "D"], names: { A: "Ani", B: "Bob" }, title: "Family" },
    messages: { m1: message({ from: "C", reactions: { A: "❤️", B: "😂" } }) },
  });
  const r = await react({ chatId: "grp1", body: "❤️", title: "Family" });
  assert.equal(r.status, 200);
  assert.equal(seen.fcm.length, 1, "not the whole group");
  assert.equal(seen.fcm[0].token, "tok-C");
  assert.equal(seen.fcm[0].data.title, "Family");
  assert.equal(seen.fcm[0].data.who, "Ani");
  assert.equal(seen.fcm[0].data.group, "1");
  assert.equal(seen.fcm[0].data.body, "❤️");
});

test("the name on the push is the one in the chat, not the one the caller wrote", async () => {
  const seen = net({ chat: DM, messages: FROM_B });
  await react({ title: "Mallory (the bank)" });
  assert.equal(seen.fcm[0].data.title, "Ani");
  assert.equal(seen.fcm[0].data.who, "Ani");
});

test("with no name stored in the chat, the caller's own title is used (as for a message)", async () => {
  const seen = net({ chat: { members: ["A", "B"] }, messages: FROM_B });
  await react({ title: "Ani" });
  assert.equal(seen.fcm[0].data.title, "Ani");
  assert.equal(seen.fcm[0].data.who, "Ani");
});

test("nothing is sent when the reaction is not really there", async () => {
  // taken back
  let seen = net({ chat: DM, messages: { m1: message({ from: "B" }) } });
  assert.equal(await (await react({})).text(), "skipped");
  assert.equal(seen.fcm.length, 0);
  // another emoji than the one the caller says (changed again in the meantime)
  seen = net({ chat: DM, messages: { m1: message({ from: "B", reactions: { A: "😮" } }) } });
  assert.equal(await (await react({ body: "👍" })).text(), "skipped");
  assert.equal(seen.fcm.length, 0);
  // somebody else's reaction, not mine
  seen = net({ chat: DM, messages: { m1: message({ from: "B", reactions: { B: "👍" }}) } });
  assert.equal(await (await react({})).text(), "skipped");
  assert.equal(seen.fcm.length, 0);
  // the message does not exist
  seen = net({ chat: DM, messages: {} });
  assert.equal(await (await react({ msgId: "nope" })).text(), "skipped");
  assert.equal(seen.fcm.length, 0);
});

test("nothing is sent for a reaction on my own message, or on a deleted one", async () => {
  let seen = net({ chat: DM, messages: { m1: message({ from: "A", reactions: { A: "👍" } }) } });
  assert.equal(await (await react({})).text(), "skipped");
  assert.equal(seen.fcm.length, 0);
  seen = net({ chat: DM, messages: { m1: message({ from: "B", reactions: { A: "👍" }, deleted: true }) } });
  assert.equal(await (await react({})).text(), "skipped");
  assert.equal(seen.fcm.length, 0);
});

test("a message from somebody who is no longer in the chat tells nobody", async () => {
  const seen = net({ chat: { members: ["A", "C"], names: { A: "Ani" }, title: "G" }, messages: { m1: message({ from: "B", reactions: { A: "👍" } }) } });
  assert.equal(await (await react({ chatId: "grp1" })).text(), "skipped");
  assert.equal(seen.fcm.length, 0);
});

test("only an emoji goes into a push: a word, markup, a long text or a number is refused", async () => {
  for (const bad of ["hello", "<b>x</b>", "👍".repeat(20), "1", "👍 now", "", "👍\n"]) {
    const seen = net({ chat: DM, messages: { m1: message({ from: "B", reactions: { A: bad } }) } });
    const r = await react({ body: bad });
    assert.equal(await r.text(), "skipped", JSON.stringify(bad));
    assert.equal(seen.fcm.length, 0, JSON.stringify(bad));
  }
  for (const good of ["👍", "❤️", "😂", "😮", "😢", "🙏", "👍🏽", "👨‍👩‍👧"]) {
    const seen = net({ chat: DM, messages: { m1: message({ from: "B", reactions: { A: good } }) } });
    await react({ body: good });
    assert.equal(seen.fcm.length, 1, good);
    assert.equal(seen.fcm[0].data.body, good);
  }
});

test("a message id that is not an id is a bad request, and nothing is read for it", async () => {
  for (const bad of ["", "../x", "a/b", "m1?x=1", "x".repeat(65), "m 1"]) {
    const seen = net({ chat: DM, messages: FROM_B });
    const r = await react({ msgId: bad });
    assert.equal(r.status, 400, JSON.stringify(bad));
    assert.equal(seen.reads.length, 0);
    assert.equal(seen.fcm.length, 0);
  }
  const seen = net({ chat: DM, messages: FROM_B });
  const r = await worker.fetch(
    new Request("https://w.example/", { method: "POST", headers: { Authorization: "Bearer x" }, body: JSON.stringify({ type: "react", chatId: "A_B", body: "👍" }) }),
    ENV,
  );
  assert.equal(r.status, 400);
  assert.equal(seen.fcm.length, 0);
});

test("the same checks as for a message: a blocked chat, a stranger, a bad chat id", async () => {
  let seen = net({ chat: { ...DM, blocked: "B" }, messages: FROM_B });
  assert.equal(await (await react({})).text(), "skipped");
  assert.equal(seen.fcm.length, 0);
  seen = net({ uid: "Z", chat: DM, messages: FROM_B });
  assert.equal((await react({ chatId: "A_B" })).status, 403);
  seen = net({ uid: "A", chat: { members: ["B", "C"] }, messages: FROM_B });
  assert.equal((await react({ chatId: "grp1" })).status, 403);
  assert.equal(seen.fcm.length, 0);
});

test("an ordinary message push is exactly what it was: no message is read, no extra fields", async () => {
  const seen = net({ chat: DM, messages: FROM_B });
  const r = await worker.fetch(
    new Request("https://w.example/", { method: "POST", headers: { Authorization: "Bearer x" }, body: JSON.stringify({ type: "msg", chatId: "A_B", title: "Ani", body: "" }) }),
    ENV,
  );
  assert.equal(r.status, 200);
  assert.equal(seen.reads.length, 0);
  assert.deepEqual(seen.fcm[0].data, { type: "msg", chatId: "A_B", from: "A", title: "Ani", body: "" });
  // a made-up msgId on a message push changes nothing
  const x = net({ chat: DM, messages: FROM_B });
  await worker.fetch(
    new Request("https://w.example/", { method: "POST", headers: { Authorization: "Bearer x" }, body: JSON.stringify({ type: "msg", chatId: "A_B", title: "Ani", body: "", who: "x", msgId: "m1" }) }),
    ENV,
  );
  assert.deepEqual(x.fcm[0].data, { type: "msg", chatId: "A_B", from: "A", title: "Ani", body: "" });
});

test("the web page may ask for it (CORS) and reads the answer", async () => {
  const seen = net({ chat: DM, messages: FROM_B });
  const r = await react({}, { origin: WEB });
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("Access-Control-Allow-Origin"), WEB);
  assert.equal(seen.fcm.length, 1);
  // another site is not answered with a CORS header
  net({ chat: DM, messages: FROM_B });
  const x = await react({}, { origin: "https://evil.example" });
  assert.equal(x.headers.get("Access-Control-Allow-Origin"), null);
});
