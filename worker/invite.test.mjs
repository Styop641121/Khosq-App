// Group invite links. Runs with: cd worker && node --test   (a fake Firebase network, no real calls)
import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import worker from "./worker.js";

const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const SA = JSON.stringify({
  project_id: "demo",
  client_email: "sa@demo.iam.gserviceaccount.com",
  private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
});
const ENV = { SA_JSON: SA, API_KEY: "k", INVITE_KEY: "a-long-test-secret" };
const CHAT = "Gabcdefghij1234567890"; // 21 letters and digits, like a Firestore id

const str = (v) => ({ stringValue: v });
const chatDoc = (o) => ({
  fields: {
    ...(o.group === false ? {} : { group: { booleanValue: true } }),
    ...(o.saved ? { saved: { booleanValue: true } } : {}),
    title: str(o.title ?? "Friends"),
    members: { arrayValue: { values: o.members.map(str) } },
    admins: { arrayValue: { values: (o.admins ?? []).map(str) } },
  },
});

// a small fake of Firebase Auth, the Google token, and the Firestore documents the Worker touches
function fakeNet({ uid = "ADM", chats = {}, invites = {}, users = {} }) {
  const log = { commits: [], patches: [], reads: 0 };
  globalThis.fetch = async (url, opts = {}) => {
    url = String(url);
    const ok = (j) => new Response(JSON.stringify(j), { status: 200 });
    if (url.includes("identitytoolkit")) return uid ? ok({ users: [{ localId: uid }] }) : new Response("{}", { status: 400 });
    if (url.includes("oauth2.googleapis.com")) return ok({ access_token: "t", expires_in: 3600 });
    if (url.endsWith("documents:commit")) {
      log.commits.push(JSON.parse(opts.body));
      return ok({});
    }
    let m = /\/documents\/invites\/([^?]+)/.exec(url);
    if (m) {
      if (opts.method === "PATCH") {
        const v = Number(JSON.parse(opts.body).fields.v.integerValue);
        invites[m[1]] = v;
        log.patches.push({ id: m[1], v });
        return ok({});
      }
      return invites[m[1]] === undefined ? new Response("{}", { status: 404 }) : ok({ fields: { v: { integerValue: String(invites[m[1]]) } } });
    }
    m = /\/documents\/chats\/([^/?]+)$/.exec(url);
    if (m) {
      log.reads++;
      return chats[m[1]] ? ok(chatDoc(chats[m[1]])) : new Response("{}", { status: 404 });
    }
    m = /\/documents\/users\/([^/?]+)$/.exec(url);
    if (m) return users[m[1]] ? ok({ fields: { name: str(users[m[1]].name), handle: str(users[m[1]].handle) } }) : new Response("{}", { status: 404 });
    throw new Error("unexpected " + url);
  };
  return log;
}
const call = (path, body, env = ENV) =>
  worker.fetch(new Request("https://w.example" + path, { method: "POST", headers: { Authorization: "Bearer x" }, body: JSON.stringify(body) }), env);
const GROUP = { members: ["ADM", "M1"], admins: ["ADM"], title: "Friends" };
const tokenOf = (link) => link.split("/j/")[1];

test("invites are off without INVITE_KEY", async () => {
  fakeNet({ chats: { [CHAT]: GROUP } });
  assert.equal((await call("/group/invite", { chatId: CHAT }, { SA_JSON: SA, API_KEY: "k" })).status, 501);
});

test("an admin gets a link; the link points at this Worker", async () => {
  fakeNet({ chats: { [CHAT]: GROUP } });
  const r = await call("/group/invite", { chatId: CHAT });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.match(j.link, new RegExp("^https://w\\.example/j/" + CHAT + "\\.0\\.[A-Za-z0-9_-]{22}$"));
  assert.equal(j.v, 0);
});

