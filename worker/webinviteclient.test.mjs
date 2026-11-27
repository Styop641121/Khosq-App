// Group invitation links on the web page (docs/invite.js): reading a pasted link, the calls to the Worker (the phone's calls), what is told when
// a call fails, and the words. Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { TOKEN_RE, tokenFrom, explainKey, makeInvite, Failure } from "../docs/invite.js";
import { MORE } from "../docs/words.js";

const WORKER = "https://khosq-push.styoptev.workers.dev";
const CHAT = "Gabcdefghij1234567890";
const SIG = "ABCDEFGHIJKLMNOPQRSTUV"; // 22 letters, like the Worker's signature
const TOK = CHAT + ".0." + SIG;
const LINK = WORKER + "/j/" + TOK;

// ------------------------------------------------------------------------------------------------ reading a pasted link
test("the token is found in every form of a link, and only a link of the Worker's address counts", () => {
  assert.equal(tokenFrom(LINK, WORKER), TOK, "the link the Worker makes");
  assert.equal(tokenFrom("khosq://join/" + TOK, WORKER), TOK, "the phone's own form");
  assert.equal(tokenFrom(TOK, WORKER), TOK, "the bare token");
  assert.equal(tokenFrom("  \n" + LINK + "\n ", WORKER), TOK, "spaces and line breaks around it");
  assert.equal(tokenFrom("Join my group on KHOSQ: " + LINK + " see you there", WORKER), TOK, "inside a message");
  assert.equal(tokenFrom("Join: " + LINK + ".", WORKER), TOK, "a full stop that ends the sentence is not part of the token");
  assert.equal(tokenFrom("(" + LINK + "), ok?", WORKER), TOK);
  assert.equal(tokenFrom(WORKER + "/j/" + CHAT + ".12.A" + SIG.slice(1), WORKER), CHAT + ".12." + SIG);
  // not ours
  assert.equal(tokenFrom("https://evil.example/j/" + TOK, WORKER), null, "another address: the token is not sent anywhere");
  assert.equal(tokenFrom("https://khosq-push.styoptev.workers.dev.evil.example/j/" + TOK, WORKER), null, "a longer host that only starts like ours");
  assert.equal(tokenFrom("http://khosq-push.styoptev.workers.dev/j/" + TOK, WORKER), null, "not https");
  assert.equal(tokenFrom(WORKER + "/x/" + TOK, WORKER), null, "another path");
  assert.equal(tokenFrom(WORKER + "/j/", WORKER), null);
  assert.equal(tokenFrom("khosq://join/short", WORKER), null);
  assert.equal(tokenFrom(CHAT + ".0." + SIG.slice(1), WORKER), null, "a signature that is one letter short");
  assert.equal(tokenFrom(CHAT + ".x." + SIG, WORKER), null, "a version that is not a number");
  assert.equal(tokenFrom("abc.0." + SIG, WORKER), null, "a chat id that is too short");
  for (const x of ["", "   ", null, undefined, 5, {}, "hello world", "a".repeat(700)]) assert.equal(tokenFrom(x, WORKER), null, String(x).slice(0, 20));
  assert.equal(tokenFrom("a".repeat(20) + "\n" + LINK, WORKER), TOK, "a pasted text with the link on a later line");
  // the first link that is ours wins when there are several
  assert.equal(tokenFrom("https://evil.example/j/" + CHAT + ".9." + SIG + " " + LINK, WORKER), TOK);
  assert.ok(TOKEN_RE.test(TOK));
});

test("a very long text is not searched (it is not a link, it is a pasted essay)", () => {
  const filler = "word ".repeat(130); // 650 characters
  assert.equal(tokenFrom(filler + LINK, WORKER), null);
  assert.equal(tokenFrom(LINK + " " + filler, WORKER), null);
  assert.equal(tokenFrom("word ".repeat(100) + LINK, WORKER), TOK, "500 characters of text around a link are fine");
});

