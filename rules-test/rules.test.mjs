// Firestore rules tests. Run by the "Rules tests" GitHub workflow inside the Firestore emulator:
//   npx firebase-tools emulators:exec --only firestore --project demo-khosq "cd rules-test && npm test"
import test, { before, after, beforeEach } from "node:test";
import { readFileSync } from "node:fs";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { doc, getDoc, getDocs, setDoc, updateDoc, collection, deleteField } from "firebase/firestore";

let env;
const db = (uid) => env.authenticatedContext(uid).firestore();
const seed = (fn) => env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore()));

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-khosq",
    firestore: { rules: readFileSync(new URL("../firestore.rules", import.meta.url), "utf8") },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await seed(async (d) => {
    await setDoc(doc(d, "chats/A_B"), { members: ["A", "B"], status: "active", requester: "A", names: { A: "a", B: "b" } });
    await setDoc(doc(d, "chats/A_B/messages/m1"), { from: "A", text: "hi" });
    await setDoc(doc(d, "users/A"), { name: "a" });
    await setDoc(doc(d, "users/B"), { name: "b" });
    await setDoc(doc(d, "handles/bob"), { uid: "B" });
  });
});

const dm = (extra = {}) => ({ members: ["A", "B"], status: "pending", requester: "A", ...extra });
const grp = (extra = {}) => ({ group: true, status: "active", members: ["A", "B"], admins: ["A"], title: "g", ...extra });

// ---------------------------------------------------------------- blocked
test("blocked: a member may set and clear only their own key", async () => {
  await assertSucceeds(updateDoc(doc(db("A"), "chats/A_B"), { "blocked.A": true }));
  await assertFails(updateDoc(doc(db("A"), "chats/A_B"), { "blocked.B": true }));
});
test("blocked: someone else cannot lift my block", async () => {
  await seed((d) => updateDoc(doc(d, "chats/A_B"), { "blocked.A": true }));
  await assertFails(updateDoc(doc(db("B"), "chats/A_B"), { "blocked.A": deleteField() }));
  await assertSucceeds(updateDoc(doc(db("A"), "chats/A_B"), { "blocked.A": deleteField() }));
});
test("blocked: a blocked chat cannot send", async () => {
  await seed((d) => updateDoc(doc(d, "chats/A_B"), { "blocked.A": true }));
  await assertFails(setDoc(doc(db("B"), "chats/A_B/messages/m2"), { from: "B", text: "x" }));
});

// ---------------------------------------------------------------- chat creation
test("create chat: a pending request with the sorted id works", async () => {
  await assertSucceeds(setDoc(doc(db("A"), "chats/A_C"), dm({ members: ["A", "C"] })));
});
test("create chat: wrong or unsorted id, or three members, is refused", async () => {
  await assertFails(setDoc(doc(db("A"), "chats/A_X"), dm({ members: ["A", "C"] })));
  await assertFails(setDoc(doc(db("A"), "chats/C_A"), dm({ members: ["A", "C"] })));
  await assertFails(setDoc(doc(db("A"), "chats/A_C"), dm({ members: ["A", "C", "D"] })));
  await assertFails(setDoc(doc(db("A"), "chats/B_C"), dm({ members: ["A", "B"] })));
});
test("create chat: I cannot claim to be someone else's requester", async () => {
  await assertFails(setDoc(doc(db("A"), "chats/A_C"), dm({ members: ["A", "C"], requester: "C" })));
});
test("create group: normal id ok; look-alike ids refused", async () => {
  await assertSucceeds(setDoc(doc(db("A"), "chats/grp1"), grp()));
  await assertFails(setDoc(doc(db("A"), "chats/X_Y"), grp()));
  await assertFails(setDoc(doc(db("A"), "chats/savedB"), grp({ members: ["A"] })));
  await assertSucceeds(setDoc(doc(db("A"), "chats/savedA"), grp({ members: ["A"] })));
});

