// KHOSQ push relay (Cloudflare Workers, free plan).
// Secrets/variables to set in the Cloudflare dashboard:
//   SA_JSON  - the whole Firebase service-account JSON (Secret)
//   API_KEY  - Firebase Web API key (from google-services.json, "current_key")
//   INVITE_KEY - any long random text (Secret). Signs group invite links. Without it, invite links are off.
//   Either Cloudflare Realtime TURN:
//     TURN_KEY_ID    - TURN key id (text variable)
//     TURN_KEY_TOKEN - TURN key API token (Secret)
//   or Metered.ca free TURN:
//     METERED_DOMAIN - e.g. "khosq.metered.live" (text variable)
//     METERED_KEY    - API key (Secret)

let cached = { token: null, exp: 0 };

const enc = new TextEncoder();
function b64u(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function googleToken(sa) {
  const now = Math.floor(Date.now() / 1000);
  if (cached.token && cached.exp - 60 > now) return cached.token;
  const header = b64u(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claim = b64u(enc.encode(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/cloud-platform",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(header + "." + claim)));
  const jwt = header + "." + claim + "." + b64u(sig);
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: "grant_type=" + encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + jwt,
  });
  const j = await r.json();
  if (!j.access_token) throw new Error("google token: " + JSON.stringify(j));
  cached = { token: j.access_token, exp: now + (j.expires_in || 3600) };
  return cached.token;
}

async function verifyUser(idToken, apiKey) {
  const r = await fetch("https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=" + apiKey, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ idToken }),
  });
  if (!r.ok) return null;
  const j = await r.json();
  return j.users && j.users[0] ? j.users[0].localId : null;
}

async function pushTo(pid, auth, fs, uid, chatId, body, peer) {
  const tokRes = await fetch(fs + "tokens/" + encodeURIComponent(peer), { headers: auth });
  if (!tokRes.ok) return "no token";
  const tok = (await tokRes.json()).fields?.fcmToken?.stringValue;
  if (!tok) return "no token";
  const isCall = body.type === "call";
  const msg = {
    message: {
      token: tok,
      data: {
        type: isCall ? "call" : "msg",
        chatId,
        from: uid,
        title: String(body.title || "KHOSQ").slice(0, 60),
        body: String(body.body || "").slice(0, 120),
      },
      android: { priority: "HIGH", ttl: isCall ? "30s" : "86400s" },
    },
  };
  const r = await fetch("https://fcm.googleapis.com/v1/projects/" + pid + "/messages:send", {
    method: "POST",
    headers: { ...auth, "Content-Type": "application/json" },
    body: JSON.stringify(msg),
  });
  return r.ok ? "ok" : "fcm " + r.status;
}

// Best-effort limits. They live in one Worker instance only, so they stop a runaway client,
// not a determined attacker (that needs a Durable Object counter, planned for the AI step).
const hits = new Map();
function limited(uid, kind, max) {
  const now = Date.now();
  const k = uid + ":" + kind;
  const h = hits.get(k);
  if (!h || now - h.t > 60000) {
    if (hits.size > 2000) hits.clear();
    hits.set(k, { t: now, n: 1 });
    return false;
  }
  h.n += 1;
  return h.n > max;
}
const MAX_BODY = 4096;

// ---------------------------------------------------------------------------------------------- group invite links
// A link is  <worker>/j/<chatId>.<version>.<signature>.  The signature is an HMAC with INVITE_KEY, so a link cannot be
// made up. "Reset link" raises the version in invites/{chatId} (a document the app cannot read or write), and every
// older link stops working. Joining adds the caller to the group with the service account, so the checks here matter.
const MAX_MEMBERS = 50;
const TOKEN_RE = /^([A-Za-z0-9]{10,40})\.(\d{1,6})\.([A-Za-z0-9_-]{22})$/;
const UID_RE = /^[A-Za-z0-9]{1,128}$/;

async function hmac(secret, text) {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return b64u(new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(text)))).slice(0, 22);
}
function same(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}
async function makeToken(secret, chatId, v) {
  return chatId + "." + v + "." + (await hmac(secret, "inv|" + chatId + "|" + v));
}
async function readToken(secret, token) {
  const m = TOKEN_RE.exec(String(token || ""));
  if (!m) return null;
  const v = Number(m[2]);
  return same(await hmac(secret, "inv|" + m[1] + "|" + v), m[3]) ? { chatId: m[1], v } : null;
}

const str = (v) => ({ stringValue: v });
const json = (o, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });

