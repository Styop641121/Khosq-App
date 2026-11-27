// Groups on the web page (docs/groups.js, and the picture of a group in docs/media.js). What is written must be what the phone writes
// (Repository.createGroup / addMembers / removeMember / renameGroup / setGroupPhoto / removeGroupPhoto / setGroupDesc / setAdminOnly /
// setAdmin / leaveGroup) and what the Firestore rules of the project allow. Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { makeGroups, contactsOf, cleanTitle, cleanDesc, isRealGroup, iAmAdmin, adminsOf, TITLE_MAX, DESC_MAX, PHOTO_LIMIT } from "../docs/groups.js";
import { squareOf, avatarBytes, AVATAR_SIDES, AVATAR_GOAL } from "../docs/media.js";
import { MORE } from "../docs/words.js";

const ME = "Mm00000000000000000000000001";
const BOB = "Bb00000000000000000000000002";
const CAT = "Cc00000000000000000000000003";
const DAN = "Dd00000000000000000000000004";
const EVE = "Ee00000000000000000000000005";

/** A pretend Firestore that writes everything down, in the order it was asked. */
function fake() {
  const log = []; // every write: {kind, path, data}, batches as {kind: "batch", ops: [...]}
  const fail = { set: null, batch: false, del: false, upd: false };
  let seq = 0;
  const pathOf = (a) => a.filter((x) => typeof x === "string").join("/");
  const fsM = {
    collection: (db, ...path) => ({ kind: "col", path: pathOf(path) }),
    doc: (first, ...path) => (first && first.kind === "col" ? { kind: "doc", path: first.path + "/auto" + ++seq, id: "auto" + seq } : { kind: "doc", path: pathOf(path), id: path[path.length - 1] }),
    serverTimestamp: () => ({ sv: "ts" }),
    arrayUnion: (...x) => ({ sv: "union", x }),
    arrayRemove: (...x) => ({ sv: "remove", x }),
    Bytes: { fromUint8Array: (u) => ({ bytes: Array.from(u) }) },
    setDoc: async (ref, data) => {
      if (fail.set && fail.set(ref.path)) throw new Error("denied");
      log.push({ kind: "set", path: ref.path, data });
    },
    updateDoc: async (ref, data) => {
      if (fail.upd) throw new Error("denied");
      log.push({ kind: "update", path: ref.path, data });
    },
    deleteDoc: async (ref) => {
      if (fail.del) throw new Error("denied");
      log.push({ kind: "delete", path: ref.path });
    },
    writeBatch: () => {
      const ops = [];
      return {
        set(ref, data) { ops.push({ kind: "set", path: ref.path, data }); return this; },
        update(ref, data) { ops.push({ kind: "update", path: ref.path, data }); return this; },
        commit: async () => {
          if (fail.batch) throw new Error("denied");
          log.push({ kind: "batch", ops });
        },
      };
    },
  };
  return { f: { db: {}, fsM }, log, fail };
}

const me = { name: "Ստյոպա", handle: "styop", pv: 7 };
const bob = { uid: BOB, name: "Բոբ", handle: "bob", pv: 5 };
const cat = { uid: CAT, name: "Քեթի", handle: "cat", pv: 0 };
const dan = { uid: DAN, name: "Դան", handle: "dan", pv: 0 };

/** A group the way Firestore holds it. */
const group = (o = {}) => ({
  id: "grpA",
  group: true,
  title: "Երևանցիներ",
  members: [ME, BOB, CAT],
  admins: [ME],
  names: { [ME]: "Ստյոպա", [BOB]: "Բոբ", [CAT]: "Քեթի" },
  handles: { [ME]: "styop", [BOB]: "bob", [CAT]: "cat" },
  status: "active",
  ...o,
});
const asBob = (o = {}) => group({ admins: [BOB], ...o });
const make = (uid = ME, user = me, extra = {}) => {
  const w = fake();
  return { ...w, g: makeGroups({ f: w.f, uid, user, ...extra }) };
};
/** The one batch a change wrote: [the change, the line]. */
const oneBatch = (w) => {
  assert.equal(w.log.length, 1, "one write");
  assert.equal(w.log[0].kind, "batch", "a batch, so the change and its line go together");
  assert.equal(w.log[0].ops.length, 2);
  return w.log[0].ops;
};
const line = (op, from, code, arg = "") => {
  assert.equal(op.kind, "set");
  assert.match(op.path, /^chats\/grpA\/messages\/auto\d+$/);
  assert.deepEqual(op.data, { from, text: "", kind: "sys", code, arg, ts: { sv: "ts" } });
};

