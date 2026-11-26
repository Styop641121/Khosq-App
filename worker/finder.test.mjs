// Real Estate Finder. Runs with: cd worker && node --test   (fake Firebase, fake Firestore, fake FCM and a fake list.am; no real calls)
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

// ---- a list.am result page, written the way the real one is built (a card is a div with a link, price, title, facts, district)
const card = (id, o = {}) => {
  const photos = o.photos ?? [String(100000000 + Number(id)), String(200000000 + Number(id))];
  const src = o.src ?? 2;
  return (
    '<div class="category-data-list-grid-card">' +
    `<a href="/en/item/${id}?ld_src=${src}&amp;ld_pos=3" data-testid="favorite-ad-card-${id}" data-tslider="${photos.join(",")}" class="x">` +
    `<img src="//img.list.am/g/${String(photos[0]).slice(-3)}/${photos[0]}.webp" data-post-card-photo="1">` +
    `<div class="p">${o.price ?? "<span>$</span>95,000"}</div>` +
    `<div class="l">${o.title ?? "3-room apartment, Kentron"}</div>` +
    `<div class="at">${o.at ?? "3 rm., 85 sq.m., 3/8 floor"}</div>` +
    `<div class="at category-data-list-card__location">${o.loc ?? "Kentron"}</div>` +
    "</a></div>"
  );
};
const page = (cards, pad = 30000) => "<html><body>" + "<div>".repeat(1) + " ".repeat(pad) + cards.join("\n") + "</body></html>";

// ---- the fake world. Every test starts a minute later than the one before, so the per-minute limit of one test never counts against the next.
const realNow = Date.now.bind(Date);
let shift = 0;
Date.now = () => realNow() + shift;
function world({ uid = "U1", docs = {}, tokens = { U1: "FCM1", U2: "FCM2" }, listam = null, fsStatus = {} } = {}) {
  shift += 61000;
  const log = { listam: [], fcm: [], patches: [], fsGets: 0, deleted: [] };
  const w = { docs, log, tokens, uid, listam: listam ?? (() => new Response(page([card(1), card(2)]), { status: 200 })) };
  globalThis.fetch = async (url, opts = {}) => {
    url = String(url);
    const ok = (j) => new Response(JSON.stringify(j), { status: 200 });
    if (url.includes("identitytoolkit")) return w.uid ? ok({ users: [{ localId: w.uid }] }) : new Response("{}", { status: 400 });
    if (url.includes("oauth2.googleapis.com")) return ok({ access_token: "t", expires_in: 3600 });
    if (url.startsWith("https://www.list.am")) {
      log.listam.push(url);
      return w.listam(url);
    }
    if (url.startsWith("https://fcm.googleapis.com")) {
      log.fcm.push(JSON.parse(opts.body).message);
      return fsStatus.fcm ? new Response("{}", { status: fsStatus.fcm }) : ok({});
    }
    let m = /\/documents\/tokens\/([^/?]+)$/.exec(url);
    if (m) return w.tokens[m[1]] ? ok({ fields: { fcmToken: { stringValue: w.tokens[m[1]] } } }) : new Response("{}", { status: 404 });
    if (/\/documents\/finder\?/.test(url)) {
      return ok({ documents: Object.entries(w.docs).map(([id, d]) => ({ name: "projects/demo/databases/(default)/documents/finder/" + id, fields: d.fields })) });
    }
    m = /\/documents\/finder\/([^/?]+)(\?.*)?$/.exec(url);
    if (m) {
      const id = decodeURIComponent(m[1]);
      if (opts.method === "DELETE") {
        const had = !!w.docs[id];
        delete w.docs[id];
        log.deleted.push(id);
        return had ? ok({}) : new Response("{}", { status: 404 });
      }
      if (opts.method === "PATCH") {
        const q = new URL(url).searchParams;
        const must = q.get("currentDocument.updateTime");
        const cur = w.docs[id];
        if (fsStatus.patch) return new Response("{}", { status: fsStatus.patch });
        if (must && (!cur || cur.updateTime !== must)) return new Response("{}", { status: 400 });
        const fields = JSON.parse(opts.body).fields;
        const names = q.getAll("updateMask.fieldPaths");
        assert.deepEqual(Object.keys(fields).sort(), [...names].sort(), "the mask names exactly the written fields");
        const next = { fields: { ...(cur?.fields ?? {}), ...fields }, updateTime: "T" + (log.patches.length + 1) };
        w.docs[id] = next;
        log.patches.push({ id, names, must });
        return ok(next);
      }
      w.log.fsGets++;
      const d = w.docs[id];
      return d ? ok({ fields: d.fields, updateTime: d.updateTime }) : new Response("{}", { status: 404 });
    }
    throw new Error("unexpected " + url);
  };
  return w;
}
const str = (v) => ({ stringValue: JSON.stringify(v) });
const doc = (cfg, extra = {}) => ({
  updateTime: "T0",
  fields: { cfg: str(cfg), seen: str(extra.seen ?? []), hits: str(extra.hits ?? []), st: str(extra.st ?? { run: 0 }) },
});
const CFG = { on: true, cat: "sale", region: 1, min: 0, max: 100000, cur: "USD", rooms: 2, lang: "en" };
const read = (w, id, f) => JSON.parse(w.docs[id].fields[f].stringValue);
const post = (path, body = {}, env = ENV) =>
  worker.fetch(new Request("https://w.example" + path, { method: "POST", headers: { Authorization: "Bearer x" }, body: JSON.stringify(body) }), env);
