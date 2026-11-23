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
