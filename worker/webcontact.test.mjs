// The contact card of the web page (docs/verify.js, docs/contact.js, the pair digest in docs/e2e.js). The safety number and the eight pictures must be
// the very same ones the phone computes (data/Verify.kt, crypto/E2E.kt), so the vectors below were made by a separate program (Python's hashlib,
// written from the phone's description) and this page's code must give the same. Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { CODE_LENGTH, EMOJIS, digestOf, emojis, hex, numberText, verifyState } from "../docs/verify.js";
import { ONLINE_MS, MAX_GROUPS, commonGroups, formatSeen, infoOf, makeContact, millisOf, statusOf } from "../docs/contact.js";
import { makeE2E } from "../docs/e2e.js";
import { MORE } from "../docs/words.js";

const VECTORS = [
 {
  "a": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaaa",
  "b": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbbb",
  "hex": "09b2f74baddbc76452ce30d92b4a3a1dbda5b492ab728ce1803249afcfd14724",
  "number": "09B2 F74B ADDB C764 52CE 30D9 2B4A 3A1D",
  "cps": [
   "0x1f42d",
   "0x1f353",
   "0x1f42e",
   "0x1f4f7",
   "0x1f40d",
   "0x1f3a9",
   "0x1f4f7",
   "0x1f353"
  ]
 },
 {
  "a": "zzz",
  "b": "aaa",
  "hex": "f3bb3d883928d17a78a4c9419e8e4796077ca5a4c8425263ff6cd23f72e86422",
  "number": "F3BB 3D88 3928 D17A 78A4 C941 9E8E 4796",
  "cps": [
   "0x1f48e",
   "0x1f451",
   "0x26a1",
   "0x1f697",
   "0x1f36a",
   "0x1f439",
   "0x1f332",
   "0x1f344"
  ]
 },
 {
  "a": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1BFzxBXa0Ig+b7niqogFrFfeJXM2ltiUaHJ88qMDaLHPiGQEfHqEtg4FgLHP8Pf5QKliqvMO3zWkhE5/BdHIEw==",
  "b": "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy+/==",
  "hex": "ce3914c80748dad7ed4f091bbe4acb4a88026e081bbc5ed98b1cc1b5712b7803",
  "number": "CE39 14C8 0748 DAD7 ED4F 091B BE4A CB4A",
  "cps": [
   "0x1f680",
   "0x1f33d",
   "0x1f332",
   "0x1f420",
   "0x1f511",
   "0x1f436",
   "0x1f351",
   "0x1f428"
  ]
 },
 {
  "a": "A",
  "b": "a",
  "hex": "81acaafba961bb831ec40eb965155e3486392c60cfb8c75150beaf2caffc244a",
  "number": "81AC AAFB A961 BB83 1EC4 0EB9 6515 5E34",
  "cps": [
   "0x1f355",
   "0x1f347",
   "0x1f511",
   "0x2b50",
   "0x26f5",
   "0x1f3a9",
   "0x1f335",
   "0x1f369"
  ]
 },
 {
  "a": "same",
  "b": "same",
  "hex": "5b493334adcaf695c0f61eadd65b50e1c3ad0d93c32270e3a7aa051c0e61a66e",
  "number": "5B49 3334 ADCA F695 C0F6 1EAD D65B 50E1",
  "cps": [
   "0x1f433",
   "0x1f6b2",
   "0x1f332",
   "0x1f680",
   "0x1f438",
   "0x1f981",
   "0x1f4f7",
   "0x1f981"
  ]
 }
];

const PHONE = new URL("../app/src/main/java/am/khosq/app/", import.meta.url);
const phone = (rel) => (existsSync(new URL(rel, PHONE)) ? readFileSync(new URL(rel, PHONE), "utf8") : null);
const DOCS = new URL("../docs/", import.meta.url);
const docs = (f) => readFileSync(new URL(f, DOCS), "utf8");