// ------------------------------------------------------------------------------------------------ small rules
test("cleanTitle is the phone's field: trimmed, at most 40 characters, never half an emoji", () => {
  assert.equal(TITLE_MAX, 40);
  assert.equal(cleanTitle("  Երևանցիներ  "), "Երևանցիներ");
  assert.equal(cleanTitle("   "), "");
  assert.equal(cleanTitle(null), "");
  assert.equal(cleanTitle("a\nb\r\nc"), "a b c", "a name is one line");
  assert.equal(cleanTitle("x".repeat(60)).length, 40);
  const cut = cleanTitle("x".repeat(39) + "😀😀");
  assert.equal(cut, "x".repeat(39), "an emoji that does not fit whole is left out");
  assert.equal(cleanTitle("x".repeat(38) + "😀").length, 40);
});

test("cleanDesc is the phone's: trimmed, at most 200 characters, line breaks stay", () => {
  assert.equal(DESC_MAX, 200);
  assert.equal(cleanDesc("  hello\r\nworld \n"), "hello\nworld");
  assert.equal(cleanDesc("x".repeat(300)).length, 200);
  assert.equal(cleanDesc(undefined), "");
  assert.equal(cleanDesc("   "), "");
});

test("isRealGroup, iAmAdmin, adminsOf: my saved messages are not a group, admins are read safely", () => {
  assert.equal(isRealGroup(group()), true);
  assert.equal(isRealGroup(group({ saved: true })), false);
  assert.equal(isRealGroup({ id: "x", members: [ME, BOB] }), false);
  assert.equal(isRealGroup(null), false);
  assert.equal(iAmAdmin(group(), ME), true);
  assert.equal(iAmAdmin(group(), BOB), false);
  assert.equal(iAmAdmin(group({ admins: undefined }), ME), false);
  assert.equal(iAmAdmin(group({ saved: true, admins: [ME] }), ME), false);
  assert.deepEqual(adminsOf({ admins: [ME, 5, null] }), [ME]);
  assert.deepEqual(adminsOf(null), []);
});

// ------------------------------------------------------------------------------------------------ the people I may add
test("contactsOf: accepted one-to-one chats where nobody is blocked, once each, by name", () => {
  const dm = (peer, o = {}) => ({
    id: [ME, peer].sort().join("_"),
    members: [ME, peer],
    names: { [ME]: "Ստյոպա", [peer]: "N-" + peer.slice(0, 2) },
    handles: { [ME]: "styop", [peer]: "h-" + peer.slice(0, 2).toLowerCase() },
    pv: { [peer]: 3 },
    status: "active",
    ...o,
  });
  const chats = [
    dm(DAN),
    dm(BOB, { names: { [BOB]: "Բոբ" } }),
    dm(CAT, { status: "pending", requester: CAT }), // a request not accepted
    dm(EVE, { blocked: { [ME]: true } }), // I blocked her
    dm("Ff00000000000000000000000006", { blocked: { Ff00000000000000000000000006: true } }), // she blocked me
    dm("Gg00000000000000000000000007", { status: undefined }), // an old chat without a status counts as accepted (the page does this everywhere)
    group({ members: [ME, "Hh00000000000000000000000008"] }), // a group
    { id: "savedMm", group: true, saved: true, members: [ME] },
    { id: "lonely", members: [ME] }, // no other person
    dm(BOB, { id: "dup" }), // the same person twice: once
  ];
  const got = contactsOf(chats, ME);
  const ids = got.map((c) => c.uid);
  assert.ok(ids.includes(BOB) && ids.includes(DAN) && ids.includes("Gg00000000000000000000000007"));
  for (const bad of [CAT, EVE, "Ff00000000000000000000000006", "Hh00000000000000000000000008"]) assert.ok(!ids.includes(bad), bad + " is not offered");
  assert.equal(ids.length, 3, "no group, no saved messages, nobody twice");
  const b = got.find((c) => c.uid === BOB);
  assert.deepEqual(b, { uid: BOB, name: "Բոբ", handle: "h-bb", pv: 3, chatId: chats[1].id }, "the first chat of a person is the one used");
  assert.equal(got.find((c) => c.uid === DAN).name, "N-Dd");
  assert.deepEqual(contactsOf(null, ME), []);
});

