// My own profile on the web page (docs/profile.js), the theme and the chat background (docs/look.js). What is written must be what the phone writes
// (Repository.saveMyInfo / savePhoto / removePhoto / setHideSeen / syncSelf, AppViewModel.saveProfile / setPhoto / syncPeople) and what the Firestore
// rules allow. Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { makeProfile, staleChats, cleanName, cleanAbout, shownName, NAME_MAX, ABOUT_MAX, PHOTO_LIMIT } from "../docs/profile.js";
import { THEMES, WALLS, WALL_COUNT, themeOf, wallOf, startLook, setTheme, setWall, getTheme, getWall, isDark, THEME_KEY, WALL_KEY } from "../docs/look.js";
import { MORE } from "../docs/words.js";

const ME = "Mm00000000000000000000000001";
const BOB = "Bb00000000000000000000000002";

/** A pretend Firestore that writes everything down in the order it was asked. [fail]: what to refuse. */
function fake() {
  const log = [];
  const fail = { set: null, del: false, upd: null, hold: null };
  let snap = null; // the watcher of users/<me>
  const pathOf = (a) => a.filter((x) => typeof x === "string").join("/");
  const fsM = {
    doc: (db, ...path) => ({ kind: "doc", path: pathOf(path) }),
    Bytes: { fromUint8Array: (u) => ({ bytes: Array.from(u) }) },
    setDoc: async (ref, data, opts) => {
      if (fail.hold) await fail.hold;
      if (fail.set && fail.set(ref.path, data)) throw new Error("denied");
      log.push({ kind: "set", path: ref.path, data, opts });
    },
    updateDoc: async (ref, data) => {
      if (fail.upd && fail.upd(ref.path, data)) throw new Error("denied");
      log.push({ kind: "update", path: ref.path, data });
    },
    deleteDoc: async (ref) => {
      if (fail.del) throw new Error("denied");
      log.push({ kind: "delete", path: ref.path });
    },
    onSnapshot: (ref, next, error) => {
      snap = { ref, next, error, stopped: false };
      return () => (snap.stopped = true);
    },
  };
  const send = (data) => snap.next({ exists: () => data !== null, data: () => data });
  return { f: { db: {}, fsM }, log, fail, send, snap: () => snap };
}

const settle = () => new Promise((r) => setTimeout(r, 0));
function make(userIn = { name: "Ստյոպա", handle: "styop", about: "Բարև", pv: 7, hideSeen: false }, extra = {}) {
  const w = fake();
  const user = { ...userIn };
  const changes = [];
  const cached = [];
  const p = makeProfile({
    f: w.f,
    uid: ME,
    user,
    cachePhoto: async (id, v, bytes) => {
      w.log.push({ kind: "cache", id, v });
      cached.push([id, v, bytes.length]);
    },
    onChange: () => changes.push({ ...user }),
    ...extra,
  });
  return { ...w, p, user, changes, cached };
}

// ---------------------------------------------------------------------------------------------- the rules of the fields
test("the name and about are cleaned the way the phone cleans them: trimmed, 30 and 140 characters, an emoji never cut in half", () => {
  assert.equal(NAME_MAX, 30);
  assert.equal(ABOUT_MAX, 140);
  assert.equal(PHOTO_LIMIT, 90000);
  assert.equal(cleanName("  Ստյոպա  "), "Ստյոպա");
  assert.equal(cleanName("a".repeat(40)), "a".repeat(30));
  assert.equal(cleanName("a".repeat(29) + "😀"), "a".repeat(29), "half an emoji is dropped");
  assert.equal(cleanName("a\nb\r\nc"), "a b c", "a name is one line");
  assert.equal(cleanName("   "), "");
  assert.equal(cleanName(null), "");
  assert.equal(cleanName(undefined), "");
  assert.equal(cleanAbout("  մի\r\nերկու\rերեք  "), "մի\nերկու\nերեք", "line breaks stay (as \\n)");
  assert.equal(cleanAbout("x".repeat(200)).length, 140);
  assert.equal(cleanAbout("x".repeat(139) + "😀"), "x".repeat(139));
  assert.equal(cleanAbout(null), "");
  assert.equal(shownName({ name: "A", handle: "h" }), "A");
  assert.equal(shownName({ name: "", handle: "h" }), "h", "without a name the ID is shown, like the phone");
  assert.equal(shownName({}), "");
  assert.equal(shownName(null), "");
});