// ---------------------------------------------------------------------------------------------- the arithmetic
test("the safety number and the eight pictures are the ones an independent program makes", async () => {
  for (const v of VECTORS) {
    const d = await digestOf(v.a, v.b);
    assert.equal(hex(d), v.hex, v.a.slice(0, 20));
    assert.equal(numberText(d), v.number);
    assert.deepEqual(emojis(d).map((e) => "0x" + e.codePointAt(0).toString(16)), v.cps);
    // both phones get the same, whoever is "a"
    assert.equal(hex(await digestOf(v.b, v.a)), v.hex, "the order of the two keys does not matter");
  }
});

test("64 different pictures, 8 in the code, the number is 8 groups of 4 hex digits", async () => {
  assert.equal(EMOJIS.length, 64);
  assert.equal(new Set(EMOJIS).size, 64);
  assert.equal(CODE_LENGTH, 8);
  const d = await digestOf("a", "b");
  assert.match(numberText(d), /^([0-9A-F]{4} ){7}[0-9A-F]{4}$/);
  assert.equal(emojis(d).length, 8);
  assert.equal(hex(d).length, 64);
  // every picture is a single code point (no joined or toned pictures)
  for (const e of EMOJIS) assert.equal([...e].length, 1, e);
});

test("the keys are compared like the phone does: by UTF-16 code units, the smaller text first", async () => {
  const x = await digestOf("A", "a"); // "A" < "a"
  const y = await digestOf("a", "A");
  assert.equal(hex(x), hex(y));
  const ref = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode("Aa")));
  assert.equal(hex(x), hex(ref));
});

test("verifyState: never confirmed or no key = none, the same digest = verified, another digest = changed", () => {
  assert.equal(verifyState("", "abc"), "none");
  assert.equal(verifyState(undefined, "abc"), "none");
  assert.equal(verifyState("abc", null), "none");
  assert.equal(verifyState("", null), "none");
  assert.equal(verifyState("abc", "abc"), "verified");
  assert.equal(verifyState("abc", "abd"), "changed");
});

