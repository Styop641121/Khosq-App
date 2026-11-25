// Trust Shield link check. Runs with: cd worker && node --test   (a fake Firebase login and a fake DNS-over-HTTPS server, no real calls)
import test from "node:test";
import assert from "node:assert/strict";
import worker from "./worker.js";

let seen = [];
let doh = null; // (host, url, init) -> Response | throws
function fakeNet(uid) {
  seen = [];
  globalThis.fetch = async (url, init) => {
    url = String(url);
    seen.push({ url, init });
    if (url.includes("identitytoolkit")) return uid ? new Response(JSON.stringify({ users: [{ localId: uid }] }), { status: 200 }) : new Response("{}", { status: 400 });
    if (url.startsWith("https://security.cloudflare-dns.com/dns-query")) {
      const q = decodeQuestion(new URL(url).searchParams.get("dns"));
      return doh(q.host, url, init);
    }
    throw new Error("unexpected " + url);
  };
}
let n = 0;
const fresh = () => "L" + ++n + "_" + Math.random().toString(36).slice(2);

// ---- tiny DNS helpers (RFC 1035), written separately from the Worker's so a shared mistake cannot hide
function b64uToBytes(s) {
  return Uint8Array.from(Buffer.from(s.replace(/-/g, "+").replace(/_/g, "/"), "base64"));
}
function decodeQuestion(dns) {
  const b = b64uToBytes(dns);
  let pos = 12;
  const labels = [];
  while (b[pos] !== 0) {
    labels.push(String.fromCharCode(...b.subarray(pos + 1, pos + 1 + b[pos])));
    pos += 1 + b[pos];
  }
  pos += 1;
  return { host: labels.join("."), type: (b[pos] << 8) | b[pos + 1], cls: (b[pos + 2] << 8) | b[pos + 3], flags: (b[2] << 8) | b[3], qd: (b[4] << 8) | b[5], id: (b[0] << 8) | b[1], len: b.length, end: pos + 4 };
}
function nameBytes(host) {
  const out = [];
  for (const l of host.split(".")) out.push(l.length, ...[...l].map((c) => c.charCodeAt(0)));
  out.push(0);
  return out;
}
// answers: [{type, data:[bytes]}] ; the name of every answer is a pointer to the question (c0 0c)
function reply(host, answers, rcode = 0) {
  const q = nameBytes(host);
  const out = [0, 0, 0x81, 0x80 | rcode, 0, 1, 0, answers.length, 0, 0, 0, 0, ...q, 0, 1, 0, 1];
  for (const a of answers) out.push(0xc0, 0x0c, 0, a.type, 0, 1, 0, 0, 1, 0x2c, 0, a.data.length, ...a.data);
  return new Response(new Uint8Array(out), { status: 200, headers: { "content-type": "application/dns-message" } });
}
const A = (a, b, c, d) => ({ type: 1, data: [a, b, c, d] });

const call = (body, { uid = fresh(), env = {}, raw = null } = {}) => {
  fakeNet(uid);
  return worker.fetch(
    new Request("https://w.example/ai/linkcheck", {
      method: "POST",
      headers: { Authorization: "Bearer x", "Content-Type": "application/json" },
      body: raw !== null ? raw : JSON.stringify(body),
    }),
    env,
  );
};

test("a person who is not signed in gets 401, and no DNS question goes out", async () => {
  doh = () => reply("a.com", [A(1, 2, 3, 4)]);
  const r = await call({ hosts: ["example.com"] }, { uid: null });
  assert.equal(r.status, 401);
  assert.equal(seen.filter((s) => s.url.includes("cloudflare-dns")).length, 0);
});

test("a site that is not on the list: ok", async () => {
  doh = (h) => reply(h, [A(93, 184, 216, 34)]);
  const r = await call({ hosts: ["example.com"] });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { results: { "example.com": "ok" } });
});

test("a site answered with 0.0.0.0 is blocked", async () => {
  doh = (h) => reply(h, [A(0, 0, 0, 0)]);
  assert.deepEqual(await (await call({ hosts: ["malware.testcategory.com"] })).json(), { results: { "malware.testcategory.com": "blocked" } });
});

test("a chain (alias first, then 0.0.0.0) is still blocked, and the alias is skipped properly", async () => {
  doh = (h) => reply(h, [{ type: 5, data: [3, 0x77, 0x77, 0x77, 0xc0, 0x0c] }, A(0, 0, 0, 0)]);
  assert.equal((await (await call({ hosts: ["evil.example.org"] })).json()).results["evil.example.org"], "blocked");
});

test("a normal address next to a 0.0.0.1 is not blocked", async () => {
  doh = (h) => reply(h, [A(0, 0, 0, 1), A(10, 0, 0, 0)]);
  assert.equal((await (await call({ hosts: ["example.net"] })).json()).results["example.net"], "ok");
});

test("a name that does not exist, or a server error, is unknown, not blocked", async () => {
  doh = (h) => reply(h, [], 3);
  assert.equal((await (await call({ hosts: ["nope.example.com"] })).json()).results["nope.example.com"], "unknown");
  doh = (h) => reply(h, [], 2);
  assert.equal((await (await call({ hosts: ["nope.example.com"] })).json()).results["nope.example.com"], "unknown");
});

test("no answer records at all (a site with only an IPv6 address): ok", async () => {
  doh = (h) => reply(h, []);
  assert.equal((await (await call({ hosts: ["v6only.example.com"] })).json()).results["v6only.example.com"], "ok");
});