// ---------------------------------------------------------------------------------------------- name and about
test("saving: users/<me> { name, about } merged, nothing else, and the page's copy changes only once the server has it", async () => {
  const w = make();
  const r = w.p.save("  Նոր անուն ", " Նոր նկարագրություն ");
  assert.equal(r.ok, true);
  assert.deepEqual([r.name, r.about], ["Նոր անուն", "Նոր նկարագրություն"]);
  assert.equal(w.user.name, "Ստյոպա", "not yet");
  await r.committed;
  assert.deepEqual(w.log, [{ kind: "set", path: "users/" + ME, data: { name: "Նոր անուն", about: "Նոր նկարագրություն" }, opts: { merge: true } }]);
  assert.equal(w.user.name, "Նոր անուն");
  assert.equal(w.user.about, "Նոր նկարագրություն");
  assert.equal(w.changes.length, 1);
});

test("saving an empty name keeps the old one (the phone's ifEmpty), about may be emptied, nothing changed writes nothing", async () => {
  const w = make();
  const r = w.p.save("   ", "");
  assert.equal(r.ok, true);
  assert.equal(r.name, "Ստյոպա");
  await r.committed;
  assert.deepEqual(w.log[0].data, { name: "Ստյոպա", about: "" });
  assert.equal(w.user.about, "");
  const same = w.p.save("Ստյոպա", "   ");
  assert.deepEqual(same, { ok: false, why: "same" });
  assert.equal(w.log.length, 1, "no second write");
  const none = make({ name: "", handle: "styop", about: "", pv: 0 });
  const g = none.p.save("", "x");
  assert.equal(g.name, "styop", "no name yet: the ID is what is kept");
});

test("a refused save is reported and the old name and about stay", async () => {
  const w = make();
  w.fail.set = () => true;
  const r = w.p.save("Նոր", "Նոր");
  await assert.rejects(r.committed, /denied/);
  assert.equal(w.user.name, "Ստյոպա");
  assert.equal(w.user.about, "Բարև");
  assert.equal(w.changes.length, 0);
});

// ---------------------------------------------------------------------------------------------- the picture
test("a new picture: photos/<me> { data, ts } first, remembered here, THEN users/<me> { pv } merged; the version is newer than the old one", async () => {
  const w = make();
  const bytes = new Uint8Array([1, 2, 3, 4]);
  const before = Date.now();
  const r = w.p.setPhoto(bytes);
  assert.equal(r.ok, true);
  assert.ok(r.ver >= before);
  await r.committed;
  assert.deepEqual(w.log, [
    { kind: "set", path: "photos/" + ME, data: { data: { bytes: [1, 2, 3, 4] }, ts: r.ver }, opts: undefined },
    { kind: "cache", id: ME, v: r.ver },
    { kind: "set", path: "users/" + ME, data: { pv: r.ver }, opts: { merge: true } },
  ]);
  assert.equal(w.user.pv, r.ver);
  assert.equal(w.changes.length, 1);
  assert.deepEqual(w.cached, [[ME, r.ver, 4]]);
  // a clock that is behind (or a version from the future on the phone) still gives a newer version
  const far = make({ name: "A", handle: "a", pv: Date.now() + 10_000_000 });
  const farPv = far.user.pv;
  const r2 = far.p.setPhoto(bytes);
  assert.equal(r2.ver, farPv + 1);
  await r2.committed;
  const ph = far.log.find((x) => x.path === "photos/" + ME);
  const us = far.log.find((x) => x.path === "users/" + ME);
  assert.equal(ph.data.ts, r2.ver, "the picture carries the version (not the time of the write)");
  assert.deepEqual(us.data, { pv: r2.ver }, "and pv is that same version");
  assert.equal(far.user.pv, r2.ver);
});

