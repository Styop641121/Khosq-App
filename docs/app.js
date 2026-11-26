// KHOSQ Web, stage 1: sign in by QR code. Shows a code, waits for the phone to answer, opens the phone's sealed packet (the identity key),
// signs in to Firebase as the same account, keeps the key in this browser, and shows who is signed in.
// Chats and messages come in the next stages.
import { WORKER, FIREBASE, QR_LIFETIME_MS, POLL_MS } from "./config.js";
import { newSession, openPacket, importIdentityKey, b64u } from "./link.js";
import { qrSvg } from "./qr.js";
import { loadIdentity, saveIdentity, clearIdentity } from "./store.js";
import { startLang, setLang, lang, t, LANGS } from "./i18n.js";

const $ = (id) => document.getElementById(id);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const VIEWS = ["boot", "login", "home"];
const show = (name) => VIEWS.forEach((v) => ($(v).hidden = v !== name));

/** A problem the person can be told about: [key] is a word of i18n.js. */
class Failure extends Error {
  constructor(key) {
    super(key);
    this.key = key;
  }
}

// ------------------------------------------------------------------------------------------------ words on the screen
let statusKey = "";
let statusKind = "";
function setStatus(key, kind = "") {
  statusKey = key;
  statusKind = kind;
  const el = $("status");
  el.dataset.kind = kind;
  el.textContent = key ? t(key) : "";
}

let homeState = null;
function applyLang() {
  document.querySelectorAll("[data-i18n]").forEach((el) => (el.textContent = t(el.dataset.i18n)));
  for (const b of $("langs").children) b.setAttribute("aria-pressed", String(b.dataset.lang === lang()));
  setStatus(statusKey, statusKind);
  if (homeState) renderHome(homeState);
}

function buildLangs() {
  for (const [code, name] of LANGS) {
    const b = document.createElement("button");
    b.type = "button";
    b.dataset.lang = code;
    b.textContent = name;
    b.addEventListener("click", () => {
      setLang(code);
      applyLang();
    });
    $("langs").append(b);
  }
}

// ------------------------------------------------------------------------------------------------ Firebase
let fbPromise = null;
/** Starts Firebase once: the key and project come from the Worker, the library from Google. */
function firebase() {
  if (!fbPromise) {
    fbPromise = (async () => {
      let r;
      try {
        r = await fetch(WORKER + "/link/config");
      } catch (e) {
        throw new Failure("errServer");
      }
      if (r.status === 501) throw new Failure("errOff");
      if (!r.ok) throw new Failure("errServer");
      const cfg = await r.json();
      let mods;
      try {
        mods = await Promise.all([import(FIREBASE + "firebase-app.js"), import(FIREBASE + "firebase-auth.js"), import(FIREBASE + "firebase-firestore.js")]);
      } catch (e) {
        throw new Failure("errServer");
      }
      const [appM, authM, fsM] = mods;
      const app = appM.initializeApp({ apiKey: cfg.apiKey, projectId: cfg.projectId, authDomain: cfg.projectId + ".firebaseapp.com" });
      // no pop-up or redirect sign-in is ever used, so none of its machinery (and no hidden frame) is loaded
      const auth = authM.initializeAuth(app, { persistence: authM.indexedDBLocalPersistence });
      return { auth, authM, db: fsM.getFirestore(app), fsM };
    })().catch((e) => {
      fbPromise = null;
      throw e;
    });
  }
  return fbPromise;
}

const firstUser = (f) =>
  new Promise((resolve) => {
    let done = false;
    const off = f.authM.onAuthStateChanged(f.auth, (u) => {
      if (done) return;
      done = true;
      resolve(u);
      setTimeout(() => off(), 0);
    });
  });

