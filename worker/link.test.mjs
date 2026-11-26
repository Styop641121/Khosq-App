// Web version: login by QR code. Runs with: cd worker && node --test   (fake Firebase and Firestore; no real calls)
import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, createVerify, createHash, randomBytes } from "node:crypto";
import worker from "./worker.js";

const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const SA = JSON.stringify({
  project_id: "demo",
  client_email: "sa@demo.iam.gserviceaccount.com",
  private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
});
const ENV = { SA_JSON: SA, API_KEY: "KEY123" };
const ORIGIN = "https://styop641121.github.io";
const b64u = (b) => Buffer.from(b).toString("base64url");

let n = 0;
const ip = () => "10.0." + Math.floor(++n / 250) + "." + (n % 250); // every test (and every call that is counted) is a different caller
const realNow = Date.now.bind(Date);
let shift = 0;
Date.now = () => realNow() + shift;

function world({ uid = "UID1" } = {}) {
  shift += 61000; // every test starts a minute later, so the per-minute limit of one test never counts against the next
  const w = { docs: {}, gets: [], deletes: [], uid };
  globalThis.fetch = async (url, opts = {}) => {
    url = String(url);
    const ok = (j) => new Response(JSON.stringify(j), { status: 200 });
    if (url.includes("identitytoolkit")) return w.uid ? ok({ users: [{ localId: w.uid }] }) : new Response("{}", { status: 400 });
    if (url.includes("oauth2.googleapis.com")) return ok({ access_token: "t", expires_in: 3600 });
    const m = /\/documents\/links\/([^/?]+)(\?.*)?$/.exec(url);
    if (m) {
      const id = m[1];
      if (opts.method === "PATCH") {
        if (new URL(url).searchParams.get("currentDocument.exists") === "false" && w.docs[id]) return new Response("{}", { status: 409 });
        w.docs[id] = { fields: JSON.parse(opts.body).fields };
        return ok({});
      }
      if (opts.method === "DELETE") {
        w.deletes.push(id);
        delete w.docs[id];
        return ok({});
      }
      w.gets.push(id);
      return w.docs[id] ? ok(w.docs[id]) : new Response("{}", { status: 404 });
    }
    throw new Error("unexpected " + url);
  };
  return w;
}

const call = (path, body, { method = "POST", origin = ORIGIN, auth = "tok", addr = ip(), env = ENV } = {}) =>
  worker.fetch(
    new Request("https://w.example" + path, {
      method,
      headers: { Origin: origin, "CF-Connecting-IP": addr, ...(auth ? { Authorization: "Bearer " + auth } : {}), "Content-Type": "application/json" },
      body: method === "GET" || method === "OPTIONS" ? undefined : JSON.stringify(body),
    }),
    env,
  );

const sid = () => b64u(randomBytes(16));
const packet = (extra = {}) => {
  const r = randomBytes(16);
  return { eph: b64u(randomBytes(65)), iv: b64u(randomBytes(12)), ct: b64u(randomBytes(300)), rh: b64u(createHash("sha256").update(r).digest()), r: b64u(r), ...extra };
};
const put = (s, p, o) => call("/link/put", { sid: s, eph: p.eph, iv: p.iv, ct: p.ct, rh: p.rh }, o);

test("config: the page gets the public key and the project, and only from the web version's address", async () => {
  world();
  const r = await call("/link/config", null, { method: "GET", auth: null });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { apiKey: "KEY123", projectId: "demo" });
  assert.equal(r.headers.get("Access-Control-Allow-Origin"), ORIGIN);
  const other = await call("/link/config", null, { method: "GET", auth: null, origin: "https://evil.example" });
  assert.equal(other.headers.get("Access-Control-Allow-Origin"), null);
});

test("an extra web address can be allowed with WEB_ORIGINS", async () => {
  world();
  const r = await call("/link/config", null, { method: "GET", auth: null, origin: "https://khosq.example", env: { ...ENV, WEB_ORIGINS: "https://a.example, https://khosq.example" } });
  assert.equal(r.headers.get("Access-Control-Allow-Origin"), "https://khosq.example");
});

test("the preflight question of a browser is answered", async () => {
  world();
  const r = await call("/link/poll", null, { method: "OPTIONS", auth: null });
  assert.equal(r.status, 204);
  assert.equal(r.headers.get("Access-Control-Allow-Origin"), ORIGIN);
  assert.match(r.headers.get("Access-Control-Allow-Headers"), /Authorization/);
});

test("without a service account or a key the web login is off", async () => {
  world();
  const r = await call("/link/config", null, { method: "GET", auth: null, env: {} });
  assert.equal(r.status, 501);
});

