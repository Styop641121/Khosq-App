// People on the web page (docs/people.js): a new chat by ID, accepting a request, blocking, and the private lists of the chat list. What is
// written must be what the phone writes (Repository.findByHandle / startChat / acceptChat / setBlocked / setPref) and what the Firestore rules
// of the project allow. Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { makePeople, cleanHandle, chatIdOf, HANDLE_MAX, PREF_FIELDS } from "../docs/people.js";

const ME = "Mm00000000000000000000000001";
const BOB = "Bb00000000000000000000000002";
const ZED = "Aa00000000000000000000000009"; // sorts before ME

/** A pretend Firestore: documents in a map, every write written down. */
function fake(initial = {}) {
  const docs = new Map(Object.entries(initial));
  const log = [];
  const fail = { read: false, write: false };
  const ref = (path) => ({ path: path.join("/") });
  const snap = (path) => ({ exists: () => docs.has(path), data: () => docs.get(path) });
  const fsM = {
    doc: (db, ...path) => ref(path),
    getDoc: async (r) => {
      if (fail.read) throw new Error("offline");
      return snap(r.path);
    },
    setDoc: async (r, data, opts) => {
      if (fail.write) throw new Error("denied");
      log.push({ kind: "set", path: r.path, data, opts });
      docs.set(r.path, data);
    },
    updateDoc: async (r, data) => {
      if (fail.write) throw new Error("denied");
      log.push({ kind: "update", path: r.path, data });
    },
    deleteField: () => ({ sv: "del" }),
    arrayUnion: (x) => ({ sv: "union", x }),
    arrayRemove: (x) => ({ sv: "remove", x }),
  };
  return { f: { db: {}, fsM }, log, docs, fail };
}
const me = { name: "Ստյոպա", handle: "styop", pv: 3 };
const world = () =>
  fake({
    "handles/bob": { uid: BOB },
    "handles/styop": { uid: ME },
    "handles/ghost": { uid: "" },
    "handles/nouser": { uid: "Nn00000000000000000000000007" },
    "users/Bb00000000000000000000000002": { handle: "bob", name: "Բոբ", pv: 5 },
    "users/Mm00000000000000000000000001": { handle: "styop", name: "Ստյոպա" },
  });

test("cleanHandle is the phone's cleanHandle: a-z, 0-9, _ and -, lower case, at most 30", () => {
  assert.equal(cleanHandle("  @Bob_Smith-9 "), "bob_smith-9");
  assert.equal(cleanHandle("Բոբ"), "");
  assert.equal(cleanHandle(null), "");
  assert.equal(cleanHandle(undefined), "");
  assert.equal(cleanHandle("a.b c/d"), "abcd");
  assert.equal(HANDLE_MAX, 30);
  assert.equal(cleanHandle("x".repeat(45)).length, 30);
});

test("chatIdOf puts the smaller id first, whichever way round", () => {
  assert.equal(chatIdOf(ME, BOB), BOB + "_" + ME);
  assert.equal(chatIdOf(BOB, ME), BOB + "_" + ME);
  assert.equal(chatIdOf(ME, ZED), ZED + "_" + ME);
});

test("find: empty, own ID, unknown, half-made accounts and a server that cannot be asked are told apart", async () => {
  const w = world();
  const p = makePeople({ f: w.f, uid: ME, user: me });
  assert.deepEqual(await p.find("   "), { ok: false, why: "empty" });
  assert.deepEqual(await p.find("Բոբ"), { ok: false, why: "empty" });
  assert.deepEqual(await p.find("@STYOP"), { ok: false, why: "self" });
  assert.deepEqual(await p.find("nobody"), { ok: false, why: "notFound" });
  assert.deepEqual(await p.find("ghost"), { ok: false, why: "notFound" }); // an ID that points at nothing
  assert.deepEqual(await p.find("nouser"), { ok: false, why: "notFound" }); // an ID whose account is gone
  w.fail.read = true;
  assert.deepEqual(await p.find("bob"), { ok: false, why: "error" });
  assert.equal(w.log.length, 0, "looking for somebody writes nothing");
});

test("find: a person is found by the typed ID, however it is typed, with name, ID and picture version", async () => {
  const w = world();
  const p = makePeople({ f: w.f, uid: ME, user: me });
  for (const typed of ["bob", " Bob ", "@BOB"]) {
    assert.deepEqual(await p.find(typed), { ok: true, peer: { uid: BOB, handle: "bob", name: "Բոբ", pv: 5 } });
  }
  // no name or picture: the ID stands in for the name, the picture version is 0
  w.docs.set("users/" + BOB, { handle: "bob" });
  assert.deepEqual(await p.find("bob"), { ok: true, peer: { uid: BOB, handle: "bob", name: "bob", pv: 0 } });
});

test("start: a new chat is written exactly as the phone writes it, a pending request from me", async () => {
  const w = world();
  const p = makePeople({ f: w.f, uid: ME, user: me });
  const r = await p.start({ uid: BOB, handle: "bob", name: "Բոբ", pv: 5 });
  assert.deepEqual(r, { ok: true, id: BOB + "_" + ME, created: true });
  assert.equal(w.log.length, 1);
  const [wr] = w.log;
  assert.equal(wr.kind, "set");
  assert.equal(wr.path, "chats/" + BOB + "_" + ME);
  assert.equal(wr.opts, undefined, "a plain create, never a merge into something that may be there");
  assert.deepEqual(wr.data, {
    members: [ME, BOB],
    names: { [ME]: "Ստյոպա", [BOB]: "Բոբ" },
    handles: { [ME]: "styop", [BOB]: "bob" },
    status: "pending",
    requester: ME,
    pv: { [ME]: 3, [BOB]: 5 },
  });
  // what the Firestore rules want of a first write: me among the members, pending, me the requester, a one-to-one id,
  // and none of the keys that only a group (or an active chat) may carry
  const d = wr.data;
  assert.ok(d.members.includes(ME) && d.members.length === 2 && d.status === "pending" && d.requester === ME);
  for (const k of ["admins", "sendMode", "group", "blocked", "last", "unread"]) assert.ok(!(k in d), k);
});