// ------------------------------------------------------------------------------------------------ the Worker's post box
async function post(path, body) {
  let r;
  try {
    r = await fetch(WORKER + path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  } catch (e) {
    throw new Failure("errServer");
  }
  if (r.status === 501) throw new Failure("errOff");
  return r;
}

let run = 0; // every sign-in attempt has a number; a newer one stops the older
let refreshNow = false;

/** Waits until the phone has answered this code. Returns the packet, or "expired" when it is time for a new code. */
async function waitForPhone(s, my) {
  const until = Date.now() + QR_LIFETIME_MS;
  while (run === my) {
    if (refreshNow || Date.now() > until) {
      refreshNow = false;
      return "expired";
    }
    if (document.hidden) {
      // nobody looks at the page: do not ask the Worker, and let the code age
      await new Promise((r) => document.addEventListener("visibilitychange", r, { once: true }));
      if (document.hidden) continue;
      return "expired";
    }
    try {
      const r = await post("/link/poll", { sid: s.sid });
      if (r.status === 429) {
        await sleep(6000);
        continue;
      }
      if (r.ok) {
        const j = await r.json();
        if (j.state === "ready") return j;
        if (statusKind === "bad") setStatus("waiting", "busy");
      }
    } catch (e) {
      if (e instanceof Failure && e.key === "errOff") throw e;
      setStatus("errServer", "bad");
    }
    await sleep(POLL_MS);
  }
  return "stopped";
}

/** The phone has answered: open the packet, prove it to the Worker, sign in, keep the key. Throws a Failure when something is wrong. */
async function link(s, packet) {
  setStatus("linking", "busy");
  const got = await openPacket(s, packet);
  if (!got) throw new Failure("errPacket");
  const r = await post("/link/finish", { sid: s.sid, r: got.r });
  if (!r.ok) throw new Failure("errSession");
  const { tok, uid } = await r.json();
  if (uid !== got.uid) throw new Failure("errSession");
  const f = await firebase();
  try {
    await f.authM.signInWithCustomToken(f.auth, tok);
  } catch (e) {
    throw new Failure("errSession");
  }
  const key = await importIdentityKey(got.priv);
  got.priv.fill(0);
  const id = { uid, pub: got.pub, key, at: Date.now() };
  try {
    await saveIdentity(id);
  } catch (e) {
    await f.authM.signOut(f.auth);
    throw new Failure("errStorage");
  }
  return id;
}

async function login() {
  const my = ++run;
  show("login");
  $("refresh").hidden = false;
  $("retry").hidden = true;
  while (run === my) {
    let s;
    try {
      s = await newSession();
    } catch (e) {
      setStatus("errSession", "bad");
      return;
    }
    $("qr").innerHTML = qrSvg(s.qr, "M");
    $("qr").dataset.qr = s.qr;
    $("code").textContent = s.code;
    setStatus("waiting", "busy");
    try {
      const got = await waitForPhone(s, my);
      if (got === "stopped") return;
      if (got === "expired") continue;
      const id = await link(s, got);
      if (run === my) await home(id);
      return;
    } catch (e) {
      if (!(e instanceof Failure)) throw e;
      setStatus(e.key, "bad");
      if (e.key === "errOff") return;
      await sleep(3000); // the person reads it, then a new code appears
    }
  }
}

// ------------------------------------------------------------------------------------------------ signed in
async function fingerprint(pub) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(pub)));
  return b64u(d.slice(0, 16));
}

function renderHome(h) {
  $("name").textContent = h.name;
  $("handle").textContent = h.handle ? "@" + h.handle : "";
  $("avatar").textContent = (h.name || "?").trim().slice(0, 1).toUpperCase();
  const list = $("checks");
  list.replaceChildren();
  const add = (text, bad) => {
    const li = document.createElement("li");
    li.textContent = text;
    if (bad) li.className = "bad";
    list.append(li);
  };
  add(t("ok1"));
  add(t("ok2"));
  if (h.match === true) add(t("ok3"));
  if (h.match === false) add(t("bad3"), true);
  $("keyid").textContent = t("keyId") + ": " + h.fp;
}

async function home(id) {
  run++; // stops any sign-in loop
  const f = await firebase();
  homeState = { name: "…", handle: "", fp: await fingerprint(id.pub), match: null };
  show("home");
  renderHome(homeState);
  try {
    const snap = await f.fsM.getDoc(f.fsM.doc(f.db, "users", id.uid));
    const d = snap.exists() ? snap.data() : {};
    homeState = { ...homeState, name: d.name || d.handle || "KHOSQ", handle: d.handle || "", match: typeof d.pk === "string" ? d.pk === id.pub : null };
  } catch (e) {
    homeState = { ...homeState, name: "KHOSQ" };
  }
  renderHome(homeState);
  $("logout").onclick = () => {
    $("askText").textContent = t("logoutAsk");
    $("ask").showModal();
  };
  $("askNo").onclick = () => $("ask").close();
  $("askYes").onclick = async () => {
    $("ask").close();
    try {
      await f.authM.signOut(f.auth);
    } catch (e) {
      // the key is removed in any case
    }
    await clearIdentity();
    location.reload();
  };
}

// ------------------------------------------------------------------------------------------------ start
async function fail(key, again) {
  show("login");
  run++;
  $("qr").replaceChildren();
  $("code").textContent = "";
  setStatus(key, "bad");
  $("refresh").hidden = true;
  $("retry").hidden = false;
  $("retry").onclick = () => again();
}

async function boot() {
  const id = await loadIdentity();
  if (!id) return login();
  try {
    const f = await firebase();
    const user = await firstUser(f);
    if (user && user.uid === id.uid) return await home(id);
  } catch (e) {
    // the server could not be reached: the key stays, the person can try again
    if (e instanceof Failure) return fail(e.key, boot);
    throw e;
  }
  // the account is not signed in any more: the stored key belongs to a session that is over
  await clearIdentity();
  return login();
}

startLang();
buildLangs();
applyLang();
$("refresh").addEventListener("click", () => {
  refreshNow = true;
});
boot().catch(() => fail("errSession", boot));