const cron = () => worker.scheduled({}, ENV, { waitUntil: (p) => (cron.last = p) }).then(() => cron.last);

// ---------------------------------------------------------------------------------------------- saving a search
test("saving a search: the address asked from list.am carries every filter", async () => {
  const w = world();
  const r = await post("/finder/save", { cfg: CFG });
  assert.equal(r.status, 200);
  const u = new URL(w.log.listam[0]);
  assert.equal(u.origin + u.pathname, "https://www.list.am/en/category/60");
  assert.equal(u.searchParams.get("n"), "1");
  assert.equal(u.searchParams.get("crc"), "1");
  assert.equal(u.searchParams.get("price2"), "100000");
  assert.equal(u.searchParams.has("price1"), false);
  assert.deepEqual(u.searchParams.getAll("_a4[]"), ["2", "3", "4", "5", "6", "7", "8"]);
  assert.equal(u.searchParams.get("srt"), "3");
});

test("rent, Armenian, AMD, a studio and a district", async () => {
  const w = world();
  await post("/finder/save", { cfg: { on: true, cat: "rent", region: 8, min: 100000, max: 300000, cur: "AMD", rooms: 1, lang: "hy" } });
  const u = new URL(w.log.listam[0]);
  assert.equal(u.pathname, "/category/56"); // Armenian has no language prefix
  assert.equal(u.searchParams.get("n"), "8");
  assert.equal(u.searchParams.get("crc"), "0");
  assert.equal(u.searchParams.get("price1"), "100000");
  assert.deepEqual(u.searchParams.getAll("_a4[]"), ["99", "1", "2", "3", "4", "5", "6", "7", "8"]);
});

test("a search with odd values is made safe before it is used or stored", async () => {
  const w = world();
  const r = await post("/finder/save", { cfg: { on: true, cat: "x", region: 99, min: 500, max: 100, cur: "EUR", rooms: 50, lang: "zz" } });
  const j = await r.json();
  assert.deepEqual(j.cfg, { on: true, cat: "sale", region: 1, min: 500, max: 500, cur: "USD", rooms: 8, lang: "hy" });
  assert.equal(read(w, "U1", "cfg").region, 1);
});

test("the first look only learns what is already there: no push, three samples", async () => {
  const w = world({ listam: () => new Response(page([card(10), card(11), card(12), card(13), card(14)])) });
  const j = await (await post("/finder/save", { cfg: CFG })).json();
  assert.equal(w.log.fcm.length, 0);
  assert.equal(j.st.ok, true);
  assert.equal(j.st.note, "baseline");
  assert.equal(j.st.found, 5);
  assert.deepEqual(read(w, "U1", "seen"), ["10", "11", "12", "13", "14"]);
  assert.deepEqual(j.hits.map((h) => h.id), ["10", "11", "12"]);
  assert.equal(j.hits[0].b, 1);
});

test("saving with the search off does not touch list.am", async () => {
  const w = world();
  const j = await (await post("/finder/save", { cfg: { ...CFG, on: false } })).json();
  assert.equal(w.log.listam.length, 0);
  assert.equal(j.cfg.on, false);
});

test("a different search starts over: new samples, a new baseline", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["7", "8"], hits: [{ id: "7", ts: 5 }] }) } });
  const j = await (await post("/finder/save", { cfg: { ...CFG, region: 8 } })).json();
  assert.equal(read(w, "U1", "cfg").region, 8);
  assert.deepEqual(read(w, "U1", "seen"), ["1", "2"]); // what the fake page shows now
  assert.equal(j.st.note, "baseline");
  assert.deepEqual(j.hits.map((h) => h.id), ["1", "2"]);
});