test("the picture is kept in this browser BEFORE pv is written (nobody is told about a picture that would be read back)", async () => {
  let release;
  const gate = new Promise((r) => (release = r));
  const w = make(undefined, { cachePhoto: async (id, v) => { w2.log.push({ kind: "cache-start" }); await gate; w2.log.push({ kind: "cache-done" }); } });
  const w2 = w;
  const r = w.p.setPhoto(new Uint8Array([9, 9]));
  await settle();
  await settle();
  assert.deepEqual(w.log.map((x) => x.kind + ":" + (x.path || "")), ["set:photos/" + ME, "cache-start:"], "pv waits for the browser's copy");
  assert.equal(w.user.pv, 7, "and nothing is shown yet");
  release();
  await r.committed;
  assert.deepEqual(w.log.map((x) => x.kind + ":" + (x.path || "")), ["set:photos/" + ME, "cache-start:", "cache-done:", "set:users/" + ME]);
  assert.equal(w.user.pv, r.ver);
});

test("a picture that is empty or 90 KB or more is not sent (the rules refuse it)", async () => {
  const w = make();
  assert.deepEqual(w.p.setPhoto(new Uint8Array(0)), { ok: false, why: "empty" });
  assert.deepEqual(w.p.setPhoto(null), { ok: false, why: "empty" });
  assert.deepEqual(w.p.setPhoto([1, 2]), { ok: false, why: "empty" });
  assert.deepEqual(w.p.setPhoto(new Uint8Array(PHOTO_LIMIT)), { ok: false, why: "big" });
  assert.equal(w.p.setPhoto(new Uint8Array(PHOTO_LIMIT - 1)).ok, true);
  await settle();
  assert.equal(w.log.filter((x) => x.kind === "set" && x.path.startsWith("photos/")).length, 1);
});

test("a picture the server refuses changes nothing: no pv, no new version shown", async () => {
  const a = make();
  a.fail.set = (path) => path.startsWith("photos/");
  const r = a.p.setPhoto(new Uint8Array([1]));
  await assert.rejects(r.committed, /denied/);
  assert.equal(a.user.pv, 7);
  assert.equal(a.log.length, 0, "no cache, no pv");
  const b = make();
  b.fail.set = (path) => path.startsWith("users/");
  const r2 = b.p.setPhoto(new Uint8Array([1]));
  await assert.rejects(r2.committed, /denied/);
  assert.equal(b.user.pv, 7, "the picture is there but nobody is pointed to it, and the page does not pretend");
  assert.equal(b.changes.length, 0);
});

test("removing the picture: photos/<me> deleted, THEN pv 0; with no picture there is nothing to do", async () => {
  const w = make();
  const r = w.p.removePhoto();
  assert.equal(r.ok, true);
  await r.committed;
  assert.deepEqual(w.log, [
    { kind: "delete", path: "photos/" + ME },
    { kind: "set", path: "users/" + ME, data: { pv: 0 }, opts: { merge: true } },
  ]);
  assert.equal(w.user.pv, 0);
  assert.deepEqual(w.p.removePhoto(), { ok: false, why: "same" });
  const bad = make();
  bad.fail.del = true;
  await assert.rejects(bad.p.removePhoto().committed, /denied/);
  assert.equal(bad.user.pv, 7);
  assert.equal(bad.log.length, 0, "no pv 0 for a picture that is still there");
});

// ---------------------------------------------------------------------------------------------- "Show online"
test("the switch is the opposite of hideSeen: on = hideSeen false", async () => {
  const w = make({ name: "A", handle: "a", pv: 0, hideSeen: false });
  assert.deepEqual(w.p.setShowOnline(true), { ok: false, why: "same" });
  const off = w.p.setShowOnline(false);
  assert.equal(off.ok, true);
  await off.committed;
  assert.deepEqual(w.log, [{ kind: "set", path: "users/" + ME, data: { hideSeen: true }, opts: { merge: true } }]);
  assert.equal(w.user.hideSeen, true);
  const on = w.p.setShowOnline(true);
  await on.committed;
  assert.deepEqual(w.log[1].data, { hideSeen: false });
  assert.equal(w.user.hideSeen, false);
  // anything that is not exactly true counts as off (it never shows "online" by accident)
  const x = make({ name: "A", handle: "a", pv: 0, hideSeen: false });
  const r = x.p.setShowOnline("yes");
  await r.committed;
  assert.equal(x.user.hideSeen, true);
});

test("a refused switch leaves it where it was", async () => {
  const w = make({ name: "A", handle: "a", pv: 0, hideSeen: false });
  w.fail.set = () => true;
  const r = w.p.setShowOnline(false);
  await assert.rejects(r.committed, /denied/);
  assert.equal(w.user.hideSeen, false);
});