test("contactsOf falls back to the handle when the chat has no name, and sorts by name", () => {
  const chats = [
    { id: "a", members: [ME, CAT], names: {}, handles: { [CAT]: "zed" }, status: "active" },
    { id: "b", members: [ME, BOB], names: { [BOB]: "Anna" }, handles: { [BOB]: "bob" }, status: "active" },
  ];
  assert.deepEqual(contactsOf(chats, ME).map((c) => c.name), ["Anna", "zed"]);
});

// ------------------------------------------------------------------------------------------------ making a group
test("create writes the phone's document, then the line 'the group was made'", async () => {
  const w = make();
  const r = w.g.create("  Երևանցիներ ", [bob, cat]);
  assert.equal(r.ok, true);
  await r.committed;
  assert.equal(w.log.length, 2);
  const [g, l] = w.log;
  assert.equal(g.kind, "set");
  assert.equal(g.path, "chats/" + r.id);
  assert.ok(!r.id.includes("_"), "a group id has no underscore (the rules: grpOk)");
  const d = g.data;
  assert.deepEqual(Object.keys(d).sort(), ["admins", "group", "handles", "lastFrom", "lastText", "lastTs", "members", "names", "pv", "status", "title"].sort());
  assert.equal(d.group, true);
  assert.equal(d.title, "Երևանցիներ");
  assert.deepEqual(d.members, [ME, BOB, CAT], "me first, then the people in the order chosen");
  assert.deepEqual(d.admins, [ME], "the rules: admins must be exactly [me]");
  assert.equal(d.status, "active", "the rules: a group is made active");
  assert.deepEqual(d.pv, { [ME]: 7 });
  assert.deepEqual(d.names, { [ME]: "Ստյոպա", [BOB]: "Բոբ", [CAT]: "Քեթի" });
  assert.deepEqual(d.handles, { [ME]: "styop", [BOB]: "bob", [CAT]: "cat" });
  assert.equal(d.lastText, "");
  assert.equal(d.lastFrom, ME);
  assert.equal(typeof d.lastTs, "number", "the phone writes a number here, not a server time");
  assert.ok(Math.abs(d.lastTs - Date.now()) < 5000);
  assert.equal(l.kind, "set");
  assert.match(l.path, new RegExp("^chats/" + r.id + "/messages/auto\\d+$"));
  assert.deepEqual(l.data, { from: ME, text: "", kind: "sys", code: "grp_created", arg: "", ts: { sv: "ts" } });
});

test("create: a name written with only my handle, and the picture version of nobody else", async () => {
  const w = make(ME, { handle: "styop" });
  const r = w.g.create("G", [{ uid: BOB, name: "", handle: "bob" }]);
  await r.committed;
  const d = w.log[0].data;
  assert.equal(d.names[ME], "styop", "no name: the handle (as the phone's profile does)");
  assert.equal(d.names[BOB], "bob");
  assert.deepEqual(d.pv, { [ME]: 0 });
});

test("create drops me and doubles from the people, and refuses a missing name or nobody", async () => {
  const w = make();
  const r = w.g.create("G", [bob, { ...bob }, { uid: ME, name: "x", handle: "styop" }, null, { uid: "" }, cat]);
  await r.committed;
  assert.deepEqual(w.log[0].data.members, [ME, BOB, CAT]);
  const w2 = make();
  assert.deepEqual(w2.g.create("   ", [bob]), { ok: false, why: "title" });
  assert.deepEqual(w2.g.create("G", []), { ok: false, why: "picks" });
  assert.deepEqual(w2.g.create("G", [{ uid: ME }]), { ok: false, why: "picks" }, "a group of only me is not made");
  assert.deepEqual(w2.g.create("G", undefined), { ok: false, why: "picks" });
  assert.equal(w2.log.length, 0, "a refusal writes nothing");
});

test("create: the name is cut to 40 characters", async () => {
  const w = make();
  const r = w.g.create("ա".repeat(70), [bob]);
  await r.committed;
  assert.equal(w.log[0].data.title.length, 40);
});

test("create: when the group itself is refused there is no line and the error comes out; when only the line is refused it is not an error", async () => {
  const a = make();
  a.fail.set = (p) => /^chats\/[^/]+$/.test(p);
  const r = a.g.create("G", [bob]);
  await assert.rejects(r.committed);
  assert.equal(a.log.length, 0, "no line for a group that is not there");
  const b = make();
  b.fail.set = (p) => p.includes("/messages/");
  const r2 = b.g.create("G", [bob]);
  await r2.committed; // the group is made, it only lacks its first line
  assert.equal(b.log.length, 1);
});

