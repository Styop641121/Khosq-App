// KHOSQ Web, stage 2: the chat list and the messages, read only. Everything the page puts on the screen from a message goes in with
// textContent (never as markup), so what somebody wrote can never act as part of the page.
import { t, tf, lang } from "./i18n.js";
import { makeData, PAGE } from "./data.js";
import { makeE2E } from "./e2e.js";
import { STICKERS } from "./stickers.js";
import { pins, getPhoto, putPhoto } from "./store.js";

const $ = (id) => document.getElementById(id);
const num = (v) => (typeof v === "number" && Number.isFinite(v) ? v : 0);
const first = (s) => Array.from(String(s || "?").trim())[0] || "?";

/** A small helper: <tag class="cls">text</tag>. */
function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
}

// ------------------------------------------------------------------------------------------------ time
const fmtCache = new Map();
function fmt(opts) {
  const key = lang() + JSON.stringify(opts);
  if (!fmtCache.has(key)) fmtCache.set(key, new Intl.DateTimeFormat(lang(), opts));
  return fmtCache.get(key);
}
const dayStart = (ms) => {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
};
const clock = (ms) => fmt({ hour: "2-digit", minute: "2-digit" }).format(ms);

/** The list's small time: the clock today, "Yesterday", the weekday this week, the date after that. */
function stamp(ms) {
  if (!ms) return "";
  const days = Math.round((dayStart(Date.now()) - dayStart(ms)) / 86400000);
  if (days <= 0) return clock(ms);
  if (days === 1) return t("yesterday");
  if (days < 7) return fmt({ weekday: "short" }).format(ms);
  return fmt({ day: "2-digit", month: "2-digit", year: "2-digit" }).format(ms);
}

function dayLabel(ms) {
  const days = Math.round((dayStart(Date.now()) - dayStart(ms)) / 86400000);
  if (days <= 0) return t("today");
  if (days === 1) return t("yesterday");
  const sameYear = new Date(ms).getFullYear() === new Date().getFullYear();
  return fmt(sameYear ? { day: "numeric", month: "long" } : { day: "numeric", month: "long", year: "numeric" }).format(ms);
}

// ------------------------------------------------------------------------------------------------ the one-line previews
const KIND_PREVIEW = {
  img: () => "📷 " + t("photo"),
  voice: () => "🎤 " + t("voice"),
  loc: () => "📍 " + t("location"),
  poll: () => "📊 " + t("poll"),
  sticker: () => "🙂 " + t("sticker"),
  sos: () => "🆘 SOS",
  call_missed: () => "📵 " + t("call_missed"),
};

/** What a quoted message shows (the phone stores a marker for a photo, a voice message ... instead of text). */
function quoteText(raw) {
  switch (raw) {
    case "img": return KIND_PREVIEW.img();
    case "voice": return KIND_PREVIEW.voice();
    case "loc": return KIND_PREVIEW.loc();
    case "sticker": return KIND_PREVIEW.sticker();
    default: return raw;
  }
}

const COLORS = 8;
const hue = (id) => {
  let h = 0;
  for (const c of String(id)) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return (h % COLORS) * (360 / COLORS);
};

const SOS_WORD = "🆘 SOS";