// ---------------------------------------------------------------------------------------------- the chats
test("staleChats: the chats that still say another name or another picture; a chat that says nothing counts as picture 0", () => {
  const chats = [
    { id: "ok", names: { [ME]: "Ստյոպա" }, pv: { [ME]: 7 } },
    { id: "oldname", names: { [ME]: "Հին" }, pv: { [ME]: 7 } },
    { id: "oldpv", names: { [ME]: "Ստյոպա" }, pv: { [ME]: 3 } },
    { id: "nopv", names: { [ME]: "Ստյոպա" } },
    { id: "nonames", pv: { [ME]: 7 } },
    { id: "other", names: { [BOB]: "Բոբ" }, pv: { [BOB]: 1 } },
    { id: "", names: {} },
    null,
    { names: {} },
  ];
  const got = staleChats(chats, ME, "Ստյոպա", 7);
  assert.deepEqual(got.map((x) => x.id), ["oldname", "oldpv", "nopv", "nonames", "other"]);
  assert.deepEqual(got[0].fields, { ["names." + ME]: "Ստյոպա", ["pv." + ME]: 7 });
  // no picture: a chat with no pv entry is already right, one that still points to a picture is not
  assert.deepEqual(staleChats([{ id: "a", names: { [ME]: "N" } }, { id: "b", names: { [ME]: "N" }, pv: { [ME]: 4 } }], ME, "N", 0).map((x) => x.id), ["b"]);
  assert.deepEqual(staleChats(chats, ME, "", 7), [], "never an empty name");
  assert.deepEqual(staleChats(null, ME, "N", 0), []);
});

test("syncChats writes the phone's two fields into each stale chat, once, and only after my profile has been read live", async () => {
  const w = make();
  const chats = [
    { id: "dm1", names: { [ME]: "Հին" }, pv: { [ME]: 7 } },
    { id: "grp1", group: true, names: { [ME]: "Ստյոպա" }, pv: { [ME]: 0 } },
    { id: "dm2", names: { [ME]: "Ստյոպա" }, pv: { [ME]: 7 } },
  ];
  assert.equal(w.p.syncChats(chats), 0, "before the profile is known to be current, nothing is written into anybody's chat list");
  await settle();
  assert.equal(w.log.length, 0);
  w.p.watch();
  w.send({ name: "Ստյոպա", handle: "styop", about: "Բարև", pv: 7, hideSeen: false });
  assert.equal(w.p.syncChats(chats), 2);
  await settle();
  assert.deepEqual(w.log, [
    { kind: "update", path: "chats/dm1", data: { ["names." + ME]: "Ստյոպա", ["pv." + ME]: 7 } },
    { kind: "update", path: "chats/grp1", data: { ["names." + ME]: "Ստյոպա", ["pv." + ME]: 7 } },
  ]);
  assert.equal(w.p.syncChats(chats), 0, "each change is sent once, even while the list still shows the old words");
  // a new name is a new change
  w.send({ name: "Նոր", about: "", pv: 7 });
  assert.equal(w.p.syncChats(chats), 3);
  await settle();
  assert.equal(w.log.length, 5);
});