test("start: a person without a picture adds no picture entry, and I with none write 0", async () => {
  const w = world();
  const p = makePeople({ f: w.f, uid: ZED, user: { handle: "zed" } });
  await p.start({ uid: ME, handle: "styop", name: "Ստյոպա", pv: 0 });
  const d = w.log[0].data;
  assert.deepEqual(d.pv, { [ZED]: 0 });
  assert.deepEqual(d.members, [ZED, ME]);
  assert.equal(w.log[0].path, "chats/" + ZED + "_" + ME);
  assert.deepEqual(d.names, { [ZED]: "zed", [ME]: "Ստյոպա" }); // no name yet: my ID stands in
});

test("start: a chat that already exists is only opened, whatever state it is in", async () => {
  const w = world();
  const id = chatIdOf(ME, BOB);
  w.docs.set("chats/" + id, { status: "active", blocked: { [ME]: true } });
  const p = makePeople({ f: w.f, uid: ME, user: me });
  assert.deepEqual(await p.start({ uid: BOB, handle: "bob", name: "Բոբ", pv: 5 }), { ok: true, id, created: false });
  assert.equal(w.log.length, 0, "nothing is written over an old chat");
});

test("start: a server that cannot be read or written to gives an error and leaves nothing behind", async () => {
  const w = world();
  const p = makePeople({ f: w.f, uid: ME, user: me });
  w.fail.read = true;
  assert.deepEqual(await p.start({ uid: BOB, handle: "bob", name: "Բոբ", pv: 0 }), { ok: false, why: "error" });
  w.fail.read = false;
  w.fail.write = true;
  assert.deepEqual(await p.start({ uid: BOB, handle: "bob", name: "Բոբ", pv: 0 }), { ok: false, why: "error" });
  assert.equal(w.log.length, 0);
  assert.equal(w.docs.has("chats/" + chatIdOf(ME, BOB)), false);
});

test("accept: only a request that somebody else made to me can be accepted, and it writes just the status", async () => {
  const w = world();
  const p = makePeople({ f: w.f, uid: ME, user: me });
  const mine = { id: "c1", status: "pending", requester: ME };
  const group = { id: "c2", status: "pending", requester: BOB, group: true };
  const active = { id: "c3", status: "active", requester: BOB };
  const old = { id: "c4", requester: BOB }; // an old chat with no status is an active one
  for (const bad of [mine, group, active, old, { id: "c5", status: "pending" }, null, undefined]) assert.deepEqual(p.accept(bad), { ok: false, why: "no" });
  assert.equal(w.log.length, 0);
  const r = p.accept({ id: "c6", status: "pending", requester: BOB });
  assert.equal(r.ok, true);
  await r.committed;
  assert.deepEqual(w.log, [{ kind: "update", path: "chats/c6", data: { status: "active" } }]);
});

test("setBlocked: my own block only, a field of mine in the blocked map, removed with a field delete", async () => {
  const w = world();
  const p = makePeople({ f: w.f, uid: ME, user: me });
  for (const bad of [{ id: "g", group: true }, { id: "s", saved: true }, null]) assert.deepEqual(p.setBlocked(bad, true), { ok: false, why: "no" });
  const on = p.setBlocked({ id: "c1" }, true);
  const off = p.setBlocked({ id: "c1" }, false);
  assert.equal(on.ok && off.ok, true);
  await on.committed;
  await off.committed;
  assert.deepEqual(w.log, [
    { kind: "update", path: "chats/c1", data: { ["blocked." + ME]: true } },
    { kind: "update", path: "chats/c1", data: { ["blocked." + ME]: { sv: "del" } } },
  ]);
});

test("setPref: a merge into my own prefs with one array change, for the four lists the phone has", async () => {
  const w = world();
  const p = makePeople({ f: w.f, uid: ME, user: me });
  assert.deepEqual(PREF_FIELDS, ["pins", "muted", "archived", "marked"]);
  for (const bad of [["name", "c1"], ["pins", ""], ["pins", null], ["__proto__", "c1"], ["members", "c1"]]) assert.deepEqual(p.setPref(bad[0], bad[1], true), { ok: false, why: "no" });
  assert.equal(w.log.length, 0);
  const a = p.setPref("pins", "c1", true);
  const b = p.setPref("marked", "c2", false);
  await a.committed;
  await b.committed;
  assert.deepEqual(w.log, [
    { kind: "set", path: "prefs/" + ME, data: { pins: { sv: "union", x: "c1" } }, opts: { merge: true } },
    { kind: "set", path: "prefs/" + ME, data: { marked: { sv: "remove", x: "c2" } }, opts: { merge: true } },
  ]);
});

test("setPref: a refused write comes back as a rejected promise, so the page can roll back", async () => {
  const w = world();
  const p = makePeople({ f: w.f, uid: ME, user: me });
  w.fail.write = true;
  const r = p.setPref("muted", "c1", true);
  assert.equal(r.ok, true);
  await assert.rejects(r.committed);
});