test("the same search saved again keeps what it found and does not look again", async () => {
  const hits = [{ id: "7", p: "$1", t: "t", a: "", l: "", ph: [], ts: 5 }];
  const w = world({ docs: { U1: doc(CFG, { seen: ["7", "8"], hits }) } });
  const j = await (await post("/finder/save", { cfg: CFG })).json();
  assert.equal(w.log.listam.length, 0);
  assert.deepEqual(read(w, "U1", "seen"), ["7", "8"]);
  assert.equal(j.hits[0].id, "7");
});

test("pause keeps everything; resume learns the page silently and keeps the hits", async () => {
  const hits = [{ id: "7", p: "$1", t: "t", a: "", l: "", ph: [], ts: 5 }];
  const w = world({ docs: { U1: doc(CFG, { seen: ["7", "8"], hits }) } });
  let j = await (await post("/finder/save", { cfg: { ...CFG, on: false } })).json();
  assert.equal(j.cfg.on, false);
  assert.equal(w.log.listam.length, 0);
  assert.deepEqual(read(w, "U1", "seen"), ["7", "8"]);
  w.listam = () => new Response(page([card(30), card(31), card(7)]));
  j = await (await post("/finder/save", { cfg: CFG })).json();
  assert.equal(w.log.fcm.length, 0); // what appeared during the pause is not pushed
  assert.equal(j.cfg.on, true);
  assert.deepEqual(read(w, "U1", "seen"), ["30", "31", "7"]);
  assert.equal(j.hits[0].id, "7"); // the old hits stay
  assert.equal(j.st.note, "baseline");
});

test("clear removes everything kept about the search", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["1"] }) } });
  const j = await (await post("/finder/clear")).json();
  assert.equal(j.cfg, null);
  assert.deepEqual(w.log.deleted, ["U1"]);
  assert.equal(w.docs.U1, undefined);
  const again = await post("/finder/clear"); // nothing there any more: still fine
  assert.equal(again.status, 200);
});

// ---------------------------------------------------------------------------------------------- reading the page
test("the page is read like a person reads it: price, title, facts, district, photos", async () => {
  const html = page([
    card(5, { price: "<span>$</span>95,000", title: "3-room &amp; bright, Kentron", at: "3 rm., 85 sq.m., 3/8 floor", loc: "Kentron", photos: ["111111111", "222222222", "333333333"] }),
    card(6, { price: "28,600,000<span>֏</span>", title: "Studio", at: "1 rm., 30 sq.m.", loc: "Arabkir" }),
    card(7, { price: "180,000<span>֏</span>monthly", title: "Rent", loc: "Avan" }),
  ]);
  const w = world({ listam: () => new Response(html) });
  const j = await (await post("/finder/save", { cfg: CFG })).json();
  const h = Object.fromEntries(j.hits.map((x) => [x.id, x]));
  assert.equal(h["5"].p, "$95,000");
  assert.equal(h["5"].t, "3-room & bright, Kentron");
  assert.equal(h["5"].a, "3 rm., 85 sq.m., 3/8 floor");
  assert.equal(h["5"].l, "Kentron");
  assert.deepEqual(h["5"].ph, ["111111111", "222222222", "333333333"]);
  assert.equal(h["6"].p, "28,600,000֏");
  assert.equal(h["7"].p, "180,000֏ monthly");
  assert.equal(w.log.fcm.length, 0);
});

test("paid Top Ads (they ignore the filters) are left out", async () => {
  const w = world({ listam: () => new Response(page([card(1, { src: 4 }), card(2, { src: 2 }), card(3, { src: 4 })])) });
  const j = await (await post("/finder/save", { cfg: CFG })).json();
  assert.equal(j.st.found, 1);
  assert.deepEqual(read(w, "U1", "seen"), ["2"]);
});

test("a card without a price, or cut off by the end of what was read, is left out; the same ad twice counts once", async () => {
  const html = page([card(1, { price: "" }), card(2), card(2), card(3)]) + '<div class="category-data-list-grid-card"><a href="/en/item/4?ld_src=2"><div class="p">$1';
  world({ listam: () => new Response(html) });
  const j = await (await post("/finder/save", { cfg: CFG })).json();
  assert.equal(j.st.found, 2);
});