// ---------------------------------------------------------------- messages
test("message: from must be me; outsiders cannot write", async () => {
  await assertSucceeds(setDoc(doc(db("B"), "chats/A_B/messages/m2"), { from: "B", text: "x" }));
  await assertFails(setDoc(doc(db("B"), "chats/A_B/messages/m3"), { from: "A", text: "x" }));
  await assertFails(setDoc(doc(db("C"), "chats/A_B/messages/m4"), { from: "C", text: "x" }));
});
test("reactions: only my own key", async () => {
  await assertSucceeds(updateDoc(doc(db("B"), "chats/A_B/messages/m1"), { "reactions.B": "👍" }));
  await assertFails(updateDoc(doc(db("B"), "chats/A_B/messages/m1"), { "reactions.A": "👍" }));
  await assertFails(updateDoc(doc(db("B"), "chats/A_B/messages/m1"), { reactions: { A: "x", B: "y" } }));
});
test("edit and delete: only the sender", async () => {
  await assertSucceeds(updateDoc(doc(db("A"), "chats/A_B/messages/m1"), { text: "edited", edited: true }));
  await assertFails(updateDoc(doc(db("B"), "chats/A_B/messages/m1"), { text: "forged", edited: true }));
  await assertSucceeds(updateDoc(doc(db("A"), "chats/A_B/messages/m1"), { deleted: true, text: "" }));
});

// ---------------------------------------------------------------- directory
test("users, handles: a single read works, listing does not", async () => {
  await assertSucceeds(getDoc(doc(db("A"), "users/B")));
  await assertSucceeds(getDoc(doc(db("A"), "handles/bob")));
  await assertFails(getDocs(collection(db("A"), "users")));
  await assertFails(getDocs(collection(db("A"), "handles")));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "users/B")));
});
test("users: only the owner writes, and nobody writes badges", async () => {
  await assertSucceeds(setDoc(doc(db("A"), "users/A"), { about: "hi" }, { merge: true }));
  await assertFails(setDoc(doc(db("B"), "users/A"), { about: "hi" }, { merge: true }));
  await assertFails(setDoc(doc(db("A"), "users/A"), { badges: ["email"] }, { merge: true }));
  await seed((d) => updateDoc(doc(d, "users/A"), { badges: ["email"] }));
  await assertSucceeds(setDoc(doc(db("A"), "users/A"), { about: "still fine" }, { merge: true }));
});

// ---------------------------------------------------------------- calls
const call = (extra = {}) => ({ caller: "A", callee: "B", state: "ringing", kind: "audio", ts: 1, ...extra });
test("calls: allowed inside an active, unblocked chat", async () => {
  await assertSucceeds(setDoc(doc(db("A"), "calls/c1"), call()));
  await assertSucceeds(setDoc(doc(db("B"), "calls/c2"), call({ caller: "B", callee: "A" })));
});
test("calls: strangers, forged callers, blocked and pending chats are refused", async () => {
  await assertFails(setDoc(doc(db("A"), "calls/c3"), call({ callee: "Z" })));
  await assertFails(setDoc(doc(db("A"), "calls/c4"), call({ caller: "B", callee: "A" })));
  await assertFails(setDoc(doc(db("A"), "calls/c5"), call({ callee: "A" })));
  await seed((d) => updateDoc(doc(d, "chats/A_B"), { "blocked.B": true }));
  await assertFails(setDoc(doc(db("A"), "calls/c6"), call()));
  await seed((d) => setDoc(doc(d, "chats/A_B"), { members: ["A", "B"], status: "pending", requester: "A" }));
  await assertFails(setDoc(doc(db("A"), "calls/c7"), call()));
});

// ---------------------------------------------------------------- phase 3a: pinned message, private lists, location message
test("pin: a member may pin and unpin a message id; an outsider may not", async () => {
  await assertSucceeds(updateDoc(doc(db("B"), "chats/A_B"), { pin: "m1" }));
  await assertSucceeds(updateDoc(doc(db("A"), "chats/A_B"), { pin: deleteField() }));
  await assertFails(updateDoc(doc(db("C"), "chats/A_B"), { pin: "m1" }));
});
test("pin: it does not open the door to protected fields", async () => {
  await assertFails(updateDoc(doc(db("B"), "chats/A_B"), { pin: "m1", status: "pending" }));
  await assertFails(updateDoc(doc(db("B"), "chats/A_B"), { pin: "m1", members: ["B"] }));
});
test("prefs: archive and unread marks are private to their owner", async () => {
  await assertSucceeds(setDoc(doc(db("A"), "prefs/A"), { archived: ["A_B"], marked: ["A_B"] }, { merge: true }));
  await assertFails(setDoc(doc(db("B"), "prefs/A"), { archived: [] }, { merge: true }));
  await assertFails(getDoc(doc(db("B"), "prefs/A")));
});
test("location message: a member can send one, the sender must be me", async () => {
  await assertSucceeds(setDoc(doc(db("B"), "chats/A_B/messages/m3"), { from: "B", kind: "loc", text: "40.18111,44.51361" }));
  await assertFails(setDoc(doc(db("B"), "chats/A_B/messages/m4"), { from: "A", kind: "loc", text: "40.18111,44.51361" }));
});

