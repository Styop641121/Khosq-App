// The web version asks for a group's invitation link and joins by one, from the browser. CORS for the two group routes is granted to the web
// address only; everything else stays closed to browsers. Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import worker from "./worker.js";

const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const SA = JSON.stringify({ project_id: "demo", client_email: "sa@demo.iam.gserviceaccount.com", private_key: privateKey.export({ type: "pkcs8", format: "pem" }) });
const ENV = { SA_JSON: SA, API_KEY: "KEY", INVITE_KEY: "a-long-test-secret" };
const WEB = "https://styop641121.github.io";
const CHAT = "Gabcdefghij1234567890";
const str = (v) => ({ stringValue: v });

function net({ uid = "ADM", admins = ["ADM"], members = ["ADM", "B"] } = {}) {
  const log = { commits: 0 };
  globalThis.fetch = async (url, opts = {}) => {
    url = String(url);
    const ok = (j) => new Response(JSON.stringify(j), { status: 200 });
    if (url.includes("identitytoolkit")) return uid ? ok({ users: [{ localId: uid }] }) : new Response("{}", { status: 400 });
    if (url.includes("oauth2.googleapis.com")) return ok({ access_token: "t", expires_in: 3600 });
    if (url.endsWith("documents:commit")) {
      log.commits++;
      return ok({});
    }
    if (/\/documents\/invites\//.test(url)) return opts.method === "PATCH" ? ok({}) : new Response("{}", { status: 404 });
    if (/\/documents\/chats\/[^/?]+$/.test(url)) {
      return ok({
        fields: {
          group: { booleanValue: true },
          title: str("Friends"),
          members: { arrayValue: { values: members.map(str) } },
          admins: { arrayValue: { values: admins.map(str) } },
        },
      });
    }
    if (/\/documents\/users\//.test(url)) return ok({ fields: { name: str("Eve"), handle: str("eve") } });
    throw new Error("unexpected " + url);
  };
  return log;
}
const send = (path, { method = "POST", origin, body = {}, env = ENV } = {}) =>
  worker.fetch(
    new Request("https://w.example" + path, {
      method,
      headers: { Authorization: "Bearer x", "Content-Type": "application/json", ...(origin ? { Origin: origin } : {}) },
      body: method === "POST" ? JSON.stringify(body) : undefined,
    }),
    env,
  );
const ACAO = (r) => r.headers.get("Access-Control-Allow-Origin");

test("the web page's preflight for both group routes is answered, for the web address only", async () => {
  net();
  for (const path of ["/group/invite", "/group/join"]) {
    const r = await send(path, { method: "OPTIONS", origin: WEB });
    assert.equal(r.status, 204, path);
    assert.equal(ACAO(r), WEB, path);
    assert.match(r.headers.get("Access-Control-Allow-Headers"), /Authorization/, path);
    assert.match(r.headers.get("Access-Control-Allow-Headers"), /Content-Type/, path);
    assert.match(r.headers.get("Access-Control-Allow-Methods"), /POST/, path);
    assert.equal(r.headers.get("Vary"), "Origin", path);
    for (const other of ["https://evil.example", "https://styop641121.github.io.evil.example", "http://styop641121.github.io", "null"]) {
      const x = await send(path, { method: "OPTIONS", origin: other });
      assert.equal(ACAO(x), null, path + " " + other);
      assert.notEqual(x.status, 204, path + " " + other);
    }
  }
});

test("an admin's page gets the link, and may read the answer", async () => {
  net();
  const r = await send("/group/invite", { origin: WEB, body: { chatId: CHAT } });
  assert.equal(r.status, 200);
  assert.equal(ACAO(r), WEB);
  const j = await r.json();
  assert.match(j.link, /^https:\/\/w\.example\/j\/[A-Za-z0-9_.-]{20,120}$/);
  assert.equal(j.v, 0);
  const again = await send("/group/invite", { origin: WEB, body: { chatId: CHAT, reset: true } });
  assert.equal((await again.json()).v, 1, "reset makes the next version");
});

test("joining from the web page: look first, then join, and the page may read both answers", async () => {
  net({ uid: "ADM" });
  const token = (await (await send("/group/invite", { origin: WEB, body: { chatId: CHAT } })).json()).link.split("/j/")[1];
  const log2 = net({ uid: "EVE", members: ["ADM", "B"] });
  const look = await send("/group/join", { origin: WEB, body: { token, preview: true } });
  assert.equal(look.status, 200);
  assert.equal(ACAO(look), WEB);
  assert.deepEqual(await look.json(), { chatId: CHAT, title: "Friends", count: 2, member: false });
  assert.equal(log2.commits, 0, "looking writes nothing");
  const join = await send("/group/join", { origin: WEB, body: { token } });
  assert.equal(join.status, 200);
  assert.equal(ACAO(join), WEB);
  const j = await join.json();
  assert.equal(j.joined, true);
  assert.equal(j.count, 3);
  assert.equal(log2.commits, 1);
});

test("a refusal carries the CORS header too, so the page can tell why", async () => {
  net({ uid: null });
  const a = await send("/group/invite", { origin: WEB, body: { chatId: CHAT } });
  assert.equal(a.status, 401);
  assert.equal(ACAO(a), WEB);
  net({ uid: "B", admins: ["ADM"] });
  const b = await send("/group/invite", { origin: WEB, body: { chatId: CHAT } });
  assert.equal(b.status, 403, "a member who is not an admin gets no link");
  assert.equal(ACAO(b), WEB);
  net({ uid: "EVE" });
  const c = await send("/group/join", { origin: WEB, body: { token: "nonsense.0.nonsense" } });
  assert.equal(c.status, 403);
  assert.equal(ACAO(c), WEB);
  const off = await send("/group/invite", { origin: WEB, body: { chatId: CHAT }, env: { ...ENV, INVITE_KEY: "" } });
  assert.equal(off.status, 501);
  assert.equal(ACAO(off), WEB);
});

test("the phone's call (no Origin header) is exactly as before: no CORS headers", async () => {
  net();
  const r = await send("/group/invite", { body: { chatId: CHAT } });
  assert.equal(r.status, 200);
  assert.equal(ACAO(r), null);
  const other = await send("/group/invite", { origin: "https://evil.example", body: { chatId: CHAT } });
  assert.equal(other.status, 200, "the server still answers (the token is the proof); the browser is the one that refuses to hand it over");
  assert.equal(ACAO(other), null, "another site's page gets no permission");
});

test("what stays closed to browsers: /ai/*, /finder/*, and routes next to the group ones", async () => {
  net();
  for (const path of ["/ai/translate", "/ai/transcribe", "/ai/linkcheck", "/finder/save", "/group", "/group/", "/group/invite/", "/group/invite/x", "/group/other"]) {
    const pre = await send(path, { method: "OPTIONS", origin: WEB });
    assert.equal(ACAO(pre), null, "preflight " + path);
    const post = await send(path, { origin: WEB, body: {} });
    assert.equal(ACAO(post), null, "post " + path);
  }
});

test("WEB_ORIGINS adds another web address to the group routes as well", async () => {
  net();
  const env = { ...ENV, WEB_ORIGINS: "https://khosq.example.com" };
  const r = await send("/group/join", { method: "OPTIONS", origin: "https://khosq.example.com", env });
  assert.equal(r.status, 204);
  assert.equal(ACAO(r), "https://khosq.example.com");
  const x = await send("/group/join", { method: "OPTIONS", origin: "https://not-listed.example", env });
  assert.equal(ACAO(x), null);
});

test("the invitation page for phones (GET /j/...) is not changed", async () => {
  const r = await worker.fetch(new Request("https://w.example/j/" + "a".repeat(30)), ENV);
  assert.equal(r.status, 200);
  assert.match(await r.text(), /intent:\/\/join\//);
  assert.equal(ACAO(r), null);
});