test("only the beginning of a big page is read", async () => {
  const big = page([card(1)]) + "x".repeat(2_000_000);
  let pulled = 0;
  const stream = new ReadableStream({
    pull(c) {
      pulled++;
      c.enqueue(new TextEncoder().encode(big.slice((pulled - 1) * 65536, pulled * 65536)));
    },
  });
  world({ listam: () => new Response(stream) });
  const j = await (await post("/finder/save", { cfg: CFG })).json();
  assert.equal(j.st.found, 1);
  assert.ok(pulled < 12, "stopped reading early (" + pulled + " pulls)");
});

// ---------------------------------------------------------------------------------------------- the cron
test("the cron: a new ad is pushed once, remembered, and not pushed again", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["1", "2"], hits: [] }) } });
  w.listam = () => new Response(page([card(3, { price: "<span>$</span>95,000", at: "3 rm., 85 sq.m., 3/8 floor", loc: "Kentron" }), card(1), card(2)]));
  await cron();
  assert.equal(w.log.fcm.length, 1);
  const m = w.log.fcm[0];
  assert.equal(m.token, "FCM1");
  assert.equal(m.android.priority, "HIGH");
  assert.equal(m.data.type, "finder");
  assert.equal(m.data.count, "1");
  assert.equal(m.data.id, "3");
  assert.equal(m.data.title, "KHOSQ Finder");
  assert.match(m.data.body, /New listing: \$95,000 · Kentron · 3 rm\./);
  assert.deepEqual(read(w, "U1", "seen").slice(0, 3), ["3", "1", "2"]);
  assert.equal(read(w, "U1", "hits")[0].id, "3");
  assert.equal(read(w, "U1", "st").fresh, 1);
  // the cron wrote only what it owns; the search itself is untouched
  assert.deepEqual(w.log.patches[0].names.sort(), ["hits", "seen", "st"]);
  // five minutes later nothing is new
  w.docs.U1.fields.st = str({ run: 1 });
  w.log.fcm.length = 0;
  await cron();
  assert.equal(w.log.fcm.length, 0);
});

test("the push speaks the person's language and counts several ads", async () => {
  const w = world({ docs: { U1: doc({ ...CFG, lang: "ru" }, { seen: ["1"] }) } });
  w.listam = () => new Response(page([card(4), card(3), card(1)]));
  await cron();
  assert.match(w.log.fcm[0].data.body, /^2 новых объявлений\. /);
  const w2 = world({ docs: { U1: doc({ ...CFG, lang: "hy" }, { seen: ["1"] }) } });
  await cron();
  assert.match(w2.log.fcm[0].data.body, /^Նոր հայտարարություն: /);
});

test("the cron leaves people alone when their search is off, or was looked at a minute ago", async () => {
  const w = world({ docs: { U1: doc({ ...CFG, on: false }), U2: doc(CFG, { seen: ["1"], st: { run: Date.now() - 60000 } }) } });
  await cron();
  assert.equal(w.log.listam.length, 0);
});

test("two people with the same search cost list.am one request", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["1"] }), U2: doc(CFG, { seen: ["1"] }) } });
  w.listam = () => new Response(page([card(9), card(1)]));
  await cron();
  assert.equal(w.log.listam.length, 1);
  assert.equal(w.log.fcm.length, 2);
  assert.deepEqual(w.log.fcm.map((m) => m.token).sort(), ["FCM1", "FCM2"]);
});

test("at most eight people per run, the one waiting longest first", async () => {
  const docs = {};
  const tokens = {};
  for (let i = 1; i <= 12; i++) {
    docs["P" + i] = doc({ ...CFG, region: 1 + (i % 13) }, { seen: ["1"], st: { run: 1000 + i } });
    tokens["P" + i] = "T" + i;
  }
  const w = world({ docs, tokens });
  w.listam = () => new Response(page([card(1)]));
  await cron();
  const touched = new Set(w.log.patches.map((p) => p.id));
  assert.equal(touched.size, 8);
  for (const id of ["P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8"]) assert.ok(touched.has(id), id);
});

test("list.am refusing us is written down honestly, nothing is pushed, nothing is forgotten", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["1", "2"], hits: [{ id: "1" }] }) } });
  w.listam = () => new Response("denied", { status: 403 });
  await cron();
  assert.equal(w.log.fcm.length, 0);
  const st = read(w, "U1", "st");
  assert.equal(st.ok, false);
  assert.equal(st.note, "http 403");
  assert.deepEqual(read(w, "U1", "seen"), ["1", "2"]);
});