async function groupRoute(path, body, uid, env, req) {
  if (!env.INVITE_KEY) return new Response("invites are off", { status: 501 });
  const sa = JSON.parse(env.SA_JSON);
  const at = await googleToken(sa);
  const auth = { Authorization: "Bearer " + at };
  const root = "projects/" + sa.project_id + "/databases/(default)/documents/";
  const fs = "https://firestore.googleapis.com/v1/" + root;
  const getDoc = async (p) => {
    const r = await fetch(fs + p, { headers: auth });
    if (r.status === 404) return null;
    if (!r.ok) throw new Error("firestore " + r.status);
    return r.json();
  };
  const inviteVersion = async (chatId) => {
    const d = await getDoc("invites/" + chatId);
    return Number(d?.fields?.v?.integerValue || 0);
  };
  const readChat = (doc) => ({
    group: doc.fields?.group?.booleanValue === true && doc.fields?.saved?.booleanValue !== true,
    members: (doc.fields?.members?.arrayValue?.values || []).map((x) => x.stringValue),
    admins: (doc.fields?.admins?.arrayValue?.values || []).map((x) => x.stringValue),
    title: doc.fields?.title?.stringValue || "",
  });

  if (path === "/group/invite") {
    const chatId = String(body.chatId || "");
    if (!/^[A-Za-z0-9]{10,40}$/.test(chatId)) return new Response("bad chat", { status: 400 });
    const doc = await getDoc("chats/" + chatId);
    if (!doc) return new Response("no chat", { status: 404 });
    const c = readChat(doc);
    // only an admin of a real group gets a link
    if (!c.group || chatId.startsWith("saved") || !c.admins.includes(uid) || !c.members.includes(uid)) return new Response("not an admin", { status: 403 });
    let v = await inviteVersion(chatId);
    if (body.reset === true) {
      v += 1;
      const r = await fetch(fs + "invites/" + chatId + "?updateMask.fieldPaths=v", {
        method: "PATCH",
        headers: { ...auth, "Content-Type": "application/json" },
        body: JSON.stringify({ fields: { v: { integerValue: String(v) } } }),
      });
      if (!r.ok) return new Response("firestore " + r.status, { status: 502 });
    }
    const origin = new URL(req.url).origin;
    return json({ link: origin + "/j/" + (await makeToken(env.INVITE_KEY, chatId, v)), v });
  }

  // /group/join: "preview" only looks, otherwise the caller becomes a member
  const t = await readToken(env.INVITE_KEY, body.token);
  if (!t) return new Response("bad invite", { status: 403 });
  if ((await inviteVersion(t.chatId)) !== t.v) return new Response("invite expired", { status: 410 });
  const doc = await getDoc("chats/" + t.chatId);
  if (!doc) return new Response("no chat", { status: 404 });
  const c = readChat(doc);
  if (!c.group) return new Response("no chat", { status: 404 });
  const info = { chatId: t.chatId, title: c.title, count: c.members.length };
  if (c.members.includes(uid)) return json({ ...info, member: true });
  if (c.members.length >= MAX_MEMBERS) return new Response("group full", { status: 409 });
  if (body.preview === true) return json({ ...info, member: false });
  if (!UID_RE.test(uid)) return new Response("bad user", { status: 403 });

  const u = await getDoc("users/" + uid);
  const handle = String(u?.fields?.handle?.stringValue || "").slice(0, 40);
  const name = String(u?.fields?.name?.stringValue || handle || "?").slice(0, 40);
  const msgId = b64u(crypto.getRandomValues(new Uint8Array(15))).slice(0, 20);
  const r = await fetch("https://firestore.googleapis.com/v1/projects/" + sa.project_id + "/databases/(default)/documents:commit", {
    method: "POST",
    headers: { ...auth, "Content-Type": "application/json" },
    body: JSON.stringify({
      writes: [
        {
          update: {
            name: root + "chats/" + t.chatId,
            fields: {
              names: { mapValue: { fields: { [uid]: str(name) } } },
              handles: { mapValue: { fields: { [uid]: str(handle) } } },
            },
          },
          updateMask: { fieldPaths: ["names.`" + uid + "`", "handles.`" + uid + "`"] },
          updateTransforms: [{ fieldPath: "members", appendMissingElements: { values: [str(uid)] } }],
          currentDocument: { exists: true },
        },
        {
          update: {
            name: root + "chats/" + t.chatId + "/messages/" + msgId,
            fields: { from: str(uid), text: str(""), kind: str("sys"), code: str("grp_joined"), arg: str("") },
          },
          updateTransforms: [{ fieldPath: "ts", setToServerValue: "REQUEST_TIME" }],
          currentDocument: { exists: false },
        },
      ],
    }),
  });
  if (!r.ok) return new Response("firestore " + r.status, { status: 502 });
  return json({ ...info, count: info.count + 1, member: true, joined: true });
}