/** Starts the chat screens. [f] is the signed-in Firebase, [id] the identity (uid, pub, key), [user] my own profile. Returns {stop, relang}. */
export function startShell({ f, id, user, onAbout }) {
  const uid = id.uid;
  const data = makeData(f, uid);
  const e2e = makeE2E({
    uid,
    key: id.key,
    pub: id.pub,
    publicOf: async (who) => {
      const u = await data.user(who);
      return u && typeof u.pk === "string" ? u.pk : null;
    },
    pins,
  });

  const S = {
    chats: [],
    prefs: { pins: [], muted: [], archived: [] },
    filter: "all",
    q: "",
    open: null, // the vm of the chat on the right
    previews: new Map(),
    listSig: "",
    users: new Map(),
    live: [],
    older: [],
    oldest: null,
    more: false,
    msgToken: 0,
    opened: new Map(),
    urls: new Set(),
    rows: new Map(),
    stops: [],
    msgStop: null,
    refreshed: new Set(),
    warnPeer: null,
    loadedChats: false,
    failed: false,
    loadingMsgs: false,
    hold: false,
  };

  // ---------------------------------------------------------------------------------------------- pictures
  const photoUrls = new Map();
  const photoWait = new Map();
  let busy = 0;
  const queue = [];
  const slot = () =>
    new Promise((resolve) => {
      const go = () => {
        busy++;
        resolve();
      };
      if (busy < 3) go();
      else queue.push(go);
    });
  const release = () => {
    busy--;
    const next = queue.shift();
    if (next) next();
  };

  /** A blob address for a person's or a group's picture, or null when there is none. Remembered in this browser by version. */
  async function photoUrl(who, v) {
    if (!who || !v) return null;
    const have = photoUrls.get(who);
    if (have && have.v >= v) return have.url;
    const key = who + "|" + v;
    if (!photoWait.has(key)) {
      photoWait.set(
        key,
        (async () => {
          await slot();
          try {
            let rec = await getPhoto(who);
            if (!rec || rec.v < v) {
              const p = await data.photo(who);
              rec = p ? { v: p.v, bytes: p.bytes } : null;
              if (p) putPhoto(who, p.v, p.bytes);
            }
            if (!rec) return null;
            const url = URL.createObjectURL(new Blob([rec.bytes], { type: "image/jpeg" }));
            photoUrls.set(who, { v: rec.v, url });
            return url;
          } finally {
            release();
          }
        })().finally(() => photoWait.delete(key)),
      );
    }
    return photoWait.get(key);
  }

  function avatar(who, name, v, cls = "av") {
    const a = el("span", cls);
    a.style.setProperty("--h", String(hue(who || name)));
    a.append(el("span", "i", first(name)));
    const have = photoUrls.get(who);
    const put = (url) => {
      const img = document.createElement("img");
      img.alt = "";
      img.src = url;
      a.append(img);
    };
    if (have && v && have.v >= v) {
      put(have.url);
    } else if (who && v) {
      photoUrl(who, v).then((url) => {
        if (url) put(url);
      });
    }
    return a;
  }

  // ---------------------------------------------------------------------------------------------- names
  const userName = (who) => {
    const u = S.users.get(who);
    if (u) return u.name || u.handle || "…";
    if (!S.users.has(who)) {
      S.users.set(who, null);
      data.user(who).then((x) => {
        S.users.set(who, x || {});
        S.listSig = "";
        renderList();
        if (S.open) renderMessages();
      });
    }
    return "…";
  };

  /** A person's name as the chat knows it (also mine). */
  function nameOf(chat, who) {
    const n = chat && chat.names && chat.names[who];
    return typeof n === "string" && n ? n : userName(who);
  }
  /** The same, but "You" for me (in quotes and previews). */
  const nameIn = (chat, who) => (who === uid ? t("you") : nameOf(chat, who));

  // ---------------------------------------------------------------------------------------------- the chat list
  function chatVm(c) {
    const group = c.group === true;
    const saved = group && c.saved === true;
    const members = Array.isArray(c.members) ? c.members : [];
    const peer = group ? null : members.find((m) => m !== uid) || null;
    const title = saved ? t("saved") : group ? c.title || t("group") : (c.names && c.names[peer]) || (peer && userName(peer)) || "?";
    return {
      id: c.id,
      raw: c,
      group,
      saved,
      peer,
      title,
      lastTs: num(c.lastTs),
      unread: c.unread && typeof c.unread === "object" ? num(c.unread[uid]) : 0,
      pinned: S.prefs.pins.includes(c.id),
      muted: S.prefs.muted.includes(c.id),
      archived: S.prefs.archived.includes(c.id),
      request: c.status === "pending" && c.requester && c.requester !== uid,
      avatarId: saved ? null : group ? c.id : peer,
      avatarV: saved ? 0 : group ? num(c.gpv) : c.pv && peer ? num(c.pv[peer]) : 0,
      handle: peer && c.handles && typeof c.handles[peer] === "string" ? c.handles[peer] : "",
      members,
    };
  }

  const inFilter = (v) => {
    switch (S.filter) {
      case "personal": return !v.archived && (!v.group || v.saved);
      case "groups": return !v.archived && v.group && !v.saved;
      case "archive": return v.archived;
      default: return !v.archived;
    }
  };

  /** The line under the name. Sealed text is opened in the background and the list draws itself again when it is ready. */
  function preview(v) {
    const c = v.raw;
    const kind = c.lastKind || "text";
    const who = c.lastFrom;
    let body;
    if (kind !== "text" && KIND_PREVIEW[kind]) {
      body = KIND_PREVIEW[kind]();
    } else if (c.lastEnc === true) {
      const key = v.id + "|" + num(c.lastTs) + "|" + String(c.lastText || "").slice(-24);
      if (S.previews.has(key)) {
        body = S.previews.get(key);
      } else {
        S.previews.set(key, t("sealed"));
        e2e
          .openLast(v.id, who, c.lastText || "", c.lastW || {})
          .catch(() => null)
          .then((txt) => {
            if (txt !== null && txt !== undefined) {
              S.previews.set(key, txt);
              S.listSig = "";
              renderList();
            }
          });
        body = t("sealed");
      }
    } else {
      body = typeof c.lastText === "string" ? c.lastText : "";
    }
    body = body.replace(/\s+/g, " ").slice(0, 90);
    if (!body) return "";
    if (v.group && !v.saved && who) return nameIn(c, who) + ": " + body;
    if (who === uid && !v.saved) return t("you") + ": " + body;
    return body;
  }

  function vms() {
    const q = S.q.trim().toLowerCase();
    return S.chats
      .map(chatVm)
      .filter((v) => inFilter(v) && (!q || v.title.toLowerCase().includes(q)))
      .sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || b.lastTs - a.lastTs);
  }

  function renderList() {
    const list = vms();
    const rows = list.map((v) => ({ v, line: preview(v) }));
    const sig = JSON.stringify([S.filter, S.q, S.open && S.open.id, S.loadedChats, S.failed, lang(), rows.map((r) => [r.v.id, r.v.title, r.v.lastTs, r.v.unread, r.v.pinned, r.v.muted, r.v.request, r.v.avatarV, r.line])]);
    if (sig === S.listSig) return;
    S.listSig = sig;
    const ul = $("list");
    const kids = [];
    for (const { v, line } of rows) {
      const li = el("li");
      const b = el("button", "item" + (S.open && S.open.id === v.id ? " on" : ""));
      b.type = "button";
      b.dataset.id = v.id;
      b.append(v.saved ? el("span", "av saved", "🔖") : avatar(v.avatarId, v.title, v.avatarV));
      const mid = el("span", "mid");
      const top = el("span", "l1");
      const name = el("b", "nm", v.title);
      top.append(name);
      if (v.pinned) top.append(el("span", "mini", "📌"));
      if (v.muted) top.append(el("span", "mini", "🔇"));
      top.append(el("time", "when" + (v.unread ? " hot" : ""), stamp(v.lastTs)));
      const bot = el("span", "l2");
      if (v.request) bot.append(el("span", "tag", t("request")));
      bot.append(el("span", "ln", line));
      if (v.unread) bot.append(el("span", "badge" + (v.muted ? " quiet" : ""), v.unread > 99 ? "99+" : String(v.unread)));
      mid.append(top, bot);
      b.append(mid);
      b.addEventListener("click", () => openChat(v.id));
      li.append(b);
      kids.push(li);
    }
    ul.replaceChildren(...kids);
    const empty = $("listEmpty");
    empty.hidden = rows.length > 0 || !S.loadedChats;
    empty.textContent = S.failed ? t("errChats") : S.q ? t("noMatch") : t("noChats");
    $("listLoading").hidden = S.loadedChats;
  }

  // ---------------------------------------------------------------------------------------------- opening what a message holds
  const withoutOlderThanMe = (list) => {
    const i = list.findIndex((m) => m.enc && (m.from === uid || (m.wraps && m.wraps[uid] !== undefined)));
    if (i === 0) return list;
    const cut = i < 0 ? list.length : i;
    return list.filter((m, k) => k >= cut || !m.enc);
  };

  async function openOne(chatId, chatK, m) {
    const v = { ok: true, text: "", reply: "", bytes: null, votes: {} };
    if (m.deleted) return v;
    if (!m.enc) {
      v.text = m.text;
      v.reply = m.reply;
      v.bytes = m.data;
      v.votes = { ...m.votes };
      return v;
    }
    const key = e2e.isGroup(chatId) ? await e2e.groupKey(chatId, m.from, m.wraps).catch(() => null) : chatK;
    if (!key) {
      v.ok = false;
      return v;
    }
    if (m.text) {
      const x = await e2e.openText(key, chatId, m.from, "t", m.text);
      if (x === null) v.ok = false;
      else v.text = x;
    }
    if (m.reply) v.reply = (await e2e.openText(key, chatId, m.from, "r", m.reply)) || "";
    if (m.data) {
      v.bytes = await e2e.openBytes(key, chatId, m.from, "d", m.data);
      if (!v.bytes) v.ok = false;
    }
    for (const [voter, sealed] of Object.entries(m.votes)) {
      const x = await e2e.openText(key, chatId, voter, "v", sealed);
      if (x !== null) v.votes[voter] = x;
    }
    return v;
  }

  // ---------------------------------------------------------------------------------------------- one message on the screen
  const blobUrl = (bytes, type) => {
    const url = URL.createObjectURL(new Blob([bytes], { type }));
    S.urls.add(url);
    return url;
  };

  function systemText(chat, m) {
    if (m.kind === "call") {
      switch (m.code) {
        case "call_done": {
          const s = Math.max(0, m.dur);
          return (m.vid ? t("videoCall") : t("call")) + " · " + Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
        }
        case "call_missed":
        case "call_declined":
        case "call_cancelled":
          return t(m.code);
        default:
          return m.text || t("call");
      }
    }
    if (/^grp_[a-z_]+$/.test(m.code) && t(m.code) !== m.code) return tf(m.code, nameOf(chat, m.from), m.arg);
    return "";
  }

  function pollBox(m, view) {
    let poll = null;
    try {
      const j = JSON.parse(view.text);
      if (j && typeof j.q === "string" && Array.isArray(j.o) && j.o.length >= 2 && j.o.length <= 6 && j.o.every((x) => typeof x === "string")) poll = j;
    } catch (e) {
      poll = null;
    }
    const box = el("div", "poll");
    if (!poll) {
      box.append(el("div", "pq", "📊 " + t("poll")));
      return box;
    }
    const counts = poll.o.map(() => 0);
    let mineIdx = -1;
    let total = 0;
    for (const [voter, raw] of Object.entries(view.votes)) {
      const i = Number(raw);
      if (Number.isInteger(i) && i >= 0 && i < poll.o.length) {
        counts[i]++;
        total++;
        if (voter === uid) mineIdx = i;
      }
    }
    box.append(el("div", "pq", "📊 " + poll.q));
    poll.o.forEach((o, i) => {
      const row = el("div", "po" + (i === mineIdx ? " mine" : ""));
      const bar = el("span", "bar");
      bar.style.width = (total ? Math.round((counts[i] * 100) / total) : 0) + "%";
      row.append(bar, el("span", "ot", o), el("span", "oc", String(counts[i])));
      box.append(row);
    });
    return box;
  }

  function placeBox(text) {
    const box = el("div", "place");
    const m = /^(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)$/.exec(text.trim());
    box.append(el("div", "pq", "📍 " + t("location")));
    if (m) {
      const lat = Number(m[1]);
      const lng = Number(m[2]);
      if (Math.abs(lat) <= 90 && Math.abs(lng) <= 180) {
        const a = el("a", "lnk", t("openMap") + " · " + m[1] + ", " + m[2]);
        a.href = "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lng + "#map=16/" + lat + "/" + lng;
        a.target = "_blank";
        a.rel = "noopener noreferrer";
        box.append(a);
      }
    }
    return box;
  }

  function content(m, view) {
    if (m.deleted) return el("div", "gone", "🚫 " + t("deleted"));
    if (!view.ok && !view.text && !view.bytes) return el("div", "gone", "🔒 " + (m.enc ? t("cantOpen") : t("sealed")));
    switch (m.kind) {
      case "img": {
        if (!view.bytes) return el("div", "gone", "🔒 " + t("cantOpen"));
        const img = document.createElement("img");
        img.className = "pic";
        img.alt = t("photo");
        img.src = view.url || (view.url = blobUrl(view.bytes, "image/jpeg"));
        img.addEventListener("click", () => {
          $("shotImg").src = img.src;
          $("shot").showModal();
        });
        return img;
      }
      case "voice": {
        if (!view.bytes) return el("div", "gone", "🔒 " + t("cantOpen"));
        const box = el("div", "voice");
        const audio = document.createElement("audio");
        audio.controls = true;
        audio.preload = "metadata";
        audio.src = view.url || (view.url = blobUrl(view.bytes, "audio/mp4"));
        box.append(audio);
        if (m.dur) box.append(el("span", "dur", Math.floor(m.dur / 60) + ":" + String(m.dur % 60).padStart(2, "0")));
        return box;
      }
      case "loc":
        return placeBox(view.text);
      case "poll":
        return pollBox(m, view);
      case "sticker": {
        const s = STICKERS[view.text];
        const box = el("div", "stk");
        box.append(el("div", "se", s ? s[0] : "🙂"), el("div", "st", s ? s[1] : t("sticker")));
        return box;
      }
      case "sos":
        return el("div", "sos", SOS_WORD);
      default:
        return el("div", "txt", view.text);
    }
  }

  function ticks(chat, m) {
    // my own messages only: one tick = sent, two = delivered, two coloured = read (the same reading of the chat document as the phone)
    const others = (chat.members || []).filter((x) => x !== uid);
    if (!others.length) return null;
    const low = (map) => Math.min(...others.map((o) => (map && typeof map[o] === "number" ? map[o] : 0)));
    const read = low(chat.read);
    const dlv = low(chat.dlv);
    if (read >= m.ts) return "r";
    if (dlv >= m.ts || read > 0) return "d";
    return "s";
  }

  function buildRow(chat, m, view, group, tick) {
    const sys = m.kind === "call" || m.kind === "sys";
    if (sys) {
      const row = el("div", "sysrow");
      const line = systemText(chat, m);
      if (line) row.append(el("span", "pill", line + "  " + clock(m.ts)));
      return row;
    }
    const mine = m.from === uid;
    const row = el("div", "msg " + (mine ? "out" : "in"));
    const bub = el("div", "bubble" + (m.kind === "sticker" ? " bare" : ""));
    if (group && !mine) {
      const nm = el("div", "who", nameIn(chat, m.from));
      nm.style.setProperty("--h", String(hue(m.from)));
      bub.append(nm);
    }
    if (m.fwd) bub.append(el("div", "fwd", "↪ " + t("fwd")));
    if (view.reply) {
      const q = el("div", "quote");
      q.append(el("b", "qn", m.replyFrom ? nameIn(chat, m.replyFrom) : ""), el("span", "qt", quoteText(view.reply).slice(0, 140)));
      bub.append(q);
    }
    bub.append(content(m, view));
    const meta = el("div", "meta");
    if (m.edited && !m.deleted) meta.append(el("span", "ed", t("edited")));
    meta.append(el("time", "", clock(m.ts)));
    if (mine && tick) meta.append(el("span", "tick " + tick, tick === "s" ? "✓" : "✓✓"));
    bub.append(meta);
    row.append(bub);
    const rs = Object.values(m.reactions || {}).filter((x) => typeof x === "string" && x);
    if (rs.length) {
      const counts = new Map();
      for (const r of rs) counts.set(r, (counts.get(r) || 0) + 1);
      const chips = el("div", "reacts");
      for (const [emoji, n] of counts) chips.append(el("span", "chip", n > 1 ? emoji + " " + n : emoji));
      row.append(chips);
    }
    return row;
  }

  // ---------------------------------------------------------------------------------------------- the messages of the open chat
  let renderChain = Promise.resolve();
  function renderMessages() {
    // one pass at a time: a newer snapshot waits for the older pass, then draws
    renderChain = renderChain.then(draw).catch(() => {});
    return renderChain;
  }

  async function draw() {
    const open = S.open;
    if (!open) return;
    const token = S.msgToken;
    const chat = open.raw;
    const chatId = open.id;
    const group = e2e.isGroup(chatId);
    const byId = new Map();
    for (const m of S.older) byId.set(m.id, m);
    for (const m of S.live) byId.set(m.id, m);
    let all = [...byId.values()].sort((a, b) => a.ts - b.ts || (a.id < b.id ? -1 : 1));
    if (group) all = withoutOlderThanMe(all);
    const chatK = group ? null : await e2e.chatKey(chatId).catch(() => null);
    if (token !== S.msgToken) return;

    const items = [];
    let lastDay = -1;
    for (const m of all) {
      const d = dayStart(m.ts);
      if (d !== lastDay) {
        lastDay = d;
        items.push({ key: "d" + d, sig: "d" + lang(), make: () => el("div", "dayrow", dayLabel(m.ts)) });
      }
      const sigBase = [m.text, m.reply, m.data ? m.data.length : 0, m.deleted, m.enc, JSON.stringify(m.votes), chatK ? 1 : 0].join("\u0001");
      let rec = S.opened.get(m.id);
      if (!rec || rec.sig !== sigBase) {
        rec = { sig: sigBase, view: await openOne(chatId, chatK, m) };
        if (token !== S.msgToken) return;
        S.opened.set(m.id, rec);
      }
      const tick = m.from === uid ? ticks(chat, m) : null;
      const sig = [sigBase, JSON.stringify(m.reactions), m.edited, tick, lang(), group ? nameIn(chat, m.from) : ""].join("\u0002");
      items.push({ key: "m" + m.id, sig, make: () => buildRow(chat, m, rec.view, group, tick) });
    }
    if (token !== S.msgToken) return;

    const box = $("msgs");
    const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 140;
    const firstDraw = S.rows.size === 0;
    const wanted = new Set(items.map((x) => x.key));
    for (const [k, r] of S.rows) {
      if (!wanted.has(k)) {
        r.el.remove();
        S.rows.delete(k);
      }
    }
    const holder = $("msgList");
    let prev = null;
    for (const it of items) {
      let r = S.rows.get(it.key);
      if (!r || r.sig !== it.sig) {
        const node = it.make();
        if (r) r.el.replaceWith(node); // keeps its place
        r = { el: node, sig: it.sig };
        S.rows.set(it.key, r);
      }
      // every row sits right after the one before it
      const want = prev ? prev.nextSibling : holder.firstChild;
      if (r.el !== want) holder.insertBefore(r.el, want);
      prev = r.el;
    }
    $("noMsgs").hidden = items.length > 0 || S.loadingMsgs;
    $("noMsgs").textContent = t("noMsgs");
    $("older").hidden = !S.more;
    $("msgLoading").hidden = !S.loadingMsgs;
    if (!S.hold && (firstDraw || nearBottom)) box.scrollTop = box.scrollHeight;
  }

  function showWarn() {
    const w = $("warn");
    const peer = S.open && !S.open.group ? S.open.peer : null;
    const on = Boolean(peer) && e2e.keyChanged(peer);
    w.hidden = !on;
    if (on) w.textContent = "⚠ " + tf("keyChanged", S.open.title);
  }

  function leaveChat() {
    S.msgToken++;
    if (S.msgStop) S.msgStop();
    S.msgStop = null;
    for (const u of S.urls) URL.revokeObjectURL(u);
    S.urls.clear();
    S.opened.clear();
    S.rows.clear();
    S.live = [];
    S.older = [];
    S.oldest = null;
    S.more = false;
    $("msgList").replaceChildren();
  }

  function openChat(chatId) {
    const c = S.chats.find((x) => x.id === chatId);
    if (!c) return;
    const v = chatVm(c);
    if (S.open && S.open.id === chatId) {
      $("app").classList.add("open");
      return;
    }
    leaveChat();
    S.open = v;
    S.loadingMsgs = true;
    $("app").classList.add("open");
    $("paneEmpty").hidden = true;
    $("chat").hidden = false;
    $("chName").textContent = v.title;
    $("chSub").textContent = v.saved ? "" : v.group ? tf("members", v.members.length) : v.handle ? "@" + v.handle : "";
    const head = $("chAv");
    head.replaceChildren(v.saved ? el("span", "av saved", "🔖") : avatar(v.avatarId, v.title, v.avatarV));
    S.listSig = "";
    renderList();
    showWarn();
    $("msgLoading").hidden = false;
    $("noMsgs").hidden = true;
    $("older").hidden = true;
    const token = S.msgToken;
    S.msgStop = data.watchMessages(
      chatId,
      PAGE,
      (list, oldest, more) => {
        if (token !== S.msgToken) return;
        S.live = list;
        if (!S.older.length) {
          S.oldest = oldest;
          S.more = more;
        }
        S.loadingMsgs = false;
        renderMessages();
      },
      () => {
        if (token !== S.msgToken) return;
        S.loadingMsgs = false;
        $("msgLoading").hidden = true;
        $("noMsgs").hidden = false;
        $("noMsgs").textContent = t("errMsgs");
      },
    );
    if (v.peer && !S.refreshed.has(v.peer)) {
      S.refreshed.add(v.peer);
      e2e
        .refreshPeer(v.peer)
        .catch(() => false)
        .then((changed) => {
          if (changed) {
            showWarn();
            S.opened.clear();
            renderMessages();
          }
        });
    }
  }

  async function loadOlder() {
    if (!S.open || !S.oldest) return;
    const token = S.msgToken;
    const btn = $("older");
    btn.disabled = true;
    try {
      const r = await data.olderMessages(S.open.id, S.oldest, PAGE);
      if (token !== S.msgToken) return;
      const box = $("msgs");
      const before = box.scrollHeight - box.scrollTop;
      S.older = [...r.list, ...S.older];
      S.oldest = r.oldest;
      S.more = r.more;
      S.hold = true;
      await renderMessages();
      box.scrollTop = box.scrollHeight - before;
    } catch (e) {
      // the button stays: the person can press it again
    } finally {
      S.hold = false;
      btn.disabled = false;
    }
  }

  function closeChat() {
    $("app").classList.remove("open");
    leaveChat();
    S.open = null;
    S.loadingMsgs = false;
    $("chat").hidden = true;
    $("paneEmpty").hidden = false;
    S.listSig = "";
    renderList();
  }

  // ---------------------------------------------------------------------------------------------- start
  function chips() {
    const box = $("chips");
    box.replaceChildren();
    for (const [key, word] of [["all", "fAll"], ["personal", "fPersonal"], ["groups", "fGroups"], ["archive", "fArchive"]]) {
      const b = el("button", "chip2", t(word));
      b.type = "button";
      b.setAttribute("aria-pressed", String(S.filter === key));
      b.addEventListener("click", () => {
        S.filter = key;
        chips();
        S.listSig = "";
        renderList();
      });
      box.append(b);
    }
  }

  function me() {
    const bar = $("meBtn");
    bar.replaceChildren(avatar(uid, user.name || user.handle, num(user.pv)), el("span", "who2", ""));
    const w = bar.lastChild;
    w.append(el("b", "", user.name || user.handle || "KHOSQ"), el("small", "", user.handle ? "@" + user.handle : ""));
  }

  $("q").value = "";
  $("q").addEventListener("input", () => {
    S.q = $("q").value;
    renderList();
  });
  $("meBtn").addEventListener("click", () => onAbout());
  $("back").addEventListener("click", closeChat);
  $("older").addEventListener("click", loadOlder);
  $("shot").addEventListener("click", () => $("shot").close());
  $("shot").addEventListener("close", () => $("shotImg").removeAttribute("src"));
  const onKey = (e) => {
    if (e.key === "Escape" && $("app").classList.contains("open") && !document.querySelector("dialog[open]")) closeChat();
  };
  document.addEventListener("keydown", onKey);

  // the server is asked for changes only while somebody looks: after two minutes in the background the connection rests
  let rest = 0;
  const onVis = () => {
    if (document.hidden) {
      rest = setTimeout(() => f.fsM.disableNetwork(f.db).catch(() => {}), 120000);
    } else {
      clearTimeout(rest);
      f.fsM.enableNetwork(f.db).catch(() => {});
    }
  };
  document.addEventListener("visibilitychange", onVis);

  me();
  chips();
  $("paneEmpty").hidden = false;
  $("chat").hidden = true;
  renderList();
  S.stops.push(
    data.watchPrefs((p) => {
      S.prefs = p;
      S.listSig = "";
      renderList();
    }),
  );
  S.stops.push(
    data.watchChats(
      (list) => {
        S.chats = list;
        S.loadedChats = true;
        S.failed = false;
        if (S.open) {
          const c = list.find((x) => x.id === S.open.id);
          if (c) {
            S.open = { ...chatVm(c) };
            $("chName").textContent = S.open.title;
            renderMessages();
          }
        }
        renderList();
      },
      () => {
        S.loadedChats = true;
        S.failed = true;
        S.listSig = "";
        renderList();
      },
    ),
  );

  return {
    /** The language changed: the words on the screen are written again. */
    relang() {
      chips();
      S.listSig = "";
      renderList();
      me();
      if (S.open) {
        const c = S.open;
        $("chSub").textContent = c.saved ? "" : c.group ? tf("members", c.members.length) : c.handle ? "@" + c.handle : "";
        $("chName").textContent = c.title;
        showWarn();
        S.rows.clear();
        $("msgList").replaceChildren();
        renderMessages();
      }
    },
    stop() {
      leaveChat();
      for (const s of S.stops) s();
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("visibilitychange", onVis);
      clearTimeout(rest);
      for (const p of photoUrls.values()) URL.revokeObjectURL(p.url);
    },
  };
}