test("only an admin of a real group gets a link", async () => {
  fakeNet({ uid: "M1", chats: { [CHAT]: GROUP } });
  assert.equal((await call("/group/invite", { chatId: CHAT })).status, 403); // a plain member
  fakeNet({ uid: "OUT", chats: { [CHAT]: GROUP } });
  assert.equal((await call("/group/invite", { chatId: CHAT })).status, 403); // a stranger
  fakeNet({ chats: { [CHAT]: { ...GROUP, group: false } } });
  assert.equal((await call("/group/invite", { chatId: CHAT })).status, 403); // not a group
  fakeNet({ chats: { ["saved" + "x".repeat(20)]: { ...GROUP, saved: true } } });
  assert.equal((await call("/group/invite", { chatId: "saved" + "x".repeat(20) })).status, 403); // saved messages
  fakeNet({ chats: {} });
  assert.equal((await call("/group/invite", { chatId: CHAT })).status, 404);
  assert.equal((await call("/group/invite", { chatId: "A_B" })).status, 400); // a one-to-one chat id
});

test("join: a stranger becomes a member, with a name, a handle and a system line", async () => {
  fakeNet({ chats: { [CHAT]: GROUP } });
  const link = (await (await call("/group/invite", { chatId: CHAT })).json()).link;
  const log2 = fakeNet({ uid: "NEW", chats: { [CHAT]: GROUP }, users: { NEW: { name: "Ani", handle: "ani" } } });
  const r = await call("/group/join", { token: tokenOf(link) });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.deepEqual([j.member, j.joined, j.count, j.title, j.chatId], [true, true, 3, "Friends", CHAT]);
  assert.equal(log2.commits.length, 1);
  const [chatWrite, msgWrite] = log2.commits[0].writes;
  assert.ok(chatWrite.update.name.endsWith("/chats/" + CHAT));
  assert.deepEqual(chatWrite.updateTransforms[0], { fieldPath: "members", appendMissingElements: { values: [{ stringValue: "NEW" }] } });
  assert.deepEqual(chatWrite.updateMask.fieldPaths, ["names.`NEW`", "handles.`NEW`"]);
  assert.equal(chatWrite.update.fields.names.mapValue.fields.NEW.stringValue, "Ani");
  assert.equal(chatWrite.update.fields.handles.mapValue.fields.NEW.stringValue, "ani");
  assert.equal(chatWrite.currentDocument.exists, true);
  assert.equal(msgWrite.update.fields.code.stringValue, "grp_joined");
  assert.equal(msgWrite.update.fields.from.stringValue, "NEW");
  assert.equal(msgWrite.currentDocument.exists, false);
});

test("preview shows the group and changes nothing", async () => {
  fakeNet({ chats: { [CHAT]: GROUP } });
  const link = (await (await call("/group/invite", { chatId: CHAT })).json()).link;
  const log = fakeNet({ uid: "NEW", chats: { [CHAT]: GROUP } });
  const j = await (await call("/group/join", { token: tokenOf(link), preview: true })).json();
  assert.deepEqual([j.title, j.count, j.member], ["Friends", 2, false]);
  assert.equal(log.commits.length, 0);
});

test("someone already in the group is not added twice", async () => {
  fakeNet({ chats: { [CHAT]: GROUP } });
  const link = (await (await call("/group/invite", { chatId: CHAT })).json()).link;
  const log = fakeNet({ uid: "M1", chats: { [CHAT]: GROUP } });
  const j = await (await call("/group/join", { token: tokenOf(link) })).json();
  assert.equal(j.member, true);
  assert.equal(log.commits.length, 0);
});

test("reset: the old link dies, the new one works", async () => {
  const state = { chats: { [CHAT]: GROUP }, invites: {} };
  fakeNet(state);
  const old = (await (await call("/group/invite", { chatId: CHAT })).json()).link;
  const fresh = await (await call("/group/invite", { chatId: CHAT, reset: true })).json();
  assert.equal(fresh.v, 1);
  assert.notEqual(fresh.link, old);
  fakeNet({ uid: "NEW", chats: state.chats, invites: state.invites });
  assert.equal((await call("/group/join", { token: tokenOf(old) })).status, 410);
  assert.equal((await call("/group/join", { token: tokenOf(fresh.link) })).status, 200);
});