test("a change the server has not taken yet is not told to the chats; once taken it is; a refused one never is", async () => {
  const chats = [{ id: "dm1", names: { [ME]: "Ստյոպա" }, pv: { [ME]: 7 } }];
  // taken
  const a = make();
  a.p.watch();
  a.send({ name: "Ստյոպա", pv: 7 });
  let open;
  a.fail.hold = new Promise((r) => (open = r));
  const r = a.p.save("Նոր", "");
  a.send({ name: "Նոր", about: "", pv: 7 }); // the library already shows the new name (not yet taken by the server)
  assert.equal(a.p.syncChats(chats), 0, "not while the server has not taken it");
  open();
  await r.committed;
  assert.equal(a.p.syncChats(chats), 1, "taken: now the chats are told");
  await settle();
  assert.deepEqual(a.log.map((x) => x.path), ["users/" + ME, "chats/dm1"]);
  // refused
  const b = make();
  b.p.watch();
  b.send({ name: "Ստյոպա", pv: 7 });
  b.fail.hold = new Promise((r2) => (open = r2));
  b.fail.set = () => true;
  const r2 = b.p.save("Նոր", "");
  b.send({ name: "Նոր", pv: 7 });
  assert.equal(b.p.syncChats(chats), 0);
  open();
  await assert.rejects(r2.committed, /denied/);
  b.send({ name: "Ստյոպա", pv: 7 }); // the library takes the refused change back
  assert.equal(b.p.syncChats(chats), 0, "back to what the chats say: nothing to tell");
  assert.deepEqual(b.log, [], "and nothing was written into any chat");
  // the picture, the removal and the switch hold the chats back the same way
  for (const [name, start] of [["a picture", (w) => w.p.setPhoto(new Uint8Array([1]))], ["removing the picture", (w) => w.p.removePhoto()], ["the switch", (w) => w.p.setShowOnline(false)]]) {
    const w = make({ name: "Ստյոպա", handle: "h", pv: 7, hideSeen: false });
    w.p.watch();
    w.send({ name: "Ստյոպա", pv: 7 });
    w.fail.hold = new Promise((r3) => (open = r3));
    const x = start(w);
    assert.equal(w.p.syncChats([{ id: "c", names: {}, pv: {} }]), 0, name + ": not before the server has it");
    open();
    await x.committed;
    assert.equal(w.p.syncChats([{ id: "c", names: {}, pv: {} }]), 1, name + ": after");
  }
});

test("a chat that refuses the write is left alone and does not stop the others or the page", async () => {
  const w = make();
  w.p.watch();
  w.send({ name: "Ստյոպա", pv: 7 });
  w.fail.upd = (path) => path === "chats/a";
  const chats = [{ id: "a", names: {}, pv: {} }, { id: "b", names: {}, pv: {} }];
  assert.equal(w.p.syncChats(chats), 2);
  await settle();
  assert.deepEqual(w.log.map((x) => x.path), ["chats/b"]);
  assert.equal(w.p.syncChats(chats), 0, "and a is not asked again and again");
});

test("with no name and no ID nothing is written into chats", async () => {
  const w = make({ name: "", handle: "", pv: 0 });
  w.p.watch();
  w.send({});
  assert.equal(w.p.syncChats([{ id: "a", names: { [ME]: "x" }, pv: {} }]), 0);
});

// ---------------------------------------------------------------------------------------------- following the profile
test("watch: a change made on the phone is taken over in place, and a missing field means off or empty", () => {
  const w = make({ name: "Հին", handle: "", about: "x", pv: 1, hideSeen: false });
  const stop = w.p.watch();
  assert.equal(w.snap().ref.path, "users/" + ME);
  w.send({ name: "Նոր", handle: "styop", about: "Նկար", pv: 9, hideSeen: true });
  assert.deepEqual(w.user, { name: "Նոր", handle: "styop", about: "Նկար", pv: 9, hideSeen: true });
  assert.equal(w.changes.length, 1);
  w.send({ name: "Նոր", handle: "styop" });
  assert.deepEqual(w.user, { name: "Նոր", handle: "styop", about: "", pv: 0, hideSeen: false });
  w.send({ name: 5, handle: null, about: 7, pv: "12", hideSeen: "true" });
  assert.deepEqual(w.user, { name: "Նոր", handle: "styop", about: "", pv: 12, hideSeen: false }, "the wrong types are not taken");
  w.send(null);
  assert.equal(w.user.name, "Նոր", "a missing document changes nothing");
  assert.equal(w.changes.length, 4);
  w.snap().error(new Error("offline"));
  assert.equal(w.user.name, "Նոր");
  stop();
  assert.equal(w.snap().stopped, true);
});

test("the first answer of the watch is announced once", () => {
  const w = make();
  let n = 0;
  w.p.watch(() => n++);
  w.send({ name: "A" });
  w.send({ name: "B" });
  assert.equal(n, 1);
});

// ---------------------------------------------------------------------------------------------- the page
test("the sender and the calls get my name as a function, so a new name is used at once (the pushes and the calls carry it)", () => {
  const shell = readFileSync(new URL("../docs/shell.js", import.meta.url), "utf8");
  assert.equal((shell.match(/name: \(\) => shownName\(user\),/g) || []).length, 3, "makeSender, makeCalls and makeGroupCalls");
  assert.doesNotMatch(shell, /name: user\.name \|\| user\.handle/, "no copy of the name taken once at the start");
});