test("two groups never get the same id", () => {
  const w = make();
  const a = w.g.create("G", [bob]);
  const b = w.g.create("G", [bob]);
  assert.notEqual(a.id, b.id);
});

// ------------------------------------------------------------------------------------------------ adding and removing
test("addMembers: one batch (members, names, handles) and the line with the names", async () => {
  const w = make();
  const r = w.g.addMembers(group(), [dan, { uid: EVE, name: "Եվա", handle: "eve" }]);
  assert.equal(r.ok, true);
  await r.committed;
  const [upd, l] = oneBatch(w);
  assert.equal(upd.kind, "update");
  assert.equal(upd.path, "chats/grpA");
  assert.deepEqual(upd.data, {
    members: { sv: "union", x: [DAN, EVE] },
    ["names." + DAN]: "Դան",
    ["handles." + DAN]: "dan",
    ["names." + EVE]: "Եվա",
    ["handles." + EVE]: "eve",
  });
  line(l, ME, "grp_added", "Դան, Եվա");
});

test("addMembers drops people who are in the group already, and refuses when nobody is left", async () => {
  const w = make();
  const r = w.g.addMembers(group(), [bob, dan, { ...dan }]);
  await r.committed;
  const [upd, l] = oneBatch(w);
  assert.deepEqual(upd.data.members, { sv: "union", x: [DAN] });
  assert.equal(l.data.arg, "Դան");
  const w2 = make();
  assert.deepEqual(w2.g.addMembers(group(), [bob, cat]), { ok: false, why: "picks" });
  assert.deepEqual(w2.g.addMembers(group(), []), { ok: false, why: "picks" });
  assert.equal(w2.log.length, 0);
});

test("removeMember: members and admins lose the person in one batch with the line that names them", async () => {
  const w = make();
  const r = w.g.removeMember(group({ admins: [ME, BOB] }), BOB, "Բոբ");
  await r.committed;
  const [upd, l] = oneBatch(w);
  assert.deepEqual(upd.data, { members: { sv: "remove", x: [BOB] }, admins: { sv: "remove", x: [BOB] } });
  line(l, ME, "grp_removed", "Բոբ");
});

test("removeMember refuses myself (that is leaving), a stranger and an empty id", () => {
  const w = make();
  assert.deepEqual(w.g.removeMember(group(), ME, "x"), { ok: false, why: "no" });
  assert.deepEqual(w.g.removeMember(group(), DAN, "x"), { ok: false, why: "no" });
  assert.deepEqual(w.g.removeMember(group(), "", "x"), { ok: false, why: "no" });
  assert.equal(w.log.length, 0);
});

// ------------------------------------------------------------------------------------------------ name, description, mode, admins
test("rename: the title and the line carry the new name", async () => {
  const w = make();
  const r = w.g.rename(group(), "  Նոր անուն ");
  await r.committed;
  const [upd, l] = oneBatch(w);
  assert.deepEqual(upd.data, { title: "Նոր անուն" });
  line(l, ME, "grp_renamed", "Նոր անուն");
});

test("rename refuses the same name, an empty one, and cuts a long one", async () => {
  const w = make();
  assert.deepEqual(w.g.rename(group(), "Երևանցիներ "), { ok: false, why: "same" });
  assert.deepEqual(w.g.rename(group(), "   "), { ok: false, why: "title" });
  assert.equal(w.log.length, 0);
  const r = w.g.rename(group(), "z".repeat(90));
  await r.committed;
  assert.equal(oneBatch(w)[0].data.title.length, 40);
});

test("setDesc: the description and a line without an argument; clearing is allowed; the same text is not a change", async () => {
  const w = make();
  await w.g.setDesc(group(), "  Մեր խումբը\nկանոններ ").committed;
  const [upd, l] = oneBatch(w);
  assert.deepEqual(upd.data, { desc: "Մեր խումբը\nկանոններ" });
  line(l, ME, "grp_desc", "");
  const w2 = make();
  await w2.g.setDesc(group({ desc: "old" }), "   ").committed;
  assert.deepEqual(oneBatch(w2)[0].data, { desc: "" });
  const w3 = make();
  assert.deepEqual(w3.g.setDesc(group({ desc: "same" }), " same "), { ok: false, why: "same" });
  assert.deepEqual(w3.g.setDesc(group(), ""), { ok: false, why: "same" }, "no description and nothing typed");
  assert.equal(w3.log.length, 0);
  const w4 = make();
  await w4.g.setDesc(group(), "q".repeat(500)).committed;
  assert.equal(oneBatch(w4)[0].data.desc.length, 200);
});