test("the shape is the Worker's own", () => {
  const src = readFileSync(new URL("./worker.js", import.meta.url), "utf8");
  const m = /const TOKEN_RE = (\/.*\/);/.exec(src);
  assert.ok(m, "the Worker's pattern was found");
  const w = new RegExp(m[1].slice(1, -1));
  for (const good of [TOK, CHAT + ".123456." + SIG, "A".repeat(10) + ".0." + SIG, "A".repeat(40) + ".0." + SIG]) assert.ok(TOKEN_RE.test(good) && w.test(good), good);
  for (const bad of [TOK + "x", "A".repeat(9) + ".0." + SIG, "A".repeat(41) + ".0." + SIG, CHAT + ".1234567." + SIG, CHAT + "..0." + SIG, CHAT + ".0." + SIG + "-", ""]) assert.equal(TOKEN_RE.test(bad), w.test(bad), "same answer for " + bad);
});

// ------------------------------------------------------------------------------------------------ the calls
function fake({ token = "idtok", answer = () => ({ status: 200, json: {} }), user = true } = {}) {
  const calls = [];
  const f = user === false ? { auth: { currentUser: null } } : { auth: { currentUser: { getIdToken: async () => token } } };
  const fetchFn = async (url, init) => {
    calls.push({ url, init, body: JSON.parse(init.body) });
    const a = await answer(url, calls[calls.length - 1].body);
    if (a.throws) throw new TypeError("Failed to fetch");
    return { ok: (a.status ?? 200) >= 200 && (a.status ?? 200) < 300, status: a.status ?? 200, json: async () => { if (a.badJson) throw new SyntaxError("x"); return a.json; } };
  };
  return { calls, inv: makeInvite({ f, worker: WORKER, fetchFn }) };
}

test("an admin's link: the phone's call, with my own token", async () => {
  const { calls, inv } = fake({ answer: () => ({ json: { link: LINK, v: 0 } }) });
  assert.equal(await inv.link(CHAT, false), LINK);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, WORKER + "/group/invite");
  assert.equal(calls[0].init.method, "POST");
  assert.equal(calls[0].init.headers.Authorization, "Bearer idtok");
  assert.equal(calls[0].init.headers["Content-Type"], "application/json");
  assert.deepEqual(calls[0].body, { chatId: CHAT, reset: false });
  assert.ok(calls[0].init.signal, "a call that cannot hang for ever");
  await inv.link(CHAT, true);
  assert.deepEqual(calls[1].body, { chatId: CHAT, reset: true });
  await inv.link(CHAT);
  assert.deepEqual(calls[2].body, { chatId: CHAT, reset: false }, "no 'reset' means no reset");
  await inv.link(CHAT, "yes");
  assert.deepEqual(calls[3].body, { chatId: CHAT, reset: false }, "only a real true resets");
});

test("looking at a link, and joining by it", async () => {
  const { calls, inv } = fake({ answer: (u, b) => ({ json: { chatId: CHAT, title: "Friends", count: b.preview ? 3 : 4, member: !b.preview } }) });
  assert.deepEqual(await inv.look(TOK), { chatId: CHAT, title: "Friends", count: 3, member: false });
  assert.deepEqual(calls[0].body, { token: TOK, preview: true });
  assert.equal(calls[0].url, WORKER + "/group/join");
  assert.deepEqual(await inv.join(TOK), { chatId: CHAT, title: "Friends", count: 4, member: true });
  assert.deepEqual(calls[1].body, { token: TOK }, "joining carries no 'preview'");
  assert.equal(inv.tokenOf("see " + LINK), TOK);
  assert.equal(inv.tokenOf("see https://evil.example/j/" + TOK), null);
});

test("an answer that is not what the Worker says is not trusted", async () => {
  const odd = [
    { json: null },
    { json: {} },
    { json: { chatId: "../../x", title: "t", count: 1 } },
    { json: { chatId: 5, title: "t" } },
    { json: { chatId: "short", title: "t" } },
    { badJson: true },
  ];
  for (const a of odd) {
    const { inv } = fake({ answer: () => a });
    await assert.rejects(inv.look(TOK), (e) => e instanceof Failure && e.code === 0, JSON.stringify(a));
    await assert.rejects(inv.join(TOK), (e) => e instanceof Failure && e.code === 0, JSON.stringify(a));
  }
  const clean = fake({ answer: () => ({ json: { chatId: CHAT, title: 7, count: -3, member: "yes" } }) });
  assert.deepEqual(await clean.inv.look(TOK), { chatId: CHAT, title: "", count: 0, member: false }, "a title that is no text, a count below zero, a 'member' that is not true");
  const frac = fake({ answer: () => ({ json: { chatId: CHAT, title: "t", count: 7.9, member: true } }) });
  assert.equal((await frac.inv.look(TOK)).count, 7);
});