test("a challenge page, a short page and an unknown page are told apart", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["1"] }) } });
  w.listam = () => new Response("<html>Just a moment... cf-chl-bypass</html>");
  await cron();
  assert.equal(read(w, "U1", "st").note, "challenge");
  w.docs.U1.fields.st = str({ run: 0 });
  w.listam = () => new Response("<html>hi</html>");
  await cron();
  assert.equal(read(w, "U1", "st").note, "short");
  w.docs.U1.fields.st = str({ run: 0 });
  w.listam = () => new Response("<html>" + "a".repeat(30000) + "</html>");
  await cron();
  assert.equal(read(w, "U1", "st").note, "parse 0");
});

test("a network failure, a redirect to the home page: noted, never thrown", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["1"] }) } });
  w.listam = () => {
    throw new Error("boom");
  };
  await cron();
  assert.equal(read(w, "U1", "st").note, "net");
  w.docs.U1.fields.st = str({ run: 0 });
  w.listam = () => {
    const r = new Response(page([card(1)]));
    Object.defineProperty(r, "url", { value: "https://www.list.am/" });
    return r;
  };
  await cron();
  assert.equal(read(w, "U1", "st").note, "redirect");
});

test("a failed push is written down, and the ad is still remembered", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["1"] }) }, fsStatus: { fcm: 500 } });
  w.listam = () => new Response(page([card(2), card(1)]));
  await cron();
  assert.equal(read(w, "U1", "st").push, "fcm 500");
  assert.equal(read(w, "U1", "seen")[0], "2");
});

test("no phone token: noted; and one person's trouble does not stop the next person", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["1"], st: { run: 5 } }), U2: doc(CFG, { seen: ["1"], st: { run: 9 } }) }, tokens: { U2: "FCM2" } });
  w.listam = () => new Response(page([card(2), card(1)]));
  await cron();
  assert.equal(read(w, "U1", "st").push, "no token");
  assert.equal(w.log.fcm.length, 1);
  assert.equal(w.log.fcm[0].token, "FCM2");
});

test("the cron writes only if the document is unchanged since it was read", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["1"] }) } });
  w.listam = () => new Response(page([card(2), card(1)]));
  await cron();
  assert.equal(w.log.patches[0].must, "T0");
});

test("the cron without a service account, or a Firestore that fails, is silent", async () => {
  world();
  await worker.scheduled({}, {}, { waitUntil: (p) => (cron.last = p) });
  await cron.last;
  const w = world({ docs: { U1: doc(CFG) }, fsStatus: { patch: 500 } });
  w.listam = () => new Response(page([card(1)]));
  await cron();
  assert.ok(true);
});

// ---------------------------------------------------------------------------------------------- the routes
test("state: a new person has no search; a saved one comes back with its hits", async () => {
  world();
  let j = await (await post("/finder/state")).json();
  assert.equal(j.cfg, null);
  assert.deepEqual(j.hits, []);
  world({ docs: { U1: doc(CFG, { hits: [{ id: "7", p: "$1", t: "t", a: "", l: "", ph: [], ts: 5 }] }) } });
  j = await (await post("/finder/state")).json();
  assert.equal(j.cfg.region, 1);
  assert.equal(j.hits[0].id, "7");
  assert.ok(j.now > 0);
});

test("check: shows the newest ads in the app, with no push, and not more than twice a minute", async () => {
  const w = world({ docs: { U1: doc(CFG, { seen: ["1"] }) } });
  w.listam = () => new Response(page([card(2), card(1)]));
  let j = await (await post("/finder/check")).json();
  assert.equal(j.hits[0].id, "2");
  assert.equal(w.log.fcm.length, 0);
  assert.equal(j.st.fresh, 1);
  j = await (await post("/finder/check")).json();
  assert.equal(j.throttled, true);
  assert.equal(w.log.listam.length, 1);
});

test("check without a search does nothing", async () => {
  const w = world();
  const j = await (await post("/finder/check")).json();
  assert.equal(j.cfg, null);
  assert.equal(w.log.listam.length, 0);
});

test("the routes need a signed-in person and a service account; strangers get nothing", async () => {
  world({ uid: null });
  assert.equal((await post("/finder/state")).status, 401);
  world();
  assert.equal((await post("/finder/state", {}, { API_KEY: "k" })).status, 501);
  assert.equal((await post("/finder/nope")).status, 404);
  assert.equal((await post("/finder/save", { cfg: "x" })).status, 400);
});

test("the routes are rate limited", async () => {
  world({ uid: "RATE" });
  let last = 0;
  for (let i = 0; i < 25; i++) last = (await post("/finder/state")).status;
  assert.equal(last, 429);
});