test("the 64 pictures are the phone's, in the phone's order (compared with data/Verify.kt, when it is here)", { skip: phone("data/Verify.kt") === null }, () => {
  const kt = phone("data/Verify.kt");
  const block = kt.slice(kt.indexOf("val EMOJIS"), kt.indexOf(".map { cp ->"));
  const cps = [...block.matchAll(/0x([0-9A-F]+)/g)].map((m) => m[1].toLowerCase());
  assert.equal(cps.length, 64);
  assert.deepEqual(EMOJIS.map((e) => e.codePointAt(0).toString(16)), cps);
  assert.match(kt, /const val CODE_LENGTH = 8/);
  assert.match(kt, /for \(i in 0 until 16\) sb\.append\(String\.format\(Locale\.ROOT, "%02X"/, "the number is the first 16 bytes, upper case");
  assert.match(kt, /sb\.chunked\(4\)\.joinToString\(" "\)/);
  assert.match(kt, /for \(i in 0 until 6\) bits = \(bits shl 8\) or/, "the pictures come from the first 6 bytes");
  assert.match(kt, /val shift = 42 - 6 \* i/);
  assert.match(kt, /MessageDigest\.getInstance\("SHA-256"\)/);
  assert.match(kt, /val first = if \(a <= b\) a else b/);
  const e2e = phone("crypto/E2E.kt");
  assert.match(e2e, /VerifyLogic\.digest\(mine\.pubText, theirs\)/, "the phone digests MY key text and the pinned text of the other");
  assert.match(e2e, /Prefs\.putStringSync\("ver\.\$peer", VerifyLogic\.hex\(d\)\)/, "and saves the hex under ver.<peer>");
  assert.match(e2e, /Prefs\.putStringSync\("pkchg\.\$peer", ""\)/, "confirming ends the 'key changed' warning");
});

// ---------------------------------------------------------------------------------------------- what the database says
test("millisOf takes every shape of a time and gives 0 for nothing", () => {
  assert.equal(millisOf({ toMillis: () => 1234 }), 1234);
  assert.equal(millisOf({ toDate: () => new Date(5678) }), 5678);
  assert.equal(millisOf(9999), 9999);
  assert.equal(millisOf({ seconds: 10, nanoseconds: 5_000_000 }), 10005);
  assert.equal(millisOf({ _seconds: 3 }), 3000);
  for (const v of [null, undefined, "x", {}, NaN, -5, 0, [], true]) assert.equal(millisOf(v), 0, String(v));
});

test("infoOf: the time of the last sign of life, hidden when the person hides it; the 'about' line as written", () => {
  assert.deepEqual(infoOf({ lastSeen: { toMillis: () => 500 }, about: "Բարև", hideSeen: false }), { seen: 500, about: "Բարև", hide: false });
  assert.deepEqual(infoOf({ lastSeen: { toMillis: () => 500 }, about: "x", hideSeen: true }), { seen: 0, about: "x", hide: true }, "hidden: no time, but the 'about' stays");
  assert.deepEqual(infoOf(null), { seen: 0, about: "", hide: false });
  assert.deepEqual(infoOf({ about: 5, hideSeen: "yes" }), { seen: 0, about: "", hide: false }, "only a real true hides; 'about' must be text");
  assert.deepEqual(infoOf("text"), { seen: 0, about: "", hide: false });
});

test("statusOf: online for less than 100 seconds, then 'last seen', nothing when there is no time", () => {
  assert.equal(ONLINE_MS, 100_000);
  const now = 1_000_000_000;
  assert.deepEqual(statusOf({ seen: now - 99_999 }, now), { kind: "online", ts: now - 99_999 });
  assert.deepEqual(statusOf({ seen: now - 100_000 }, now), { kind: "seen", ts: now - 100_000 });
  assert.deepEqual(statusOf({ seen: now + 5000 }, now), { kind: "online", ts: now + 5000 }, "a clock a little ahead still says online, like the phone");
  assert.equal(statusOf({ seen: 0 }, now).kind, "none");
  assert.equal(statusOf(null, now).kind, "none");
  assert.equal(statusOf(undefined, now).kind, "none");
});

test("formatSeen: the time for today, the day and month and the time for another day", () => {
  const at = (y, mo, d, h, mi) => new Date(y, mo - 1, d, h, mi).getTime();
  assert.equal(formatSeen(at(2026, 11, 27, 9, 5), at(2026, 11, 27, 23, 59)), "09:05");
  assert.equal(formatSeen(at(2026, 11, 26, 23, 59), at(2026, 11, 27, 0, 1)), "26.11 23:59", "yesterday, even a minute ago");
  assert.equal(formatSeen(at(2026, 1, 2, 7, 0), at(2026, 11, 27, 12, 0)), "02.01 07:00", "zero padding");
  assert.equal(formatSeen(at(2025, 11, 27, 12, 0), at(2026, 11, 27, 12, 0)), "27.11 12:00", "the same day a year ago is not today");
  assert.equal(formatSeen(at(2025, 12, 31, 22, 30), at(2026, 1, 1, 8, 0)), "31.12 22:30");
});

test("commonGroups: the groups both people are in, not saved messages, not one-to-one chats, at most ten", () => {
  const chats = [
    { id: "g1", group: true, title: "Մեկ", members: ["me", "p"] },
    { id: "g2", group: true, title: "Երկու", members: ["me"] },
    { id: "d1", group: false, members: ["me", "p"] },
    { id: "s1", group: true, saved: true, title: "Saved", members: ["me", "p"] },
    { id: "g3", group: true, members: ["me", "p"] },
    { id: "g4", group: true, title: "Չորս", members: "p" },
    null,
    { id: "g5", group: true, title: 7, members: ["p"] },
  ];
  assert.deepEqual(commonGroups(chats, "p"), [{ id: "g1", title: "Մեկ" }, { id: "g3", title: "" }, { id: "g5", title: "" }]);
  const many = Array.from({ length: 15 }, (_, i) => ({ id: "x" + i, group: true, title: "T" + i, members: ["p"] }));
  assert.equal(MAX_GROUPS, 10);
  assert.equal(commonGroups(many, "p").length, 10);
  assert.deepEqual(commonGroups(undefined, "p"), []);
});

/** A pretend Firestore with one watcher on users/<peer>. */
function fakeStore() {
  const w = { calls: [], next: null, error: null, opts: null, stopped: false };
  const fsM = {
    doc: (db, ...path) => ({ path: path.join("/") }),
    onSnapshot: (ref, next, error) => {
      w.calls.push(ref.path);
      w.next = next;
      w.error = error;
      return () => (w.stopped = true);
    },
  };
  const snap = (data) => ({ exists: () => data !== null, data: (o) => ((w.opts = o), data) });
  return { w, f: { db: {}, fsM }, snap };
}

test("watch follows users/<peer> and gives {seen, about, hide}; a missing document is nothing; the stop function stops it", () => {
  const s = fakeStore();
  const c = makeContact({ f: s.f, e2e: {} });
  const got = [];
  const errs = [];
  const stop = c.watch("PEER", (i) => got.push(i), (e) => errs.push(e.message));
  assert.deepEqual(s.w.calls, ["users/PEER"]);
  s.w.next(s.snap({ lastSeen: { toMillis: () => 777 }, about: "Բարև", hideSeen: false }));
  assert.deepEqual(s.w.opts, { serverTimestamps: "estimate" }, "a time the server has not filled in yet is estimated, like the phone");
  s.w.next(s.snap(null));
  s.w.next({ exists: () => true, data: () => { throw new Error("odd"); } });
  assert.deepEqual(got, [{ seen: 777, about: "Բարև", hide: false }, { seen: 0, about: "", hide: false }, { seen: 0, about: "", hide: false }]);
  s.w.error(new Error("denied"));
  assert.deepEqual(errs, ["denied"]);
  stop();
  assert.equal(s.w.stopped, true);
});

// ---------------------------------------------------------------------------------------------- the verification
const b64 = (u8) => Buffer.from(u8).toString("base64");
async function newKey() {
  const k = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
  return b64(new Uint8Array(await crypto.subtle.exportKey("spki", k.publicKey)));
}

async function world() {
  const mine = await newKey();
  const theirs = await newKey();
  const server = new Map([["BOB", theirs]]); // what the server says about people
  const store = new Map();
  const pins = { get: async (u) => store.get(u), set: async (u, v) => void store.set(u, v) };
  const e2e = makeE2E({ uid: "ME", key: null, pub: mine, publicOf: async (u) => server.get(u) || null, pins });
  const f = fakeStore().f;
  const c = makeContact({ f, e2e });
  return { mine, theirs, server, store, e2e, c };
}

test("the verification: no key = not ready; with the key the number and pictures are the ones of the two keys", async () => {
  const w = await world();
  assert.deepEqual(await w.c.view("NOBODY"), { ready: false, state: "none", number: "", pictures: [], changed: false });
  assert.equal((await w.c.view("ME")).ready, false, "never for myself");
  assert.equal((await w.c.view("")).ready, false);
  const v = await w.c.view("BOB");
  const d = await digestOf(w.mine, w.theirs);
  assert.equal(v.ready, true);
  assert.equal(v.state, "none");
  assert.equal(v.number, numberText(d));
  assert.deepEqual(v.pictures, emojis(d));
  assert.equal(v.changed, false);
  assert.equal(w.store.get("BOB"), w.theirs, "the key was remembered when first seen (the phone's pk.<peer>)");
});

test("the person's side computes the same number: swap the roles and nothing changes", async () => {
  const a = await newKey();
  const b = await newKey();
  const make = async (me, other) => {
    const store = new Map();
    const e2e = makeE2E({ uid: "X", key: null, pub: me, publicOf: async () => other, pins: { get: async (u) => store.get(u), set: async (u, v) => void store.set(u, v) } });
    return makeContact({ f: fakeStore().f, e2e });
  };
  const va = await (await make(a, b)).view("Y");
  const vb = await (await make(b, a)).view("Y");
  assert.equal(va.number, vb.number);
  assert.deepEqual(va.pictures, vb.pictures);
});

test("confirming saves the digest under ver.<peer> (as the phone does) and makes the state verified; removing takes it away", async () => {
  const w = await world();
  assert.equal(await w.c.confirm("NOBODY"), false, "no key: nothing to confirm");
  assert.equal(await w.c.confirm("BOB"), true);
  const d = await digestOf(w.mine, w.theirs);
  assert.equal(w.store.get("ver.BOB"), hex(d));
  assert.equal((await w.c.view("BOB")).state, "verified");
  await w.c.clear("BOB");
  assert.equal(w.store.get("ver.BOB"), "");
  assert.equal((await w.c.view("BOB")).state, "none");
});

test("a changed key ends the verification by itself, and confirming again ends the warning too", async () => {
  const w = await world();
  await w.c.confirm("BOB");
  assert.equal((await w.c.view("BOB")).state, "verified");
  // the server now holds another key for him: the page notices it (once per visit of the chat), remembers the new one and warns
  w.server.set("BOB", await newKey());
  assert.equal(await w.e2e.refreshPeer("BOB"), true);
  let v = await w.c.view("BOB");
  assert.equal(v.state, "changed");
  assert.equal(v.changed, true, "and the page's 'key changed' warning is up");
  // "Got it" ends the warning but is not a verification
  await w.c.acknowledge("BOB");
  v = await w.c.view("BOB");
  assert.deepEqual([v.state, v.changed], ["changed", false]);
  // the new pictures are compared and confirmed: verified again
  w.server.set("BOB", await newKey());
  await w.e2e.refreshPeer("BOB");
  assert.equal((await w.c.view("BOB")).changed, true);
  await w.c.confirm("BOB");
  v = await w.c.view("BOB");
  assert.deepEqual([v.state, v.changed], ["verified", false], "confirming clears the warning, like the phone's markVerified");
});

test("what the page remembers about verification does not touch the key records (no clash with a person's id)", async () => {
  const w = await world();
  await w.c.confirm("BOB");
  assert.equal(w.store.get("BOB"), w.theirs, "the key itself is still under the person's id");
  assert.deepEqual([...w.store.keys()].sort(), ["!BOB", "BOB", "ver.BOB"]);
});

test("never a number for myself, even if the server holds a key under my own id", async () => {
  const w = await world();
  w.server.set("ME", w.mine);
  assert.equal(await w.e2e.pairDigest("ME"), null);
  assert.equal((await w.c.view("ME")).ready, false);
});

/** A browser that refuses to keep anything (a full disk, a private window): the store's own set swallows the refusal, like store.js's pins.set. */
async function refusing() {
  const mine = await newKey();
  const theirs = await newKey();
  const kept = new Map();
  let allow = true;
  const pins = { get: async (u) => kept.get(u), set: async (u, v) => void (allow && kept.set(u, v)) };
  const e2e = makeE2E({ uid: "ME", key: null, pub: mine, publicOf: async (u) => (u === "BOB" ? theirs : null), pins });
  return { c: makeContact({ f: fakeStore().f, e2e }), kept, refuse: () => (allow = false), allow: () => (allow = true) };
}

test("a browser that will not keep the mark: confirming and removing say so (they throw), nothing looks verified", async () => {
  const w = await refusing();
  assert.equal((await w.c.view("BOB")).ready, true, "the key is kept while the browser still lets it");
  w.refuse();
  await assert.rejects(() => w.c.confirm("BOB"), /did not keep/);
  assert.equal((await w.c.view("BOB")).state, "none", "so it does not look verified");
  w.allow();
  assert.equal(await w.c.confirm("BOB"), true);
  assert.equal((await w.c.view("BOB")).state, "verified");
  w.refuse();
  await assert.rejects(() => w.c.clear("BOB"), /did not keep/);
  assert.equal((await w.c.view("BOB")).state, "verified", "the mark is still there, and the card says it could not be removed");
});

test("a browser that cannot keep the other person's key at all: no number is made from nothing", async () => {
  const w = await refusing();
  w.refuse();
  const v = await w.c.view("BOB");
  assert.deepEqual(v, { ready: false, state: "none", number: "", pictures: [], changed: false });
});

// ---------------------------------------------------------------------------------------------- the phone's wording and rules
test("the words borrowed from the phone are the phone's, in all three languages (compared with Strings.kt, when it is here)", { skip: phone("Strings.kt") === null }, () => {
  const kt = phone("Strings.kt");
  const phoneMap = new Map();
  for (const m of kt.matchAll(/map\["((?:[^"\\]|\\.)*)"\] = arrayOf\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)"\)/g)) phoneMap.set(m[1], [m[2], m[3]]);
  const same = {
    ctOnline: "առցանց", ctSeen: "վերջին անգամ՝ {0}", ctAbout: "Նկարագրություն", ctNoAbout: "Դեռ ոչինչ չի գրել", ctGroups: "Ընդհանուր խմբեր",
    ctNumber: "Անվտանգության թիվ", ctGotIt: "Հասկացա", ctVerNone: "Հաստատել զրուցակցին", ctVerOk: "Հաստատված զրուցակից", ctVerChanged: "Անվտանգության թիվը փոխվել է",
    ctConfirm: "Նշանները համընկնում են. հաստատել", ctReconfirm: "Նշանները համընկնում են. հաստատել նորից", ctClear: "Հանել հաստատումը",
    ctNumberNote: "Համեմատիր այս թիվը զրուցակցիդ հեռախոսի վրա. եթե նույնն է, ոչ ոք չի կարող միջամտել նամակներին։",
    ctNumberChanged: "Զրուցակցի անվտանգության թիվը փոխվել է։ Դա կարող է նշանակել, որ նա նոր հեռախոս է օգտագործում։ Համեմատիր թիվը նրա հետ։",
    ctNoKey: "Զրուցակցի բանալին դեռ չի ստացվել. բացիր զրույցը և փորձիր քիչ հետո։",
    ctVerOkText: "Դու և {0}-ը համեմատել եք նշանները, և դրանք համընկել են։ Եթե նրա կամ քո բանալին փոխվի, այս նշանը կվերանա։",
    ctVerChangedText: "Դու նրան հաստատել էիր, բայց հետո նրա (կամ քո) բանալին փոխվել է։ Դա կարող է նշանակել նոր հեռախոս, բայց նաև որ ինչ-որ մեկը կանգնել է ձեր միջև։ Զանգիր նրան և նորից համեմատեք նշանները։",
    ctVerNoneText: "Զանգիր {0}-ին կամ հանդիպեք և համեմատեք այս նշանները՝ նույն կարգով։ Եթե նույնն են, սեղմիր «Հաստատել»։ Այդպես կիմանաս, որ ձեր միջև ոչ ոք չկա։",
  };
  for (const [key, hy] of Object.entries(same)) {
    const p = phoneMap.get(hy);
    assert.ok(p, "the phone has: " + hy.slice(0, 40));
    assert.equal(MORE.hy[key], hy, key + " hy");
    assert.equal(MORE.en[key], p[0], key + " en");
    assert.equal(MORE.ru[key], p[1], key + " ru");
  }
});