// ---------------------------------------------------------------------------------------------- the phone
const PHONE = new URL("../app/src/main/java/am/khosq/app/", import.meta.url);
const phone = (rel) => (existsSync(new URL(rel, PHONE)) ? readFileSync(new URL(rel, PHONE), "utf8") : null);

test("the phone writes the same fields (compared with the phone's own code, when it is here)", { skip: phone("data/Repository.kt") === null }, () => {
  const repo = phone("data/Repository.kt");
  const vm = phone("ui/AppViewModel.kt");
  const ui = phone("ui/Settings.kt");
  assert.match(repo, /collection\("photos"\)\.document\(uid\)\.set\(hashMapOf<String, Any>\("data" to Blob\.fromBytes\(bytes\), "ts" to ver\)\)/);
  assert.match(repo, /collection\("users"\)\.document\(uid\)\.set\(hashMapOf<String, Any>\("pv" to ver\), SetOptions\.merge\(\)\)/);
  assert.match(repo, /collection\("photos"\)\.document\(uid\)\.delete\(\)/);
  assert.match(repo, /"pv" to 0L/);
  assert.match(repo, /hashMapOf<String, Any>\("name" to name, "about" to about\), SetOptions\.merge\(\)/);
  assert.match(repo, /hashMapOf<String, Any>\("hideSeen" to on\), SetOptions\.merge\(\)/);
  assert.match(repo, /"names\.\$uid" to name, "pv\.\$uid" to pv/);
  assert.match(vm, /name\.trim\(\)\.take\(30\)\.ifEmpty \{ me\.name \}/);
  assert.match(vm, /about\.trim\(\)\.take\(140\)/);
  assert.match(vm, /c\.memberNames\[me\.uid\] != me\.name \|\| \(c\.pv\[me\.uid\] \?: 0L\) != me\.pv/);
  assert.match(ui, /onValueChange = \{ v -> name = v\.take\(30\) \}/);
  assert.match(ui, /onValueChange = \{ v -> about = v\.take\(140\) \}/);
  assert.match(ui, /checked = !me\.hideSeen, onCheckedChange = \{ on -> vm\.setHideSeen\(!on\) \}/);
  assert.match(ui, /visible = changed && name\.isNotBlank\(\)/);
});

// ---------------------------------------------------------------------------------------------- theme and background
function page(stored = {}, { dark = false, brokenStorage = false } = {}) {
  const attrs = {};
  const style = {};
  const root = {
    dataset: new Proxy(attrs, { deleteProperty: (o, k) => (delete o[k], true) }),
    style: { setProperty: (k, v) => (style[k] = v), removeProperty: (k) => delete style[k] },
  };
  const store = { ...stored };
  globalThis.localStorage = brokenStorage
    ? { getItem: () => { throw new Error("blocked"); }, setItem: () => { throw new Error("blocked"); } }
    : { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => (store[k] = String(v)) };
  const listeners = [];
  const mq = { matches: dark, addEventListener: (type, fn) => listeners.push(fn) };
  const win = { matchMedia: (q) => (/prefers-color-scheme: dark/.test(q) ? mq : { matches: false }) };
  return { root, attrs, style, store, mq, listeners, win };
}

test("themeOf and wallOf only accept what exists", () => {
  assert.deepEqual(THEMES, ["system", "light", "dark"]);
  assert.equal(WALL_COUNT, 8);
  assert.equal(WALLS[0], null);
  for (const v of ["system", "light", "dark"]) assert.equal(themeOf(v), v);
  for (const v of ["", null, undefined, "DARK", "blue", 3, {}]) assert.equal(themeOf(v), "system");
  for (let i = 0; i < 8; i++) assert.equal(wallOf(String(i)), i);
  assert.equal(wallOf(5), 5);
  for (const v of ["8", "-1", "1.5", "x", "", null, undefined, "07x", 8, -1, 2.5, "99", NaN]) assert.equal(wallOf(v), 0, String(v));
  // text that a number can be made of, but that this page never writes, is not a number here
  for (const v of [" 3 ", "3 ", " 3", "0x3", "1e0", "3.0", "+3", "٣", "\n3", "003", "3\n"]) assert.equal(wallOf(v), 0, JSON.stringify(v));
  assert.equal(wallOf("07"), 7, "two digits are fine");
  assert.equal(wallOf("00"), 0);
});