// The page a link opens in a browser. It only holds a button that opens the app; it does not look at the group at all.
function invitePage(token) {
  const html = `<!doctype html><html lang="hy"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>KHOSQ</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0a0c14;color:#eef0fa;font-family:system-ui,sans-serif}
main{max-width:360px;padding:28px;text-align:center}h1{font-size:26px;margin:0 0 6px}p{color:#aab0c8;line-height:1.5;margin:8px 0}
a.b{display:block;margin:22px 0 10px;padding:14px;border-radius:14px;background:#5560ee;color:#fff;font-weight:600;text-decoration:none}</style></head>
<body><main><h1>KHOSQ</h1>
<p>Հրավեր խմբին · Приглашение в группу · Group invite</p>
<a class="b" href="intent://join/${token}#Intent;scheme=khosq;package=am.khosq.app;end">Բացել KHOSQ-ում · Открыть · Open</a>
<p>Բացիր այս էջը այն հեռախոսում, որտեղ տեղադրված է KHOSQ-ը։<br>Откройте на телефоне, где установлен KHOSQ.<br>Open this on the phone where KHOSQ is installed.</p>
</main></body></html>`;
  return new Response(html, {
    status: 200,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'",
    },
  });
}

export default {
  async fetch(req, env) {
    if (req.method === "GET") {
      const page = /^\/j\/([A-Za-z0-9_.-]{20,120})$/.exec(new URL(req.url).pathname);
      if (page) return invitePage(page[1]);
    }
    if (req.method !== "POST") return new Response("KHOSQ push ok");
    try {
      const idToken = (req.headers.get("Authorization") || "").replace("Bearer ", "");
      const raw = await req.text();
      if (raw.length > MAX_BODY) return new Response("too big", { status: 413 });
      const body = JSON.parse(raw);
      const uid = await verifyUser(idToken, env.API_KEY);
      if (!uid) return new Response("unauthorized", { status: 401 });
      const path = new URL(req.url).pathname;
      if (path === "/group/invite" || path === "/group/join") {
        if (limited(uid, "grp", 20)) return new Response("slow down", { status: 429 });
        return await groupRoute(path, body, uid, env, req);
      }
      if (limited(uid, body.type === "ice" ? "ice" : "msg", body.type === "ice" ? 30 : 120)) {
        return new Response("slow down", { status: 429 });
      }

      // calls ask for TURN settings; the TURN keys stay secret here in the Worker
      if (body.type === "ice") {
        let list = null;
        if (env.METERED_DOMAIN && env.METERED_KEY) {
          // Metered.ca free TURN (20 GB / month)
          const r = await fetch("https://" + env.METERED_DOMAIN + "/api/v1/turn/credentials?apiKey=" + env.METERED_KEY);
          if (!r.ok) return new Response("metered " + r.status, { status: 502 });
          const j = await r.json();
          list = Array.isArray(j) ? j : j.iceServers;
        } else if (env.TURN_KEY_ID && env.TURN_KEY_TOKEN) {
          // Cloudflare Realtime TURN
          const r = await fetch(
            "https://rtc.live.cloudflare.com/v1/turn/keys/" + env.TURN_KEY_ID + "/credentials/generate-ice-servers",
            {
              method: "POST",
              headers: { Authorization: "Bearer " + env.TURN_KEY_TOKEN, "Content-Type": "application/json" },
              body: JSON.stringify({ ttl: 86400 }),
            });
          if (!r.ok) return new Response("turn " + r.status, { status: 502 });
          list = (await r.json()).iceServers;
        }
        if (!list) return new Response("no turn key", { status: 501 });
        return new Response(JSON.stringify({ iceServers: list }), { status: 200, headers: { "Content-Type": "application/json" } });
      }

      const chatId = String(body.chatId || "");
      const isCall = body.type === "call";
      // one-to-one chat ids look like "uidA_uidB"; group chat ids have no underscore
      const parts = chatId.split("_");
      const group = parts.length === 1 && chatId.length > 0;
      if (!group && (parts.length !== 2 || !parts.includes(uid))) return new Response("bad chat", { status: 403 });

      const sa = JSON.parse(env.SA_JSON);
      const pid = sa.project_id;
      const at = await googleToken(sa);
      const fs = "https://firestore.googleapis.com/v1/projects/" + pid + "/databases/(default)/documents/";
      const auth = { Authorization: "Bearer " + at };

      const chatRes = await fetch(fs + "chats/" + encodeURIComponent(chatId), { headers: auth });
      if (!chatRes.ok) return new Response("no chat", { status: 403 });
      const doc = await chatRes.json();
      const members = (doc.fields?.members?.arrayValue?.values || []).map((v) => v.stringValue);
      if (!members.includes(uid)) return new Response("not a member", { status: 403 });
      // same meaning as the Firestore rules: a blocked chat, or one the other side has not accepted, sends nothing
      const blocked = doc.fields?.blocked?.mapValue?.fields;
      if (blocked && Object.keys(blocked).length > 0) return new Response("skipped", { status: 200 });
      const status = doc.fields?.status?.stringValue || "active";
      const requester = doc.fields?.requester?.stringValue || "";
      if (status !== "active" && (isCall || requester !== uid)) return new Response("skipped", { status: 200 });

      const targets = members.filter((m) => m !== uid).slice(0, 20);

      const out = await Promise.all(targets.map((t) => pushTo(pid, auth, fs, uid, chatId, body, t)));
      return new Response(out.join(","), { status: 200 });
    } catch (e) {
      return new Response("error: " + e.message, { status: 500 });
    }
  },
};