test("the phone's rules for the card: 100 seconds is online, the time is HH:mm or dd.MM HH:mm, ten groups, the same fields of users/<id> (compared with the phone's code, when it is here)", { skip: phone("ui/Screens.kt") === null }, () => {
  const screens = phone("ui/Screens.kt");
  const repo = phone("data/Repository.kt");
  const comps = phone("ui/Components.kt");
  const sheet = phone("ui/Settings.kt");
  assert.match(screens, /peerSeen > 0L && clock - peerSeen < 100_000L -> tr\("առցանց"\)/);
  assert.match(screens, /peerSeen > 0L -> trf\("վերջին անգամ՝ \{0\}", formatSeen\(peerSeen\)\)/);
  assert.match(comps, /SimpleDateFormat\("dd\.MM HH:mm"/);
  assert.match(comps, /fun formatTime\(ts: Long\): String = SimpleDateFormat\("HH:mm"/);
  assert.match(repo, /d\?\.getBoolean\("hideSeen"\) \?: false/);
  assert.match(repo, /d\?\.getTimestamp\("lastSeen", DocumentSnapshot\.ServerTimestampBehavior\.ESTIMATE\)/);
  assert.match(repo, /UserInfo\(if \(hide\) 0L else \(t\?\.toDate\(\)\?\.time \?: 0L\), d\?\.getString\("about"\) \?: "", hide\)/);
  assert.match(sheet, /for \(g in commonGroups\.take\(10\)\)/);
  assert.match(screens, /chats\.filter \{ g -> g\.isGroup && !g\.saved && chat\.peerUid in g\.members \}/);
  assert.match(sheet, /if \(about\.isBlank\(\)\) tr\("Դեռ ոչինչ չի գրել"\) else about/);
  assert.match(sheet, /if \(changed && vstate != VerifyState\.CHANGED\)/, "the 'safety number changed' note shows only while the pictures' own warning does not");
});

// ---------------------------------------------------------------------------------------------- the words and the wiring
test("every word of the card exists in all three languages with the same {0} places, and each one is used", () => {
  const html = docs("index.html");
  const code = docs("contactui.js") + docs("shell.js");
  const ct = Object.keys(MORE.hy).filter((k) => /^ct[A-Z]/.test(k));
  assert.ok(ct.length >= 20, "found " + ct.length);
  for (const k of ct) {
    for (const l of ["hy", "en", "ru"]) assert.ok(typeof MORE[l][k] === "string" && MORE[l][k].length > 0, l + " " + k);
    const slots = (l) => (MORE[l][k].match(/\{\d\}/g) || []).join();
    assert.equal(slots("en"), slots("hy"), k);
    assert.equal(slots("ru"), slots("hy"), k);
    assert.ok(html.includes('"' + k + '"') || code.includes('"' + k + '"'), k + " is used");
  }
  assert.deepEqual(Object.keys(MORE.hy).sort(), Object.keys(MORE.en).sort());
  assert.deepEqual(Object.keys(MORE.hy).sort(), Object.keys(MORE.ru).sort());
});

test("the window's markup: a dialog named by the person, a button for every action, nothing is shown before it is known", () => {
  const html = docs("index.html");
  const tag = (id) => {
    const m = html.match(new RegExp("<(\\w+)\\b[^>]*\\bid=\"" + id + "\"[^>]*>"));
    assert.ok(m, "index.html has #" + id);
    return m[0];
  };
  assert.match(tag("contact"), /^<dialog\b/);
  assert.match(tag("contact"), /aria-labelledby="ctName"/);
  for (const id of ["ctVer", "ctVerBox", "ctNumBox", "ctGroupsBox", "ctChanged", "ctErr", "ctHandle", "ctStatus", "ctCode", "ctConfirm", "ctClear"]) assert.match(tag(id), /\bhidden\b/, id + " starts hidden");
  assert.match(tag("ctErr"), /role="alert"/);
  assert.match(tag("ctChanged"), /role="alert"/);
  assert.match(tag("chVer"), /\bhidden\b/);
  for (const id of ["ctMute", "ctBlock", "ctClose", "ctX", "ctAck"]) assert.match(tag(id), /^<button\b[^>]*type="button"/, id);
});

test("the card never writes to the database, and everything somebody else wrote goes in as text", () => {
  for (const f of ["contactui.js", "contact.js", "verify.js"]) {
    const src = docs(f);
    assert.doesNotMatch(src, /\b(setDoc|updateDoc|deleteDoc|addDoc|writeBatch|runTransaction)\b/, f + " writes nothing");
    assert.doesNotMatch(src, /innerHTML|insertAdjacentHTML|outerHTML|document\.write/, f + " builds no markup from text");
  }
  const ui = docs("contactui.js");
  assert.doesNotMatch(ui, /\.innerHTML/);
  const shell = docs("shell.js");
  assert.match(shell, /contactUi\.open\(S\.open\.id\)/, "the header opens the card of a one-to-one chat");
  assert.match(shell, /item\(t\("ctOpen"\)/, "and the chat menu has an entry for it");
});