test("setAdminOnly writes sendMode and the matching line, both ways", async () => {
  const w = make();
  await w.g.setAdminOnly(group(), true).committed;
  let [upd, l] = oneBatch(w);
  assert.deepEqual(upd.data, { sendMode: "admins" });
  line(l, ME, "grp_mode_admins", "");
  const w2 = make();
  await w2.g.setAdminOnly(group({ sendMode: "admins" }), false).committed;
  [upd, l] = oneBatch(w2);
  assert.deepEqual(upd.data, { sendMode: "all" });
  line(l, ME, "grp_mode_all", "");
});

test("setAdminOnly refuses what is already so", () => {
  const w = make();
  assert.deepEqual(w.g.setAdminOnly(group(), false), { ok: false, why: "same" });
  assert.deepEqual(w.g.setAdminOnly(group({ sendMode: "all" }), false), { ok: false, why: "same" });
  assert.deepEqual(w.g.setAdminOnly(group({ sendMode: "admins" }), true), { ok: false, why: "same" });
  assert.equal(w.log.length, 0);
});

test("setAdmin: a person is made an admin or stops being one, with the line that names them", async () => {
  const w = make();
  await w.g.setAdmin(group(), BOB, "Բոբ", true).committed;
  let [upd, l] = oneBatch(w);
  assert.deepEqual(upd.data, { admins: { sv: "union", x: [BOB] } });
  line(l, ME, "grp_admin_on", "Բոբ");
  const w2 = make();
  await w2.g.setAdmin(group({ admins: [ME, BOB] }), BOB, "Բոբ", false).committed;
  [upd, l] = oneBatch(w2);
  assert.deepEqual(upd.data, { admins: { sv: "remove", x: [BOB] } });
  line(l, ME, "grp_admin_off", "Բոբ");
});

test("setAdmin refuses myself, a stranger and what is already so", () => {
  const w = make();
  assert.deepEqual(w.g.setAdmin(group(), ME, "x", false), { ok: false, why: "no" });
  assert.deepEqual(w.g.setAdmin(group(), DAN, "x", true), { ok: false, why: "no" });
  assert.deepEqual(w.g.setAdmin(group(), BOB, "x", false), { ok: false, why: "same" });
  assert.deepEqual(w.g.setAdmin(group({ admins: [ME, BOB] }), BOB, "x", true), { ok: false, why: "same" });
  assert.equal(w.log.length, 0);
});

test("only an admin of a real group changes anything: everybody else is refused and nothing is written", async () => {
  const w = make(BOB, { name: "Բոբ", handle: "bob" }); // Bob is a member, not an admin
  const g = group();
  const all = [
    w.g.addMembers(g, [dan]),
    w.g.removeMember(g, CAT, "x"),
    w.g.rename(g, "New"),
    w.g.setDesc(g, "x"),
    w.g.setAdminOnly(g, true),
    w.g.setAdmin(g, CAT, "x", true),
    w.g.setPhoto(g, new Uint8Array([1])),
    w.g.removePhoto({ ...g, gpv: 5 }),
  ];
  for (const r of all) assert.deepEqual(r, { ok: false, why: "admin" });
  // my saved messages and a one-to-one chat are not groups
  const wa = make();
  for (const chat of [group({ saved: true }), { id: "x_y", members: [ME, BOB], admins: [ME] }, null]) {
    assert.deepEqual(wa.g.rename(chat, "New"), { ok: false, why: "no" });
    assert.deepEqual(wa.g.addMembers(chat, [dan]), { ok: false, why: "no" });
    assert.deepEqual(wa.g.leave(chat), { ok: false, why: "no" });
  }
  assert.equal(w.log.length + wa.log.length, 0);
});

