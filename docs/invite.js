// KHOSQ Web, stage 10 (6j): group invitation links. The links are made and checked by the Worker (it holds the signing secret), exactly as for the
// phone (InviteClient.kt): an admin asks for a link ("/group/invite", with {chatId, reset}); whoever holds a link asks to look at the group and
// then to join it ("/group/join", with {token, preview} and {token}). The call is the phone's call: a POST with the person's own Firebase token.
// The Worker answers a page only when it comes from the web version's address (CORS), and only on these two group routes.
// Nothing secret is here, and nothing is written to Firestore by the page: the Worker adds the person to the group.

/** The shape of a token the Worker makes: <chat id>.<version>.<signature>. The page only checks the shape; the Worker checks the signature. */
export const TOKEN_RE = /^[A-Za-z0-9]{10,40}\.\d{1,6}\.[A-Za-z0-9_-]{22}$/;
const FOUND = /(?:khosq:\/\/join\/|(https:\/\/[^\s/?#]+)\/j\/)([A-Za-z0-9_.-]{20,120})/g;
const CHAT_RE = /^[A-Za-z0-9]{10,40}$/;

/**
 * The token inside what a person pasted: the link the Worker made (WORKER/j/TOKEN), the phone's own form (khosq://join/TOKEN), the link inside a
 * longer text ("Join my group: https://..."), or the bare token. A link of another address is not ours. null when there is none.
 * @param {string} text
 * @param {string} worker  the Worker's address (https://host)
 */
export function tokenFrom(text, worker) {
  const s = String(text == null ? "" : text).trim();
  if (!s || s.length > 600) return null;
  if (TOKEN_RE.test(s)) return s;
  let host = "";
  try {
    host = new URL(worker).host;
  } catch (e) {
    host = "";
  }
  for (const m of s.matchAll(FOUND)) {
    if (m[1] && (!host || new URL(m[1]).host !== host)) continue;
    let t = m[2];
    if (!TOKEN_RE.test(t)) t = t.replace(/\.+$/, ""); // a full stop that ends a sentence is not part of the token
    if (TOKEN_RE.test(t)) return t;
  }
  return null;
}

/** The Worker said no (or could not be reached: code 0). [code] is the HTTP status. */
export class Failure extends Error {
  constructor(code) {
    super("invite " + code);
    this.code = code;
  }
}

/** The word to tell the person when a call failed (the phone's explain()). [forLink]: an admin asked for a link, else somebody opened one. */
export function explainKey(e, forLink) {
  switch (e && e.code) {
    case 403:
      return forLink ? "invNotAdmin" : "invBad";
    case 404:
      return "invNoGroup";
    case 410:
      return "invExpired";
    case 409:
      return "invFull";
    case 429:
      return "invSlow";
    case 501:
      return "invOff";
    case 401:
      return "invSignedOut";
    default:
      return "invNet";
  }
}

const TIMEOUT_MS = 20000;

/**
 * @param {object} o
 * @param {{auth?: {currentUser?: {getIdToken?: () => Promise<string>}}}} o.f  the signed-in Firebase
 * @param {string} o.worker  the Worker's address
 * @param {typeof fetch} [o.fetchFn]
 */
export function makeInvite({ f, worker, fetchFn }) {
  const doFetch = fetchFn || ((...a) => fetch(...a));

  async function post(path, body) {
    if (!worker) throw new Failure(0);
    const user = f && f.auth && f.auth.currentUser;
    if (!user || typeof user.getIdToken !== "function") throw new Failure(401);
    let token = "";
    try {
      token = await user.getIdToken();
    } catch (e) {
      throw new Failure(401);
    }
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
    let r;
    try {
      r = await doFetch(worker + path, {
        method: "POST",
        headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
        body: JSON.stringify(body),
        signal: ctl.signal,
      });
    } catch (e) {
      throw new Failure(0);
    } finally {
      clearTimeout(timer);
    }
    if (!r.ok) throw new Failure(r.status);
    try {
      return await r.json();
    } catch (e) {
      throw new Failure(0);
    }
  }

  /** What the Worker says about a group behind a link: {chatId, title, count, member}. */
  function info(j) {
    if (!j || typeof j !== "object" || typeof j.chatId !== "string" || !CHAT_RE.test(j.chatId)) throw new Failure(0);
    const count = Number(j.count);
    return {
      chatId: j.chatId,
      title: typeof j.title === "string" ? j.title : "",
      count: Number.isFinite(count) && count > 0 ? Math.floor(count) : 0,
      member: j.member === true,
    };
  }

  return {
    /** The token inside what a person pasted (see tokenFrom), or null. */
    tokenOf: (text) => tokenFrom(text, worker),
    /** The invitation link of a group (admins only). With [reset] every older link stops working. */
    async link(chatId, reset) {
      const j = await post("/group/invite", { chatId: String(chatId), reset: reset === true });
      const link = j && j.link;
      // only an https link of the Worker's own address is shown and copied
      if (typeof link !== "string" || !link.startsWith(worker + "/j/") || link.length > 300) throw new Failure(0);
      return link;
    },
    /** Looks at the group behind a token without joining it. */
    async look(token) {
      return info(await post("/group/join", { token: String(token), preview: true }));
    },
    /** Joins the group behind a token. The Worker writes the person into the group; the chat then reaches the list by itself. */
    async join(token) {
      return info(await post("/group/join", { token: String(token) }));
    },
  };
}