// ---------------------------------------------------------------- phase 3b-a: announcement groups, polls
const seedGroup = (extra = {}) => seed((d) => setDoc(doc(d, "chats/G1"), grp({ ...extra })));
test("send mode: in an announcement group only an admin can write", async () => {
  await seedGroup({ sendMode: "admins" });
  await assertFails(setDoc(doc(db("B"), "chats/G1/messages/x1"), { from: "B", text: "hi" }));
  await assertSucceeds(setDoc(doc(db("A"), "chats/G1/messages/x2"), { from: "A", text: "news" }));
});
test("send mode: a normal group lets every member write", async () => {
  await seedGroup();
  await assertSucceeds(setDoc(doc(db("B"), "chats/G1/messages/x3"), { from: "B", text: "hi" }));
  await seedGroup({ sendMode: "all" });
  await assertSucceeds(setDoc(doc(db("B"), "chats/G1/messages/x4"), { from: "B", text: "hi" }));
});
test("send mode: only an admin can switch it on or off", async () => {
  await seedGroup();
  await assertFails(updateDoc(doc(db("B"), "chats/G1"), { sendMode: "admins" }));
  await assertSucceeds(updateDoc(doc(db("A"), "chats/G1"), { sendMode: "admins" }));
  await assertFails(updateDoc(doc(db("B"), "chats/G1"), { sendMode: "all" }));
  await assertSucceeds(updateDoc(doc(db("A"), "chats/G1"), { sendMode: "all" }));
});
test("direct chat: a request cannot be created with admins, a send mode, a group flag or a block", async () => {
  await assertFails(setDoc(doc(db("A"), "chats/A_C"), dm({ members: ["A", "C"], admins: ["A"] })));
  await assertFails(setDoc(doc(db("A"), "chats/A_C"), dm({ members: ["A", "C"], sendMode: "admins" })));
  await assertFails(setDoc(doc(db("A"), "chats/A_C"), dm({ members: ["A", "C"], blocked: { C: true } })));
  await assertFails(setDoc(doc(db("A"), "chats/A_C"), dm({ members: ["A", "C"], group: true })));
  await assertSucceeds(setDoc(doc(db("A"), "chats/A_C"), dm({ members: ["A", "C"] })));
});
test("poll: a member votes with their own key only", async () => {
  await seedGroup();
  await seed((d) => setDoc(doc(d, "chats/G1/messages/p1"), { from: "A", kind: "poll", text: "sealed" }));
  const p = (uid) => doc(db(uid), "chats/G1/messages/p1");
  await assertSucceeds(updateDoc(p("B"), { "votes.B": "sealed-vote" }));
  await assertSucceeds(updateDoc(p("A"), { "votes.A": "sealed-vote" }));
  await assertFails(updateDoc(p("B"), { "votes.A": "forged" }));
  await assertSucceeds(updateDoc(p("B"), { "votes.B": deleteField() }));
  await assertFails(updateDoc(doc(db("C"), "chats/G1/messages/p1"), { "votes.C": "x" }));
});
test("poll: a voter cannot use a vote to rewrite the poll", async () => {
  await seedGroup();
  await seed((d) => setDoc(doc(d, "chats/G1/messages/p2"), { from: "A", kind: "poll", text: "sealed" }));
  await assertFails(updateDoc(doc(db("B"), "chats/G1/messages/p2"), { "votes.B": "1", text: "changed" }));
  await assertFails(updateDoc(doc(db("B"), "chats/G1/messages/p2"), { deleted: true }));
});