// ------------------------------------------------------------------------------------------------ the picture
test("setPhoto: the picture first, then gpv and the line in one batch; the cache gets it in between", async () => {
  const seen = [];
  const w = make(ME, me, { cachePhoto: (id, v, b) => seen.push({ id, v, n: b.length, after: w.log.length }) });
  const bytes = new Uint8Array([1, 2, 3, 4]);
  const r = w.g.setPhoto(group({ gpv: 0 }), bytes);
  assert.equal(r.ok, true);
  assert.ok(Math.abs(r.ver - Date.now()) < 5000);
  await r.committed;
  assert.equal(w.log.length, 2);
  assert.deepEqual(w.log[0], { kind: "set", path: "photos/grpA", data: { data: { bytes: [1, 2, 3, 4] }, ts: r.ver } });
  const [upd, l] = w.log[1].ops;
  assert.deepEqual(upd.data, { gpv: r.ver }, "the version of the picture and of the chat agree");
  assert.equal(upd.path, "chats/grpA");
  line(l, ME, "grp_photo", "");
  assert.deepEqual(seen, [{ id: "grpA", v: r.ver, n: 4, after: 1 }], "cached once the picture is saved and before the chat says there is a new one");
});

test("setPhoto: a version always newer than the one before (even when the clock is behind), refuses a big picture", async () => {
  const w = make();
  const future = Date.now() + 10 * 60 * 1000;
  const r = w.g.setPhoto(group({ gpv: future }), new Uint8Array([9]));
  assert.equal(r.ver, future + 1);
  await r.committed;
  assert.deepEqual(w.g.setPhoto(group(), new Uint8Array(PHOTO_LIMIT)), { ok: false, why: "big" });
  assert.equal(PHOTO_LIMIT, 90000);
  const ok = w.g.setPhoto(group(), new Uint8Array(PHOTO_LIMIT - 1));
  assert.equal(ok.ok, true);
  await ok.committed;
  assert.deepEqual(w.g.setPhoto(group(), new Uint8Array(0)), { ok: false, why: "empty" });
  assert.deepEqual(w.g.setPhoto(group(), [1, 2]), { ok: false, why: "empty" });
  assert.deepEqual(w.g.setPhoto(group(), null), { ok: false, why: "empty" });
});

test("setPhoto: when the picture is refused nobody is told about it (no gpv, no line, nothing cached)", async () => {
  const seen = [];
  const w = make(ME, me, { cachePhoto: () => seen.push(1) });
  w.fail.set = () => true;
  const r = w.g.setPhoto(group(), new Uint8Array([1]));
  await assert.rejects(r.committed);
  assert.equal(w.log.length, 0);
  assert.equal(seen.length, 0);
});

test("removePhoto deletes the picture, then sets gpv to 0, and writes no line (like the phone)", async () => {
  const w = make();
  await w.g.removePhoto(group({ gpv: 123 })).committed;
  assert.deepEqual(w.log, [{ kind: "delete", path: "photos/grpA" }, { kind: "update", path: "chats/grpA", data: { gpv: 0 } }]);
  assert.deepEqual(w.g.removePhoto(group({ gpv: 0 })), { ok: false, why: "same" });
  assert.deepEqual(w.g.removePhoto(group()), { ok: false, why: "same" });
});

test("removePhoto: when the delete is refused gpv stays", async () => {
  const w = make();
  w.fail.del = true;
  await assert.rejects(w.g.removePhoto(group({ gpv: 5 })).committed);
  assert.equal(w.log.length, 0);
});

// ------------------------------------------------------------------------------------------------ leaving
test("leave (a member who is not an admin): the line first, then only members changes — what the rules allow a member", async () => {
  const w = make(BOB, { name: "Բոբ", handle: "bob" });
  const r = w.g.leave(group());
  assert.equal(r.ok, true);
  await r.committed;
  const [l, upd] = oneBatch(w);
  line(l, BOB, "grp_left", "");
  assert.equal(upd.kind, "update");
  assert.deepEqual(upd.data, { members: { sv: "remove", x: [BOB] } }, "the rules: a member who leaves changes members and nothing else");
});

test("leave (an admin with other admins): the others stay admins", async () => {
  const w = make();
  await w.g.leave(group({ admins: [ME, CAT] })).committed;
  const [l, upd] = oneBatch(w);
  line(l, ME, "grp_left", "");
  assert.deepEqual(upd.data, { members: { sv: "remove", x: [ME] }, admins: [CAT] });
});