test("a failing, silent or garbled DNS server is unknown", async () => {
  doh = () => new Response("nope", { status: 502 });
  assert.equal((await (await call({ hosts: ["a.example.com"] })).json()).results["a.example.com"], "unknown");
  doh = () => {
    throw new Error("network down");
  };
  assert.equal((await (await call({ hosts: ["a.example.com"] })).json()).results["a.example.com"], "unknown");
  doh = () => new Response(new Uint8Array([1, 2, 3]), { status: 200 });
  assert.equal((await (await call({ hosts: ["a.example.com"] })).json()).results["a.example.com"], "unknown");
  // an answer that says it has 3 records but is cut off
  doh = (h) => {
    const bytes = [0, 0, 0x81, 0x80, 0, 1, 0, 3, 0, 0, 0, 0, ...nameBytes(h), 0, 1, 0, 1, 0xc0, 0x0c, 0, 1];
    return new Response(new Uint8Array(bytes), { status: 200 });
  };
  assert.equal((await (await call({ hosts: ["a.example.com"] })).json()).results["a.example.com"], "unknown");
});

test("several sites at once, each with its own answer; repeats and capitals are folded", async () => {
  doh = (h) => (h === "bad.example.org" ? reply(h, [A(0, 0, 0, 0)]) : h === "gone.example.org" ? reply(h, [], 3) : reply(h, [A(1, 1, 1, 1)]));
  const j = await (await call({ hosts: ["Good.Example.com", "bad.example.org", "gone.example.org", "good.example.com", "bad.example.org."] })).json();
  assert.deepEqual(j, { results: { "good.example.com": "ok", "bad.example.org": "blocked", "gone.example.org": "unknown" } });
});

test("what is asked: one A question per site, to the malware-blocking server, nothing else", async () => {
  doh = (h) => reply(h, [A(1, 2, 3, 4)]);
  await call({ hosts: ["pay.example.com", "other.example.org"] });
  const asks = seen.filter((s) => s.url.includes("cloudflare-dns"));
  assert.equal(asks.length, 2);
  for (const a of asks) {
    assert.ok(a.url.startsWith("https://security.cloudflare-dns.com/dns-query?dns="));
    assert.equal(a.init.headers.accept, "application/dns-message");
    const q = decodeQuestion(new URL(a.url).searchParams.get("dns"));
    assert.equal(q.type, 1);
    assert.equal(q.cls, 1);
    assert.equal(q.qd, 1);
    assert.equal(q.flags & 0x0100, 0x0100); // recursion wanted
    assert.equal(q.end, q.len); // nothing after the question
  }
  assert.deepEqual(asks.map((a) => decodeQuestion(new URL(a.url).searchParams.get("dns")).host).sort(), ["other.example.org", "pay.example.com"]);
  // only the login check and these two questions went out
  assert.equal(seen.length, 3);
  assert.ok(seen[0].url.includes("identitytoolkit"));
});

test("bad requests are 400 and ask nothing", async () => {
  doh = (h) => reply(h, [A(1, 2, 3, 4)]);
  const bad = [
    {},
    { hosts: [] },
    { hosts: "example.com" },
    { hosts: ["a1.example.com", "a2.example.com", "a3.example.com", "a4.example.com", "a5.example.com", "a6.example.com"] },
    { hosts: ["localhost"] },
    { hosts: ["192.168.1.1"] },
    { hosts: ["3232235777"] },
    { hosts: ["https://example.com/x"] },
    { hosts: ["exa mple.com"] },
    { hosts: ["bad_name.example.com"] },
    { hosts: ["-a.example.com"] },
    { hosts: ["a..example.com"] },
    { hosts: [123] },
    { hosts: [null] },
    { hosts: ["a".repeat(64) + ".com"] },
    { hosts: [("a".repeat(60) + ".").repeat(5) + "com"] },
    { hosts: ["пример.рф"] },
  ];
  for (const b of bad) {
    const r = await call(b);
    assert.equal(r.status, 400, JSON.stringify(b).slice(0, 60));
    assert.equal(seen.filter((s) => s.url.includes("cloudflare-dns")).length, 0);
  }
});

test("a punycode name is fine", async () => {
  doh = (h) => reply(h, [A(1, 2, 3, 4)]);
  assert.equal((await call({ hosts: ["xn--e1afmkfd.xn--p1ai"] })).status, 200);
});

test("a body that is not JSON does not crash the Worker into a 200", async () => {
  doh = (h) => reply(h, [A(1, 2, 3, 4)]);
  const r = await call(null, { raw: "not json" });
  assert.notEqual(r.status, 200);
});

test("20 checks a minute per person, then 429; another person is not held back", async () => {
  doh = (h) => reply(h, [A(1, 2, 3, 4)]);
  const uid = fresh();
  for (let i = 0; i < 20; i++) assert.equal((await call({ hosts: ["example.com"] }, { uid })).status, 200);
  assert.equal((await call({ hosts: ["example.com"] }, { uid })).status, 429);
  assert.equal((await call({ hosts: ["example.com"] }, { uid: fresh() })).status, 200);
});

test("it needs no AI binding and no key", async () => {
  doh = (h) => reply(h, [A(1, 2, 3, 4)]);
  assert.equal((await call({ hosts: ["example.com"] }, { env: {} })).status, 200);
});
