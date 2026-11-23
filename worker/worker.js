// KHOSQ push relay (Cloudflare Workers, free plan).
// Secrets/variables to set in the Cloudflare dashboard:
//   SA_JSON  - the whole Firebase service-account JSON (Secret)
//   API_KEY  - Firebase Web API key (from google-services.json, "current_key")
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

export default {
  async fetch(req, env) {
    if (req.method !== "POST") return new Response("KHOSQ push ok");
    try {
      const idToken = (req.headers.get("Authorization") || "").replace("Bearer ", "");
      const raw = await req.text();
      if (raw.length > MAX_BODY) return new Response("too big", { status: 413 });
      const body = JSON.parse(raw);
      const uid = await verifyUser(idToken, env.API_KEY);
      if (!uid) return new Response("unauthorized", { status: 401 });
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