test("leave (the only admin): the first of the others becomes admin, so the group is never without one", async () => {
  const w = make();
  await w.g.leave(group()).committed;
  assert.deepEqual(oneBatch(w)[1].data, { members: { sv: "remove", x: [ME] }, admins: [BOB] });
  const w2 = make();
  await w2.g.leave(group({ members: [ME], admins: [ME] })).committed;
  assert.deepEqual(oneBatch(w2)[1].data, { members: { sv: "remove", x: [ME] }, admins: [] }, "the last person out");
});

test("leave (a member who is not an admin, in an announcement group): no line, because the rules would refuse it and take the leaving with it", async () => {
  const w = make(BOB, { name: "Բոբ", handle: "bob" });
  const r = w.g.leave(group({ sendMode: "admins" }));
  assert.equal(r.ok, true);
  await r.committed;
  assert.deepEqual(w.log, [{ kind: "update", path: "chats/grpA", data: { members: { sv: "remove", x: [BOB] } } }], "the change of members alone, nothing else");
});

test("leave (an admin of an announcement group) still writes the line: an admin may write there", async () => {
  const w = make();
  await w.g.leave(group({ sendMode: "admins", admins: [ME, CAT] })).committed;
  const [l, upd] = oneBatch(w);
  line(l, ME, "grp_left", "");
  assert.deepEqual(upd.data, { members: { sv: "remove", x: [ME] }, admins: [CAT] });
  // and a group that says "all" (or says nothing) lets everybody write the line
  const w2 = make(BOB, { name: "Բոբ", handle: "bob" });
  await w2.g.leave(group({ sendMode: "all" })).committed;
  assert.equal(oneBatch(w2).length, 2);
});

test("leave refuses when I am not in the group", () => {
  const w = make();
  assert.deepEqual(w.g.leave(group({ members: [BOB, CAT] })), { ok: false, why: "no" });
  assert.equal(w.log.length, 0);
});

test("a write the server refuses comes out of committed, not out of the call", async () => {
  const w = make();
  w.fail.batch = true;
  const r = w.g.rename(group(), "New");
  assert.equal(r.ok, true);
  await assert.rejects(r.committed);
});

// ------------------------------------------------------------------------------------------------ the picture file
test("squareOf: the middle square of a picture", () => {
  assert.deepEqual(squareOf(1000, 600), { sx: 200, sy: 0, side: 600 });
  assert.deepEqual(squareOf(600, 1000), { sx: 0, sy: 200, side: 600 });
  assert.deepEqual(squareOf(500, 500), { sx: 0, sy: 0, side: 500 });
  assert.deepEqual(squareOf(101, 100), { sx: 0, sy: 0, side: 100 });
});

test("the picture of a group is the phone's: 512, then 384, then 256 px, at most 70 KB (the rules refuse 90 KB)", () => {
  assert.deepEqual(AVATAR_SIDES, [512, 384, 256]);
  assert.equal(AVATAR_GOAL, 70000);
  assert.ok(AVATAR_GOAL < PHOTO_LIMIT);
});

test("avatarBytes refuses what is not a picture and what is far too big, before opening anything", async () => {
  await assert.rejects(avatarBytes({ type: "text/plain", size: 10 }), { message: "notImage" });
  await assert.rejects(avatarBytes(null), { message: "notImage" });
  await assert.rejects(avatarBytes({ type: "image/png", size: 41 * 1000 * 1000 }), { message: "big" });
});

// ------------------------------------------------------------------------------------------------ the words
test("every group word exists in Armenian, English and Russian, with the same {0} places", () => {
  const keys = Object.keys(MORE.hy).filter((k) => /^(nc|ng|gi)[A-Z]/.test(k));
  assert.ok(keys.length >= 40, "found " + keys.length);
  for (const lang of ["en", "ru"]) {
    for (const k of keys) {
      assert.equal(typeof MORE[lang][k], "string", lang + "." + k);
      assert.ok(MORE[lang][k].trim().length > 0, lang + "." + k + " is empty");
      assert.equal((MORE[lang][k].match(/\{\d\}/g) || []).join(), (MORE.hy[k].match(/\{\d\}/g) || []).join(), lang + "." + k + " has other {0} places");
    }
  }
  for (const code of ["grp_created", "grp_added", "grp_removed", "grp_renamed", "grp_left", "grp_photo", "grp_desc", "grp_mode_admins", "grp_mode_all", "grp_admin_on", "grp_admin_off"]) {
    for (const lang of ["hy", "en", "ru"]) assert.equal(typeof MORE[lang][code], "string", lang + "." + code + " (the line of the group)");
  }
});