test("a link that is not the Worker's own is never shown or copied", async () => {
  for (const link of ["https://evil.example/j/" + TOK, "javascript:alert(1)", WORKER + "/x/" + TOK, 5, null, undefined, WORKER + "/j/" + "a".repeat(400), ""]) {
    const { inv } = fake({ answer: () => ({ json: { link } }) });
    await assert.rejects(inv.link(CHAT, false), (e) => e instanceof Failure && e.code === 0, String(link).slice(0, 30));
  }
  const { inv } = fake({ answer: () => ({ json: {} }) });
  await assert.rejects(inv.link(CHAT, false), (e) => e.code === 0);
});

test("what the Worker refuses is passed on as its status; what cannot be reached is 0; no sign-in is 401", async () => {
  for (const status of [400, 401, 403, 404, 409, 410, 429, 501, 502, 500]) {
    const { inv } = fake({ answer: () => ({ status }) });
    await assert.rejects(inv.link(CHAT, false), (e) => e instanceof Failure && e.code === status, "link " + status);
    await assert.rejects(inv.look(TOK), (e) => e instanceof Failure && e.code === status, "look " + status);
    await assert.rejects(inv.join(TOK), (e) => e instanceof Failure && e.code === status, "join " + status);
  }
  const off = fake({ answer: () => ({ throws: true }) });
  await assert.rejects(off.inv.look(TOK), (e) => e.code === 0, "no connection");
  const none = fake({ user: false });
  await assert.rejects(none.inv.link(CHAT, false), (e) => e.code === 401, "not signed in");
  assert.equal(none.calls.length, 0, "nothing is sent without a sign-in");
  const bad = makeInvite({ f: { auth: { currentUser: { getIdToken: async () => { throw new Error("expired"); } } } }, worker: WORKER, fetchFn: async () => { throw new Error("must not be called"); } });
  await assert.rejects(bad.look(TOK), (e) => e.code === 401, "the token could not be had");
  let reached = 0;
  const noWorker = makeInvite({ f: { auth: { currentUser: { getIdToken: async () => "t" } } }, worker: "", fetchFn: async () => { reached++; throw new Error("must not be called"); } });
  await assert.rejects(noWorker.look(TOK), (e) => e.code === 0, "no Worker address");
  assert.equal(reached, 0, "with no Worker address nothing is sent (a relative address would go to the page's own site)");
});

test("a call that does not answer is given up (it does not hang)", async () => {
  let aborted = false;
  const f = { auth: { currentUser: { getIdToken: async () => "t" } } };
  const inv = makeInvite({
    f,
    worker: WORKER,
    fetchFn: (url, init) => new Promise((_, reject) => init.signal.addEventListener("abort", () => { aborted = true; reject(new DOMException("aborted", "AbortError")); })),
  });
  const real = globalThis.setTimeout;
  globalThis.setTimeout = (fn, ms, ...a) => real(fn, ms > 1000 ? 5 : ms, ...a); // the 20 seconds become 5 milliseconds
  const guard = new Promise((_, reject) => real(() => reject(new Error("the call hung")), 1500)); // a call that is never given up fails the test, it does not hang it
  try {
    await assert.rejects(Promise.race([inv.look(TOK), guard]), (e) => e instanceof Failure && e.code === 0);
  } finally {
    globalThis.setTimeout = real;
  }
  assert.ok(aborted, "the request was cancelled");
});

// ------------------------------------------------------------------------------------------------ what is told
test("every refusal has its own words, the same as the phone's", () => {
  const f = (code, forLink) => explainKey(new Failure(code), forLink);
  assert.equal(f(403, true), "invNotAdmin");
  assert.equal(f(403, false), "invBad");
  assert.equal(f(404, true), "invNoGroup");
  assert.equal(f(404, false), "invNoGroup");
  assert.equal(f(410, false), "invExpired");
  assert.equal(f(409, false), "invFull");
  assert.equal(f(429, true), "invSlow");
  assert.equal(f(501, true), "invOff");
  assert.equal(f(401, false), "invSignedOut");
  for (const code of [0, 500, 502, 400, 418]) assert.equal(f(code, true), "invNet", String(code));
  assert.equal(explainKey(new Error("x"), true), "invNet");
  assert.equal(explainKey(null, false), "invNet");
  assert.equal(explainKey(undefined, true), "invNet");
});

