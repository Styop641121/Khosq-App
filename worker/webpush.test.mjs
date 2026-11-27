// The web version sends the push for a message it wrote, from the browser. CORS for that is granted to the web address only, and only
// on the main address "/" and the two group routes (see webinvite.test.mjs). Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import worker from "./worker.js";

const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const ENV = { SA_JSON: JSON.stringify({ project_id: "demo", client_email: "sa@demo.iam.gserviceaccount.com", private_key: privateKey.export({ type: "pkcs8", format: "pem" }) }), API_KEY: "KEY" };
const WEB = "https://styop641121.github.io";
const str = (v) => ({ stringValue: v });

function net({ uid = "A", members = ["A", "B"] } = {}) {
  const calls = { fcm: [] };
  globalThis.fetch = async (url, opts = {}) => {
    url = String(url);
    const ok = (j) => new Response(JSON.stringify(j), { status: 200 });
    if (url.includes("identitytoolkit")) return uid ? ok({ users: [{ localId: uid }] }) : new Response("{}", { status: 400 });
    if (url.includes("oauth2.googleapis.com")) return ok({ access_token: "t", expires_in: 3600 });
    if (url.includes("/documents/chats/")) return ok({ fields: { members: { arrayValue: { values: members.map(str) } } } });
    if (url.includes("/documents/tokens/")) return ok({ fields: { fcmToken: str("tok") } });
    if (url.includes("fcm.googleapis.com")) { calls.fcm.push(JSON.parse(opts.body)); return ok({}); }
    throw new Error("unexpected " + url);
  };
  return calls;
}
const send = (path, { method = "POST", origin, body = { type: "msg", chatId: "A_B", title: "Ani", body: "" }, env = ENV } = {}) =>
  worker.fetch(
    new Request("https://w.example" + path, {
      method,
      headers: { Authorization: "Bearer x", "Content-Type": "application/json", ...(origin ? { Origin: origin } : {}) },
      body: method === "POST" ? JSON.stringify(body) : undefined,
    }),
    env,
  );

test("the web page's preflight for the main address is answered, for the web address only", async () => {
  net();
  const r = await send("/", { method: "OPTIONS", origin: WEB });
  assert.equal(r.status, 204);
  assert.equal(r.headers.get("Access-Control-Allow-Origin"), WEB);
  assert.match(r.headers.get("Access-Control-Allow-Headers"), /Authorization/);
  assert.match(r.headers.get("Access-Control-Allow-Methods"), /POST/);
  assert.equal(r.headers.get("Vary"), "Origin");
  for (const other of ["https://evil.example", "https://styop641121.github.io.evil.example", "http://styop641121.github.io", "null"]) {
    const x = await send("/", { method: "OPTIONS", origin: other });
    assert.equal(x.headers.get("Access-Control-Allow-Origin"), null, other);
    assert.notEqual(x.status, 204, other);
  }
});

test("a push sent from the web page works and the page may read the answer", async () => {
  const calls = net();
  const r = await send("/", { origin: WEB, body: { type: "msg", chatId: "A_B", title: "Ani", body: "" } });
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("Access-Control-Allow-Origin"), WEB);
  assert.equal(calls.fcm.length, 1);
});

test("an error answer carries the CORS header too, so the page can see it was refused", async () => {
  net({ uid: null });
  const r = await send("/", { origin: WEB });
  assert.equal(r.status, 401);
  assert.equal(r.headers.get("Access-Control-Allow-Origin"), WEB);
  net({ uid: "A", members: ["B", "C"] });
  const x = await send("/", { origin: WEB });
  assert.equal(x.status, 403);
  assert.equal(x.headers.get("Access-Control-Allow-Origin"), WEB);
});

test("the phone's call (no Origin header) is exactly as before: no CORS headers", async () => {
  const calls = net();
  const r = await send("/");
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("Access-Control-Allow-Origin"), null);
  assert.equal(calls.fcm.length, 1);
  const other = await send("/", { origin: "https://evil.example" });
  assert.equal(other.headers.get("Access-Control-Allow-Origin"), null, "another site's page gets no permission");
});

test("the other routes stay closed to browsers: no CORS headers, no preflight", async () => {
  net();
  for (const path of ["/ai/translate", "/ai/transcribe", "/ai/linkcheck", "/finder/save", "/group", "/group/invite/x", "/anything"]) {
    const pre = await send(path, { method: "OPTIONS", origin: WEB });
    assert.equal(pre.headers.get("Access-Control-Allow-Origin"), null, "preflight " + path);
    const post = await send(path, { origin: WEB, body: {} });
    assert.equal(post.headers.get("Access-Control-Allow-Origin"), null, "post " + path);
  }
});

test("WEB_ORIGINS adds another web address to the main address as well", async () => {
  net();
  const env = { ...ENV, WEB_ORIGINS: "https://khosq.example.com, https://other.example" };
  const r = await send("/", { method: "OPTIONS", origin: "https://khosq.example.com", env });
  assert.equal(r.status, 204);
  assert.equal(r.headers.get("Access-Control-Allow-Origin"), "https://khosq.example.com");
  const x = await send("/", { method: "OPTIONS", origin: "https://not-listed.example", env });
  assert.equal(x.headers.get("Access-Control-Allow-Origin"), null);
});