test("poll: nothing there yet, then the packet the phone left, and nothing else", async () => {
  const w = world();
  const s = sid();
  assert.deepEqual(await (await call("/link/poll", { sid: s }, { auth: null })).json(), { state: "wait" });
  const p = packet();
  assert.equal((await put(s, p)).status, 200);
  const j = await (await call("/link/poll", { sid: s }, { auth: null })).json();
  assert.deepEqual(j, { state: "ready", eph: p.eph, iv: p.iv, ct: p.ct });
  // the account and the proof stay in the Worker
  assert.ok(!JSON.stringify(j).includes("UID1"));
  assert.ok(w.docs[s].fields.uid.stringValue === "UID1");
});

test("put: signed in only, well formed only, and the first phone to answer wins", async () => {
  const w = world();
  const s = sid();
  const p = packet();
  w.uid = null;
  assert.equal((await put(s, p)).status, 401);
  w.uid = "UID1";
  assert.equal((await put(s, { ...p, rh: "short" })).status, 400);
  assert.equal((await put(s, { ...p, iv: "x".repeat(15) })).status, 400);
  assert.equal((await put(s, { ...p, ct: "!!!!" + p.ct })).status, 400);
  assert.equal((await put("not-a-session", p)).status, 400);
  assert.equal((await put(s, p)).status, 200);
  w.uid = "UID2";
  assert.equal((await put(s, packet())).status, 409);
  assert.equal(w.docs[s].fields.uid.stringValue, "UID1");
});

test("put: a phone cannot flood the post box", async () => {
  world();
  const addr = ip();
  let last = 0;
  for (let i = 0; i < 12; i++) last = (await put(sid(), packet(), { addr })).status;
  assert.equal(last, 429);
});

test("finish: with the secret the page gets a sign-in token for that account, once", async () => {
  const w = world();
  const s = sid();
  const p = packet();
  await put(s, p);
  const r = await call("/link/finish", { sid: s, r: p.r }, { auth: null });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.uid, "UID1");
  // a real, signed Firebase custom token
  const [h, c, sig] = j.tok.split(".");
  assert.deepEqual(JSON.parse(Buffer.from(h, "base64url")), { alg: "RS256", typ: "JWT" });
  const v = createVerify("RSA-SHA256");
  v.update(h + "." + c);
  assert.ok(v.verify(publicKey, Buffer.from(sig, "base64url")));
  const claim = JSON.parse(Buffer.from(c, "base64url"));
  assert.equal(claim.uid, "UID1");
  assert.equal(claim.iss, "sa@demo.iam.gserviceaccount.com");
  assert.equal(claim.sub, "sa@demo.iam.gserviceaccount.com");
  assert.equal(claim.aud, "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit");
  assert.ok(claim.exp - claim.iat <= 3600 && claim.exp > claim.iat);
  assert.deepEqual(claim.claims, { web: true });
  // used up
  assert.deepEqual(w.deletes, [s]);
  assert.equal((await call("/link/finish", { sid: s, r: p.r }, { auth: null })).status, 404);
  assert.deepEqual(await (await call("/link/poll", { sid: s }, { auth: null })).json(), { state: "wait" });
});

test("finish: a wrong secret gets nothing, and does not use the packet up", async () => {
  const w = world();
  const s = sid();
  const p = packet();
  await put(s, p);
  assert.equal((await call("/link/finish", { sid: s, r: b64u(randomBytes(16)) }, { auth: null })).status, 403);
  assert.equal((await call("/link/finish", { sid: s, r: "!!" }, { auth: null })).status, 400);
  assert.ok(w.docs[s]);
  assert.equal((await call("/link/finish", { sid: s, r: p.r }, { auth: null })).status, 200);
});

test("a packet is good for three minutes only", async () => {
  world();
  const s = sid();
  const p = packet();
  await put(s, p);
  shift += 4 * 60 * 1000;
  assert.deepEqual(await (await call("/link/poll", { sid: s }, { auth: null })).json(), { state: "wait" });
  assert.equal((await call("/link/finish", { sid: s, r: p.r }, { auth: null })).status, 404);
});

test("poll and finish are limited per caller", async () => {
  world();
  const addr = ip();
  let last = 0;
  for (let i = 0; i < 42; i++) last = (await call("/link/poll", { sid: sid() }, { auth: null, addr })).status;
  assert.equal(last, 429);
  const a2 = ip();
  for (let i = 0; i < 12; i++) last = (await call("/link/finish", { sid: sid(), r: b64u(randomBytes(16)) }, { auth: null, addr: a2 })).status;
  assert.equal(last, 429);
});

test("other routes of the Worker are untouched by the web login", async () => {
  world();
  const r = await worker.fetch(new Request("https://w.example/", { method: "GET" }), ENV);
  assert.equal(await r.text(), "KHOSQ push ok");
  const nf = await call("/link/nothing", { sid: sid() }, { auth: null });
  assert.equal(nf.status, 404);
});
