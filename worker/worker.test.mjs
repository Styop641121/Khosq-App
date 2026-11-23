// Runs with: cd worker && node --test   (Node 20+, no packages needed)
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
const ENV = { SA_JSON: SA, API_KEY: "k" };

const str = (v) => ({ stringValue: v });
const chatDoc = (o) => ({
  fields: {
    members: { arrayValue: { values: o.members.map(str) } },
    ...(o.status ? { status: str(o.status) } : {}),
    ...(o.requester ? { requester: str(o.requester) } : {}),
    ...(o.blocked ? { blocked: { mapValue: { fields: { [o.blocked]: { booleanValue: true } } } } } : {}),
  },
});

// a fake network: Firebase Auth, Google token, Firestore and FCM
function fakeNet({ uid = "A", chat }) {
  const calls = { fcm: 0 };
  globalThis.fetch = async (url) => {
    url = String(url);
    const ok = (j) => new Response(JSON.stringify(j), { status: 200 });
    if (url.includes("identitytoolkit")) return uid ? ok({ users: [{ localId: uid }] }) : new Response("{}", { status: 400 });
    if (url.includes("oauth2.googleapis.com")) return ok({ access_token: "t", expires_in: 3600 });
    if (url.includes("/documents/chats/")) return chat ? ok(chatDoc(chat)) : new Response("{}", { status: 404 });
    if (url.includes("/documents/tokens/")) return ok({ fields: { fcmToken: str("tok") } });
    if (url.includes("fcm.googleapis.com")) { calls.fcm++; return ok({}); }
    throw new Error("unexpected " + url);
  };
  return calls;
}
const post = (body, raw) =>
  worker.fetch(new Request("https://w", { method: "POST", headers: { Authorization: "Bearer x" }, body: raw ?? JSON.stringify(body) }), ENV);

test("active DM: push is sent", async () => {
  const calls = fakeNet({ chat: { members: ["A", "B"] } });
  const r = await post({ type: "msg", chatId: "A_B", title: "t", body: "b" });
  assert.equal(r.status, 200);
  assert.equal(calls.fcm, 1);
});

test("blocked chat: nothing is sent", async () => {
  const calls = fakeNet({ chat: { members: ["A", "B"], blocked: "B" } });
  const r = await post({ type: "msg", chatId: "A_B" });
  assert.equal(await r.text(), "skipped");
  assert.equal(calls.fcm, 0);
});

test("pending chat: only the requester may push, and no calls", async () => {
  let calls = fakeNet({ uid: "A", chat: { members: ["A", "B"], status: "pending", requester: "B" } });
  assert.equal(await (await post({ type: "msg", chatId: "A_B" })).text(), "skipped");
  assert.equal(calls.fcm, 0);
  calls = fakeNet({ uid: "A", chat: { members: ["A", "B"], status: "pending", requester: "A" } });
  await post({ type: "msg", chatId: "A_B" });
  assert.equal(calls.fcm, 1);
  calls = fakeNet({ uid: "A", chat: { members: ["A", "B"], status: "pending", requester: "A" } });
  assert.equal(await (await post({ type: "call", chatId: "A_B" })).text(), "skipped");
  assert.equal(calls.fcm, 0);
});

test("a user who is not in the chat gets 403", async () => {
  fakeNet({ uid: "A", chat: { members: ["B", "C"] } });
  assert.equal((await post({ type: "msg", chatId: "A_B" })).status, 403);
  fakeNet({ uid: "A", chat: { members: ["B", "C"] } });
  assert.equal((await post({ type: "msg", chatId: "grp1" })).status, 403);
});

test("a chat id that does not contain the caller is refused", async () => {
  fakeNet({ chat: { members: ["B", "C"] } });
  assert.equal((await post({ type: "msg", chatId: "B_C" })).status, 403);
});

test("bad token: 401", async () => {
  fakeNet({ uid: null, chat: { members: ["A", "B"] } });
  assert.equal((await post({ type: "msg", chatId: "A_B" })).status, 401);
});

test("body over 4096 characters: 413", async () => {
  fakeNet({ chat: { members: ["A", "B"] } });
  assert.equal((await post(null, JSON.stringify({ type: "msg", chatId: "A_B", body: "x".repeat(5000) }))).status, 413);
});

test("too many requests from one user: 429", async () => {
  fakeNet({ uid: "RATE", chat: { members: ["RATE", "B"] } });
  let last = 200;
  for (let i = 0; i < 125; i++) last = (await post({ type: "msg", chatId: "B_RATE" })).status;
  assert.equal(last, 429);
});