test("a made-up, altered or foreign token is refused", async () => {
  fakeNet({ chats: { [CHAT]: GROUP } });
  const link = (await (await call("/group/invite", { chatId: CHAT })).json()).link;
  const tok = tokenOf(link);
  const log = fakeNet({ uid: "NEW", chats: { [CHAT]: GROUP, ["Hother1234567890abcd"]: GROUP } });
  assert.equal((await call("/group/join", { token: tok.slice(0, -1) + (tok.endsWith("A") ? "B" : "A") })).status, 403); // bad signature
  assert.equal((await call("/group/join", { token: tok.replace(CHAT, "Hother1234567890abcd") })).status, 403); // another chat
  assert.equal((await call("/group/join", { token: tok.replace(".0.", ".1.") })).status, 403); // another version
  assert.equal((await call("/group/join", { token: "nonsense" })).status, 403);
  assert.equal((await call("/group/join", {})).status, 403);
  assert.equal((await call("/group/join", { token: tok }, { ...ENV, INVITE_KEY: "another-secret" })).status, 403); // signed with another key
  assert.equal(log.commits.length, 0);
});

test("a full group does not take more people", async () => {
  const many = Array.from({ length: 50 }, (_, i) => "U" + i);
  fakeNet({ chats: { [CHAT]: { ...GROUP, members: ["ADM", ...many.slice(1)] } } });
  const link = (await (await call("/group/invite", { chatId: CHAT })).json()).link;
  const log = fakeNet({ uid: "NEW", chats: { [CHAT]: { ...GROUP, members: many } } });
  assert.equal((await call("/group/join", { token: tokenOf(link) })).status, 409);
  assert.equal(log.commits.length, 0);
});

test("a chat that is not a group cannot be joined", async () => {
  fakeNet({ chats: { [CHAT]: GROUP } });
  const link = (await (await call("/group/invite", { chatId: CHAT })).json()).link;
  fakeNet({ uid: "NEW", chats: { [CHAT]: { ...GROUP, group: false } } });
  assert.equal((await call("/group/join", { token: tokenOf(link) })).status, 404);
});

test("a bad sign-in token gets 401", async () => {
  fakeNet({ uid: null, chats: { [CHAT]: GROUP } });
  assert.equal((await call("/group/invite", { chatId: CHAT })).status, 401);
});

test("the link page opens the app and never touches the network", async () => {
  globalThis.fetch = async () => { throw new Error("the page must not call out"); };
  const r = await worker.fetch(new Request("https://w.example/j/" + CHAT + ".0.AAAAAAAAAAAAAAAAAAAAAA"), ENV);
  assert.equal(r.status, 200);
  assert.match(r.headers.get("content-type"), /text\/html/);
  assert.match(r.headers.get("referrer-policy"), /no-referrer/);
  const html = await r.text();
  assert.ok(html.includes("intent://join/" + CHAT + ".0.AAAAAAAAAAAAAAAAAAAAAA#Intent;scheme=khosq;package=am.khosq.app;end"));
  // anything that is not a plain token is not a page
  const bad = await worker.fetch(new Request("https://w.example/j/%3Cscript%3E"), ENV);
  assert.equal(await bad.text(), "KHOSQ push ok");
});

test("too many invite calls from one user: 429", async () => {
  fakeNet({ uid: "RATEADM", chats: { [CHAT]: { ...GROUP, admins: ["RATEADM"], members: ["RATEADM"] } } });
  let last = 200;
  for (let i = 0; i < 25; i++) last = (await call("/group/invite", { chatId: CHAT })).status;
  assert.equal(last, 429);
});