test("the phone says the same things in Armenian", () => {
  // the phone's source is beside this folder in the repository; in a copy without the app folder only the page's own words are checked
  let kt = null;
  try {
    kt = readFileSync(new URL("../app/src/main/java/am/khosq/app/push/InviteClient.kt", import.meta.url), "utf8");
  } catch (e) {
    kt = null;
  }
  const want = {
    invNotAdmin: "Հղում կարող է ստանալ միայն խմբի ադմինը",
    invBad: "Հրավերի հղումը անվավեր է",
    invNoGroup: "Խումբը չի գտնվել",
    invExpired: "Այս հղումը չեղարկված է։ Խնդրիր նոր հղում։",
    invFull: "Խումբը լցված է (առավելագույնը 50 անդամ)",
    invSlow: "Չափազանց շատ հարցումներ. փորձիր քիչ հետո",
    invOff: "Հրավերների ծառայությունը դեռ միացված չէ",
    invSignedOut: "Մուտք չի կատարված",
    invNet: "Կապ չկա կամ սերվերը չի պատասխանում",
  };
  for (const [k, v] of Object.entries(want)) {
    assert.equal(MORE.hy[k], v, k);
    if (kt !== null) assert.ok(kt.includes(v), "the phone says: " + v);
  }
});

// ------------------------------------------------------------------------------------------------ the words
test("every word of the new windows exists in Armenian, English and Russian, with the same {0} places", () => {
  const keys = new Set();
  for (const file of ["groupui.js", "invite.js"]) {
    const src = readFileSync(new URL("../docs/" + file, import.meta.url), "utf8");
    for (const m of src.matchAll(/"((?:nc|ng|gi|jg|inv)[A-Z][A-Za-z]*)"/g)) keys.add(m[1]);
  }
  const html = readFileSync(new URL("../docs/index.html", import.meta.url), "utf8");
  for (const m of html.matchAll(/data-i18n(?:-aria|-title)?="([A-Za-z]+)"/g)) keys.add(m[1]);
  for (const k of ["jgTitle", "jgLabel", "jgCheck", "jgChecking", "jgJoin", "jgJoining", "jgOpen", "jgBad", "jgMember", "jgInfo", "ncJoin", "giLink", "giLinkSub", "giLinkLb", "giLinkNote", "giLinkCopy", "giLinkCopied", "giLinkSelect", "giLinkNew", "giLinkBusy", "giLinkResetAsk", "giLinkResetYes", "invNotAdmin", "invBad", "invNoGroup", "invExpired", "invFull", "invSlow", "invOff", "invSignedOut", "invNet"]) assert.ok(keys.has(k), "the code uses " + k);
  assert.ok(keys.size >= 60, "found " + keys.size);
  for (const lang of ["hy", "en", "ru"]) {
    for (const k of keys) {
      if (k in MORE[lang]) {
        assert.ok(MORE[lang][k].trim().length > 0, lang + "." + k + " is empty");
        assert.equal((MORE[lang][k].match(/\{\d\}/g) || []).join(), (MORE.hy[k].match(/\{\d\}/g) || []).join(), lang + "." + k + " has other {0} places");
      } else assert.ok(!(k in MORE.hy) && !(k in MORE.en) && !(k in MORE.ru), lang + "." + k + " is missing in one language only");
    }
  }
  assert.ok(!("giNoLink" in MORE.hy) && !("giNoLink" in MORE.en) && !("giNoLink" in MORE.ru), "the old 'only on the phone' note is gone");
  assert.ok(!readFileSync(new URL("../docs/index.html", import.meta.url), "utf8").includes("giNoLink"));
});

test("the old words of the groups are still the phone's (the note about the link is replaced, nothing else lost)", () => {
  for (const lang of ["hy", "en", "ru"]) for (const k of ["giOpen", "giMembers", "giLeave", "giAdd", "giRename", "ncGroup", "ngTitle"]) assert.equal(typeof MORE[lang][k], "string", lang + "." + k);
});