test("the colours of the chat backgrounds are the phone's (Wallpaper.kt), light and dark", { skip: phone("ui/Wallpaper.kt") === null }, () => {
  const kt = phone("ui/Wallpaper.kt");
  const rows = [...kt.matchAll(/Wall\(listOf\(Color\(0xFF(\w{6})\), Color\(0xFF(\w{6})\)\), listOf\(Color\(0xFF(\w{6})\), Color\(0xFF(\w{6})\)\), (\d)\)/g)];
  assert.equal(rows.length, 7);
  assert.match(kt, /private val WALLS: List<Wall\?> = listOf\(\s*null,/);
  const pat = ["none", "dots", "lines"];
  rows.forEach((m, i) => {
    const w = WALLS[i + 1];
    assert.deepEqual(w.light, ["#" + m[1].toUpperCase(), "#" + m[2].toUpperCase()], "light " + (i + 1));
    assert.deepEqual(w.dark, ["#" + m[3].toUpperCase(), "#" + m[4].toUpperCase()], "dark " + (i + 1));
    assert.equal(w.pattern, pat[Number(m[5])], "pattern " + (i + 1));
  });
});

test("startLook reads what this browser kept and paints it on the page", () => {
  const p = page({ [THEME_KEY]: "dark", [WALL_KEY]: "3" });
  assert.deepEqual(startLook(p.root, p.win), { theme: "dark", wall: 3 });
  assert.equal(p.attrs.theme, "dark");
  assert.equal(p.attrs.wall, "3");
  assert.equal(p.attrs.wallp, "none");
  assert.deepEqual([p.style["--wall-a"], p.style["--wall-b"]], WALLS[3].dark, "a dark page takes the dark colours");
  assert.match(p.style["--wall-ink"], /255,255,255/);
  assert.equal(isDark(), true);
});

test("system theme: the page follows the browser, also when the browser changes", () => {
  const p = page({ [WALL_KEY]: "6" }, { dark: false });
  startLook(p.root, p.win);
  assert.equal(getTheme(), "system");
  assert.equal("theme" in p.attrs, false, "no theme attribute: the CSS follows prefers-color-scheme by itself");
  assert.equal(isDark(), false);
  assert.deepEqual([p.style["--wall-a"], p.style["--wall-b"]], WALLS[6].light);
  assert.equal(p.attrs.wallp, "dots");
  assert.match(p.style["--wall-ink"], /0,0,0/);
  p.mq.matches = true;
  p.listeners.forEach((fn) => fn());
  assert.equal(isDark(), true);
  assert.deepEqual([p.style["--wall-a"], p.style["--wall-b"]], WALLS[6].dark, "and the background changes with it");
  p.mq.matches = false;
});

test("an explicit theme wins over the browser", () => {
  const p = page({}, { dark: true });
  startLook(p.root, p.win);
  assert.equal(isDark(), true);
  assert.equal(setTheme("light"), "light");
  assert.equal(p.attrs.theme, "light");
  assert.equal(isDark(), false);
  assert.equal(p.store[THEME_KEY], "light");
  assert.equal(setTheme("dark"), "dark");
  assert.equal(p.attrs.theme, "dark");
  assert.equal(setTheme("system"), "system");
  assert.equal("theme" in p.attrs, false);
  assert.equal(p.store[THEME_KEY], "system");
  assert.equal(setTheme("nonsense"), "system");
});

test("the background: choosing writes, 0 takes it away, a wrong number is none", () => {
  const p = page();
  startLook(p.root, p.win);
  assert.equal("wall" in p.attrs, false);
  assert.equal(setWall(7), 7);
  assert.deepEqual([p.attrs.wall, p.attrs.wallp, p.store[WALL_KEY]], ["7", "lines", "7"]);
  assert.equal(getWall(), 7);
  assert.equal(setWall(0), 0);
  assert.equal("wall" in p.attrs, false);
  assert.equal("wallp" in p.attrs, false);
  assert.equal("--wall-a" in p.style, false);
  assert.equal(p.store[WALL_KEY], "0");
  assert.equal(setWall(99), 0);
  assert.equal(setWall("3"), 3);
});

test("storage that refuses is not an error: the choice lasts for this visit", () => {
  const p = page({}, { brokenStorage: true });
  assert.deepEqual(startLook(p.root, p.win), { theme: "system", wall: 0 });
  assert.equal(setTheme("dark"), "dark");
  assert.equal(p.attrs.theme, "dark");
  assert.equal(setWall(2), 2);
  assert.equal(p.attrs.wall, "2");
  const q = page({}, { dark: true });
  q.win.matchMedia = () => { throw new Error("no"); };
  assert.deepEqual(startLook(q.root, q.win), { theme: "system", wall: 0 });
  assert.equal(isDark(), false);
});

// ---------------------------------------------------------------------------------------------- the words
test("every word of the window exists in all three languages, with the same {0} places", () => {
  const html = readFileSync(new URL("../docs/index.html", import.meta.url), "utf8");
  const sp = Object.keys(MORE.hy).filter((k) => /^sp[A-Z]/.test(k));
  assert.ok(sp.length >= 20, "found " + sp.length);
  for (const k of sp) {
    for (const l of ["hy", "en", "ru"]) {
      assert.equal(typeof MORE[l][k], "string", l + " " + k);
      assert.ok(MORE[l][k].length > 0, l + " " + k);
    }
    const slots = (l) => (MORE[l][k].match(/\{\d\}/g) || []).join();
    assert.equal(slots("en"), slots("hy"), k);
    assert.equal(slots("ru"), slots("hy"), k);
  }
  assert.deepEqual(Object.keys(MORE.hy).sort(), Object.keys(MORE.en).sort());
  assert.deepEqual(Object.keys(MORE.hy).sort(), Object.keys(MORE.ru).sort());
  // each word the page asks for by its id is there
  const asked = new Set([...html.matchAll(/data-i18n(?:-ph|-aria|-title)?="(\w+)"/g)].map((m) => m[1]));
  for (const k of asked) assert.ok(k in MORE.hy || ["tagline", "loginTitle"].includes(k) || /^(step|home|ok|bad|key|logout|cancel|waiting|linking|refresh|changes|privacy|retry|err)/.test(k), "the page asks for " + k);
  for (const k of ["spProfile", "spName", "spAbout", "spAboutPh", "spWall", "spTheme", "spThemeSystem", "spThemeLight", "spThemeDark", "spPrivacy", "spOnline", "spOnlineNote", "spOnlineWeb", "spLocal", "spCopyId"]) {
    assert.ok(asked.has(k), "index.html uses " + k);
  }
  assert.doesNotMatch(MORE.hy.homeNext, /կհայտնվի հաջորդ տարբերակներում/, "the note under the window no longer promises what is already there");
  assert.doesNotMatch(MORE.en.homeNext, /next versions/);
});

// ---------------------------------------------------------------------------------------------- the window's markup (what the browser itself enforces)
test("the window's fields carry the phone's limits and the picture box only takes pictures; the groups are real radio groups", () => {
  const html = readFileSync(new URL("../docs/index.html", import.meta.url), "utf8");
  const tag = (id) => {
    const m = html.match(new RegExp("<(\\w+)\\b[^>]*\\bid=\"" + id + "\"[^>]*>"));
    assert.ok(m, "index.html has #" + id);
    return m[0];
  };
  assert.match(tag("spName"), /\bmaxlength="30"/);
  assert.match(tag("spAbout"), /\bmaxlength="140"/);
  assert.match(tag("spFile"), /\baccept="image\/\*"/);
  assert.match(tag("spFile"), /\bhidden\b/);
  assert.match(tag("spWalls"), /\brole="radiogroup"/);
  assert.match(tag("spThemes"), /\brole="radiogroup"/);
  assert.match(tag("spOnline"), /\brole="switch"/);
  assert.match(tag("spPhotoX"), /\bhidden\b/, "no picture to take away until there is one");
  assert.match(tag("idCopied"), /\bhidden\b/);
  assert.match(tag("spErr"), /\bhidden\b/);
  assert.match(tag("about"), /\baria-labelledby="name"/);
  // the three themes, each one a button of the group
  assert.equal([...html.matchAll(/<button[^>]*data-theme="(system|light|dark)"[^>]*role="radio"|<button[^>]*role="radio"[^>]*data-theme="(system|light|dark)"/g)].length, 3);
});
