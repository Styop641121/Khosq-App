// KHOSQ Web, stages 2 to 6: the chat list, the messages, writing (send.js), and people (people.js: new chats, requests, blocking, my lists).
// Everything the page puts on the screen from a message or a name goes in with textContent (never as markup), so what somebody wrote can
// never act as part of the page.
import { t, tf, lang } from "./i18n.js";
import { makeData, PAGE } from "./data.js";
import { makeE2E } from "./e2e.js";
import { makeSender, whyNot, replyOf, REACTIONS, canEdit, canDelete, isActionable, lastReal } from "./send.js";
import { photoBytes, voiceMime, startVoice } from "./media.js";
import { makePeople, cleanHandle } from "./people.js";
import { similarNames } from "./lookalike.js";
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
  deleted: () => "🚫 " + t("deletedLine"),
  call_missed: () => "📵 " + t("call_missed"),
};

/**
 * What a quoted message shows. For a photo, a voice message, a location or a sticker the phone stores a marker, not text: the private-use
 * character U+E000 followed by the kind (REPLY_IMG ... in I18n.kt), so that each reader sees it in their own language. A reply that merely
 * says "img" is text and stays text.
 */
const MARK = "\uE000";
function quoteText(raw) {
  if (!raw.startsWith(MARK)) return raw;
  switch (raw.slice(1)) {
    case "img": return KIND_PREVIEW.img();
    case "voice": return KIND_PREVIEW.voice();
    case "loc": return KIND_PREVIEW.loc();
    case "sticker": return KIND_PREVIEW.sticker();
    default: return "";
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
export function startShell({ f, id, user, onAbout, worker }) {
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
  const sender = makeSender({ f, uid, name: user.name || user.handle || "", e2e, worker: worker || "" });
  const people = makePeople({ f, uid, user });
  const MAX_REFRESH = 30; // at most this many members of one group are looked at for a changed key when the group is opened

  const S = {
    chats: [],
    prefs: { pins: [], muted: [], archived: [], marked: [] },
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
    // writing
    reply: null, // {id, from, quote}: the message being answered; quote is what is stored for it
    drafts: new Map(), // what was typed in a chat and not sent (kept in memory only, never in the browser's storage)
    sending: false,
    block: "", // why the box is closed: blocked / pending / admins / key, or ""
    changed: [], // people of the open chat whose key changed and was not acknowledged
    stickUntil: 0, // after sending, the view follows the newest message until this moment
    refreshJob: null,
    readSig: "",
    // changing and deleting (stage 4)
    editing: null, // {id, original, draft, reply}: my message being changed; draft and reply are what the box held before
    menuFor: null, // the id of the message whose menu is open
    menuBtn: null,
    // a photo and a voice note (stage 5)
    media: null, // what waits to be sent: {kind: "img", bytes, url} | {kind: "rec", ctl} (recording now) | {kind: "voice", bytes, dur, wave}
    mediaBusy: false, // a picture is being shrunk, the microphone is being asked for, or a send is on its way
    mediaTimer: 0,
    // people (stage 6)
    wantOpen: "", // a chat that was just made: opened as soon as the list has it
    wantTimer: 0,
    ncToken: 0, // the "new chat" search that is current
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
    const blockedBy = c.blocked && typeof c.blocked === "object" ? Object.keys(c.blocked) : [];
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
      marked: S.prefs.marked.includes(c.id),
      request: c.status === "pending" && c.requester && c.requester !== uid,
      waiting: !group && c.status === "pending" && c.requester === uid,
      blocked: !group && blockedBy.length > 0,
      iBlocked: !group && blockedBy.includes(uid),
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
    if (kind === "deleted") return body;
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
    const sig = JSON.stringify([S.filter, S.q, S.open && S.open.id, S.loadedChats, S.failed, lang(), rows.map((r) => [r.v.id, r.v.title, r.v.lastTs, r.v.unread, r.v.pinned, r.v.muted, r.v.request, r.v.marked, r.v.waiting, r.v.blocked, r.v.avatarV, r.line])]);
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
      if (v.waiting) bot.append(el("span", "tag waiting", t("waitingTag")));
      if (v.blocked) bot.append(el("span", "tag blocked", t("blockedTag")));
      bot.append(el("span", "ln", line));
      if (v.unread) bot.append(el("span", "badge" + (v.muted ? " quiet" : ""), v.unread > 99 ? "99+" : String(v.unread)));
      else if (v.marked) bot.append(el("span", "dot" + (v.muted ? " quiet" : "")));
      mid.append(top, bot);
      b.append(mid);
      b.addEventListener("click", () => openChat(v.id));
      // the same menu as in the chat's header: with the mouse a button shows when the pointer is over the row, and a right press opens it too
      const more = el("button", "rowmore", "⋯");
      more.type = "button";
      more.setAttribute("aria-label", t("chatMenu"));
      more.setAttribute("aria-haspopup", "menu");
      more.setAttribute("aria-expanded", "false");
      more.addEventListener("click", () => openChatMenu(v.id, more));
      b.addEventListener("contextmenu", (e) => {
        e.preventDefault();
        openChatMenu(v.id, more.offsetParent ? more : b);
      });
      li.append(b, more);
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
    // my own messages only: a clock = still being sent, one tick = sent, two = delivered, two coloured = read (the phone's reading of the chat document)
    if (m.pending && sender.sending.has(m.id)) return "p";
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
    if (mine && tick) {
      const tk = el("span", "tick " + tick, tick === "s" ? "✓" : tick === "p" ? "🕓" : "✓✓");
      if (tick === "p") tk.title = t("sending");
      meta.append(tk);
    }
    bub.append(meta);
    const live = isActionable(m) && !sender.sending.has(m.id);
    if (live) {
      const mb = el("button", "mbtn", "⋯");
      mb.type = "button";
      mb.title = t("moreActions");
      mb.setAttribute("aria-label", t("moreActions"));
      mb.setAttribute("aria-haspopup", "menu");
      mb.setAttribute("aria-expanded", "false");
      mb.addEventListener("click", (e) => {
        e.stopPropagation();
        if (S.menuFor === m.id) closeMenu();
        else openMenu(m.id, mb);
      });
      bub.append(mb);
    }
    if (view && !m.deleted && (view.ok || view.text || view.bytes)) {
      const rb = el("button", "rbtn", "↩");
      rb.type = "button";
      rb.title = t("reply");
      rb.setAttribute("aria-label", t("reply"));
      rb.addEventListener("click", () => setReply(m, view));
      bub.append(rb);
    }
    row.append(bub);
    const rs = Object.values(m.reactions || {}).filter((x) => typeof x === "string" && x);
    if (rs.length) {
      const counts = new Map();
      for (const r of rs) counts.set(r, (counts.get(r) || 0) + 1);
      const chips = el("div", "reacts");
      const mine = m.reactions ? m.reactions[uid] : null;
      for (const [emoji, n] of counts) {
        const label = n > 1 ? emoji + " " + n : emoji;
        if (!live) {
          chips.append(el("span", "chip", label));
          continue;
        }
        // a chip is also a button: pressing it puts my reaction on, or takes it off (the same as in the menu)
        const c = el("button", "chip" + (mine === emoji ? " mine" : ""), label);
        c.type = "button";
        c.setAttribute("aria-pressed", String(mine === emoji));
        c.setAttribute("aria-label", tf(mine === emoji ? "reactTake" : "reactWith", emoji));
        c.addEventListener("click", () => doReact(m.id, emoji));
        chips.append(c);
      }
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
      const live = isActionable(m) && !sender.sending.has(m.id);
      const sig = [sigBase, JSON.stringify(m.reactions), m.edited, tick, live, lang(), group ? nameIn(chat, m.from) : ""].join("\u0002");
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
    if (!S.hold && (firstDraw || nearBottom || Date.now() < S.stickUntil)) box.scrollTop = box.scrollHeight;
  }

  /** Looks for people of the open chat whose key changed and was not acknowledged: shows the warning and, in a one-to-one chat, closes the box. */
  async function showWarn() {
    const v = S.open;
    if (!v) return;
    const peers = v.saved ? [] : v.group ? v.members.filter((m) => m !== uid).slice(0, MAX_REFRESH) : v.peer ? [v.peer] : [];
    const changed = [];
    for (const p of peers) if (await e2e.keyChanged(p)) changed.push(p);
    if (!S.open || S.open.id !== v.id) return;
    S.changed = changed;
    $("warn").hidden = changed.length === 0;
    if (changed.length) {
      $("warnText").textContent = "⚠ " + (v.group ? tf("keyChangedIn", changed.map((p) => nameOf(v.raw, p)).join(", ")) : tf("keyChanged", v.title));
    }
    updateComposer();
  }

  /** Looks at the server's copy of the keys of the open chat once per visit (a changed one is noted, the new one used for what is sealed from now on). */
  function refreshKeys(v) {
    const people = (v.saved ? [] : v.group ? v.members : v.peer ? [v.peer] : []).filter((p) => p !== uid).slice(0, MAX_REFRESH);
    return (async () => {
      let any = false;
      for (const p of people) {
        if (S.refreshed.has(p)) continue;
        S.refreshed.add(p);
        if (await e2e.refreshPeer(p).catch(() => false)) any = true;
      }
      if (S.open && S.open.id === v.id) {
        if (any) {
          S.opened.clear();
          renderMessages();
        }
        showWarn();
      }
    })().catch(() => {});
  }

  function leaveChat() {
    closeMenu();
    if (S.open) {
      const text = S.editing ? S.editing.draft : $("msgIn").value; // what is being changed is not a draft
      S.editing = null;
      if (text) S.drafts.set(S.open.id, text);
      else S.drafts.delete(S.open.id);
    }
    S.reply = null;
    dropMedia();
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
    if (v.marked) people.setPref("marked", chatId, false).committed.catch(() => {});
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
    S.changed = [];
    $("warn").hidden = true;
    $("msgIn").value = S.drafts.get(chatId) || "";
    autosize();
    updateComposer();
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
    S.refreshJob = refreshKeys(v);
    showWarn();
    maybeRead();
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

  // ---------------------------------------------------------------------------------------------- writing
  let toastTimer = 0;
  function toast(text) {
    const n = $("toast");
    n.textContent = text;
    n.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (n.hidden = true), 6000);
  }

  const coarse = () => Boolean(window.matchMedia && window.matchMedia("(pointer: coarse)").matches);

  function autosize() {
    const ta = $("msgIn");
    ta.style.height = "auto";
    // a box that is closed (display: none) measures 0: leave it to the stylesheet; it is measured again when it opens (updateComposer)
    if (ta.offsetParent !== null) ta.style.height = Math.min(ta.scrollHeight, 160) + "px";
  }

  const syncSend = () => {
    const text = $("msgIn").value.replace(/\r\n?/g, "\n").trim();
    $("sendBtn").disabled = !S.open || S.sending || Boolean(S.block) || !text || Boolean(S.editing && text === S.editing.original);
  };

  function renderReply() {
    const r = S.reply;
    $("replyBar").hidden = !r || Boolean(S.block);
    if (r && S.open) {
      $("replyName").textContent = nameIn(S.open.raw, r.from);
      $("replyText").textContent = quoteText(r.quote).slice(0, 140);
    }
  }

  /** Opens or closes the box for the open chat: the conditions of the Firestore rules, and an unchecked key change in a one-to-one chat. */
  function updateComposer() {
    const v = S.open;
    if (!v) return;
    let why = whyNot(v.raw, uid);
    if (!why && !v.group && S.changed.length) why = "key";
    S.block = why;
    if (why && S.editing) cancelEdit();
    if (why && S.media) dropMedia();
    $("chat").classList.toggle("nowrite", Boolean(why));
    // somebody's request to me, or a person I blocked: a bar with the buttons (instead of the line that only says why)
    const mode = v.group ? "" : v.iBlocked && !v.saved ? "unblock" : v.request && !v.blocked ? "request" : "";
    $("notice").hidden = !mode;
    if (mode === "unblock") {
      $("noticeText").textContent = t("youBlocked");
      $("noticeNote").hidden = true;
      $("noticeNo").hidden = true;
      $("noticeYes").textContent = t("unblock");
    } else if (mode === "request") {
      $("noticeText").textContent = tf("reqText", v.title);
      $("noticeNote").textContent = requestNotes(v);
      $("noticeNote").hidden = false;
      $("noticeNo").hidden = false;
      $("noticeNo").textContent = t("block");
      $("noticeYes").textContent = t("accept");
    }
    const waitNote = $("waitNote");
    waitNote.hidden = !(v.waiting && !v.blocked);
    if (!waitNote.hidden) waitNote.textContent = tf("waitNote", v.title);
    const cant = $("cant");
    cant.hidden = !why || Boolean(mode);
    if (why) {
      cant.textContent = why === "blocked" ? t("cantBlocked") : why === "pending" ? tf("cantPending", v.title) : why === "admins" ? t("cantAdmins") : t("cantKey");
    }
    renderReply();
    syncSend();
    updateEditUi();
    renderMedia();
    if (!why) autosize(); // the box has just opened: measure it now that it can be measured
  }

  function setReply(m, view) {
    if (!S.open || S.block) return;
    if (S.editing) cancelEdit();
    S.reply = { id: m.id, from: m.from, quote: replyOf(m, view) };
    renderReply();
    $("msgIn").focus();
  }

  // ---------------------------------------------------------------------------------------------- actions on a message (stage 4)
  /** Every message of the open chat that is loaded, oldest first. */
  function allMessages() {
    const byId = new Map();
    for (const m of S.older) byId.set(m.id, m);
    for (const m of S.live) byId.set(m.id, m);
    return [...byId.values()].sort((a, b) => a.ts - b.ts || (a.id < b.id ? -1 : 1));
  }
  const findMsg = (id) => allMessages().find((m) => m.id === id) || null;
  const isNewest = (id) => {
    const last = lastReal(allMessages());
    return Boolean(last) && last.id === id;
  };

  function closeMenu(giveBackFocus) {
    const menu = $("menu");
    const btn = S.menuBtn;
    S.menuFor = null;
    S.menuBtn = null;
    if (!menu.hidden) {
      menu.hidden = true;
      menu.replaceChildren();
    }
    if (btn) {
      btn.setAttribute("aria-expanded", "false");
      if (giveBackFocus && document.contains(btn)) btn.focus();
    }
  }

  function placeMenu(btn) {
    const menu = $("menu");
    menu.hidden = false;
    menu.style.left = "0px";
    menu.style.top = "0px";
    const r = btn.getBoundingClientRect();
    const w = menu.offsetWidth;
    const h = menu.offsetHeight;
    const left = Math.min(Math.max(8, r.left), Math.max(8, window.innerWidth - w - 8));
    let top = r.bottom + 6;
    if (top + h > window.innerHeight - 8) top = Math.max(8, r.top - h - 6);
    menu.style.left = left + "px";
    menu.style.top = top + "px";
  }

  /** The menu of one message: six reactions, and what I may do with it (answer, change, delete). */
  function openMenu(id, btn) {
    const m = findMsg(id);
    if (!m || !S.open || !isActionable(m) || sender.sending.has(m.id)) return;
    const rec = S.opened.get(id);
    const view = rec ? rec.view : { ok: false, text: "", bytes: null };
    closeMenu();
    S.menuFor = id;
    S.menuBtn = btn;
    btn.setAttribute("aria-expanded", "true");
    const menu = $("menu");
    menu.setAttribute("role", "menu");
    menu.removeAttribute("aria-label");
    menu.replaceChildren();
    const mine = m.reactions ? m.reactions[uid] : null;
    const emo = el("div", "emo");
    emo.setAttribute("role", "group");
    emo.setAttribute("aria-label", t("reactions"));
    for (const e of REACTIONS) {
      const b = el("button", "emo-b" + (mine === e ? " on" : ""), e);
      b.type = "button";
      b.setAttribute("role", "menuitemradio");
      b.setAttribute("aria-checked", String(mine === e));
      b.setAttribute("aria-label", tf(mine === e ? "reactTake" : "reactWith", e));
      b.addEventListener("click", () => {
        closeMenu();
        doReact(id, e);
      });
      emo.append(b);
    }
    menu.append(emo);
    const item = (label, fn, danger) => {
      const b = el("button", "item" + (danger ? " danger" : ""), label);
      b.type = "button";
      b.setAttribute("role", "menuitem");
      b.addEventListener("click", fn);
      menu.append(b);
    };
    if (!S.block && (view.ok || view.text || view.bytes)) {
      item(t("reply"), () => {
        closeMenu();
        setReply(m, view);
      });
    }
    if (!S.block && view.ok && view.text && canEdit(m, uid, Date.now())) {
      item(t("edit"), () => {
        closeMenu();
        startEdit(id);
      });
    }
    if (canDelete(m, uid)) item(t("deleteAll"), () => askDelete(id), true);
    placeMenu(btn);
    menu.querySelector("button").focus({ preventScroll: true });
  }

  /** "Delete for everyone" cannot be undone: the menu asks once more. */
  function askDelete(id) {
    const menu = $("menu");
    menu.setAttribute("role", "alertdialog");
    menu.setAttribute("aria-label", t("deleteAll"));
    menu.replaceChildren();
    menu.append(el("p", "ask", t("deleteAsk")));
    const row = el("div", "btns");
    const no = el("button", "btn ghost", t("cancel"));
    no.type = "button";
    no.addEventListener("click", () => closeMenu(true));
    const yes = el("button", "btn danger", t("deleteDo"));
    yes.type = "button";
    yes.addEventListener("click", () => {
      closeMenu();
      doDelete(id);
    });
    row.append(no, yes);
    menu.append(row);
    if (S.menuBtn) placeMenu(S.menuBtn);
    no.focus({ preventScroll: true });
  }

  function doReact(id, emoji) {
    const v = S.open;
    const m = findMsg(id);
    if (!v || !m) return;
    const r = sender.react(v.raw, m, emoji);
    if (r.ok) r.committed.catch(() => toast(t("actionFailed")));
  }

  function doDelete(id) {
    const v = S.open;
    const m = findMsg(id);
    if (!v || !m) return;
    if (S.editing && S.editing.id === id) cancelEdit();
    const r = sender.remove(v.raw, m, isNewest(id));
    if (r.ok) r.committed.catch(() => toast(t("actionFailed")));
  }

  /** The bar above the box while I change one of my messages, and the arrow that turns into a tick ("Save"). */
  function updateEditUi() {
    const e = S.editing;
    $("editBar").hidden = !e || Boolean(S.block);
    if (e) $("editText").textContent = e.original.replace(/\s+/g, " ").slice(0, 140);
    const b = $("sendBtn");
    b.classList.toggle("save", Boolean(e));
    b.setAttribute("aria-label", t(e ? "save" : "send"));
  }

  /** The text of my message goes into the box; what the box held (and the answer being written) comes back when this ends. */
  function startEdit(id) {
    const v = S.open;
    const m = findMsg(id);
    const rec = S.opened.get(id);
    if (!v || !m || S.block) return;
    const text = rec && rec.view && rec.view.ok ? rec.view.text : "";
    if (!text) return;
    if (!canEdit(m, uid, Date.now())) {
      toast(t("editLate"));
      return;
    }
    if (S.editing) cancelEdit();
    if (S.media) dropMedia(); // a change and a photo are not prepared at the same time
    const ta = $("msgIn");
    S.editing = { id, original: text, draft: ta.value, reply: S.reply };
    S.reply = null;
    renderReply();
    renderMedia();
    ta.value = text;
    autosize();
    updateEditUi();
    syncSend();
    ta.focus();
    ta.setSelectionRange(ta.value.length, ta.value.length);
  }

  function cancelEdit() {
    const e = S.editing;
    if (!e) return;
    S.editing = null;
    const ta = $("msgIn");
    ta.value = e.draft;
    S.reply = e.reply;
    autosize();
    renderReply();
    updateEditUi();
    syncSend();
    renderMedia();
  }

  async function saveEdit() {
    const v = S.open;
    const ed = S.editing;
    if (!v || !ed || S.sending || S.block) return;
    const text = $("msgIn").value;
    const body = text.replace(/\r\n?/g, "\n").trim();
    if (!body) return;
    if (body === ed.original) {
      cancelEdit(); // nothing changed: nothing is written
      return;
    }
    S.sending = true;
    syncSend();
    const chatId = v.id;
    let r;
    try {
      if (S.refreshJob) await Promise.race([S.refreshJob, new Promise((res) => setTimeout(res, 4000))]);
      await showWarn(); // an unchecked key change closes the box, and with it the change
      if (S.block || !S.open || S.open.id !== chatId || S.editing !== ed) {
        S.sending = false;
        syncSend();
        return;
      }
      const m = findMsg(ed.id);
      if (!m || m.deleted) {
        S.sending = false;
        cancelEdit();
        toast(t("deleted"));
        return;
      }
      r = await sender.edit(v.raw, { ...m, text: ed.original }, text, isNewest(m.id));
    } catch (e) {
      r = { ok: false, why: "error" };
    }
    S.sending = false;
    if (!r.ok) {
      if (r.why === "same") cancelEdit();
      else if (r.why === "late") toast(t("editLate"));
      else if (r.why === "noKey") toast(tf("cantEncrypt", v.title));
      else toast(t("actionFailed"));
      syncSend();
      return;
    }
    if (S.editing === ed) {
      S.editing = null;
      const ta = $("msgIn");
      ta.value = ed.draft;
      S.reply = ed.reply;
      autosize();
      renderReply();
      updateEditUi();
    }
    syncSend();
    r.committed.catch(() => toast(t("actionFailed")));
  }

  // ---------------------------------------------------------------------------------------------- a photo and a voice note (stage 5)
  const VOICE_MIME = voiceMime(); // "" when this browser cannot record what the phone plays
  const sizeText = (n) => Math.max(1, Math.round(n / 1024)) + " KB";
  const mmss = (s) => Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");

  /** The two buttons and the bar above the box: what waits to be sent (a photo, a recording, a voice note). */
  function renderMedia() {
    const m = S.media;
    $("mediaBar").hidden = !m || Boolean(S.block);
    const idle = Boolean(S.open) && !S.block && !S.editing && !m && !S.mediaBusy;
    $("photoBtn").disabled = !idle;
    $("micBtn").disabled = !idle;
    if (!m) return;
    const thumb = $("mediaThumb");
    thumb.hidden = m.kind !== "img";
    if (m.kind === "img" && thumb.getAttribute("src") !== m.url) thumb.src = m.url;
    $("mediaDot").hidden = m.kind !== "rec";
    if (m.kind === "img") {
      $("mediaTitle").textContent = t("photo");
      $("mediaInfo").textContent = sizeText(m.bytes.length);
    } else if (m.kind === "rec") {
      $("mediaTitle").textContent = t("recording");
      $("mediaInfo").textContent = mmss(m.ctl.elapsed()) + (m.ctl.limited ? " · " + t("voiceFull") : "");
    } else {
      $("mediaTitle").textContent = t("voice");
      $("mediaInfo").textContent = mmss(m.dur);
    }
    $("mediaSend").disabled = S.mediaBusy;
  }

  /** Throws away what waits: a recording is stopped and the microphone is let go. */
  function dropMedia() {
    const m = S.media;
    if (!m) return;
    S.media = null;
    clearInterval(S.mediaTimer);
    S.mediaTimer = 0;
    if (m.kind === "rec") m.ctl.cancel();
    if (m.url) {
      URL.revokeObjectURL(m.url);
      S.urls.delete(m.url);
    }
    $("mediaThumb").removeAttribute("src");
    renderMedia();
  }

  /** A picture file (chosen, or pasted): shrunk like the phone does it, then shown above the box until "Send". */
  async function pickPhoto(file) {
    const v = S.open;
    if (!v || !file || S.block || S.editing || S.media || S.mediaBusy) return;
    const chatId = v.id;
    S.mediaBusy = true;
    renderMedia();
    let r;
    try {
      r = await photoBytes(file);
    } catch (e) {
      S.mediaBusy = false;
      renderMedia();
      toast(t(e && e.message === "big" ? "photoBig" : "photoBad"));
      return;
    }
    S.mediaBusy = false;
    if (!S.open || S.open.id !== chatId || S.block || S.editing || S.media) {
      renderMedia();
      return; // the person went elsewhere meanwhile
    }
    S.media = { kind: "img", bytes: r.bytes, url: blobUrl(r.bytes, "image/jpeg") };
    renderMedia();
    $("mediaSend").focus();
  }

  /** The microphone button: asks for the microphone and starts recording. */
  async function startRec() {
    const v = S.open;
    if (!v || S.block || S.editing || S.media || S.mediaBusy) return;
    if (!VOICE_MIME) {
      toast(t("noVoiceHere"));
      return;
    }
    const chatId = v.id;
    S.mediaBusy = true;
    renderMedia();
    let ctl;
    try {
      ctl = await startVoice({ mime: VOICE_MIME, onEnd: renderMedia });
    } catch (e) {
      S.mediaBusy = false;
      renderMedia();
      const n = e && e.name;
      toast(t(n === "NotAllowedError" || n === "SecurityError" ? "micDenied" : n === "NotFoundError" || n === "OverconstrainedError" ? "noMic" : "micFailed"));
      return;
    }
    S.mediaBusy = false;
    if (!S.open || S.open.id !== chatId || S.block || S.editing || S.media) {
      ctl.cancel(); // the person went elsewhere while the microphone was being asked for
      renderMedia();
      return;
    }
    S.media = { kind: "rec", ctl };
    S.mediaTimer = setInterval(renderMedia, 250);
    renderMedia();
    $("mediaSend").focus();
  }

  /** "Send" on the bar: a photo, or the end of a recording and its sending. What was recorded stays on the bar until the server has it. */
  async function sendMediaNow() {
    const v = S.open;
    let m = S.media;
    if (!v || !m || S.mediaBusy || S.block) return;
    const chatId = v.id;
    S.mediaBusy = true;
    renderMedia();
    let r;
    try {
      // the keys of the people in this chat are looked at first, as before a text
      if (S.refreshJob) await Promise.race([S.refreshJob, new Promise((res) => setTimeout(res, 4000))]);
      await showWarn();
      const here = () => S.media === m && !S.block && S.open && S.open.id === chatId;
      if (!here()) {
        S.mediaBusy = false;
        renderMedia();
        return;
      }
      if (m.kind === "rec") {
        clearInterval(S.mediaTimer);
        S.mediaTimer = 0;
        const rec = await m.ctl.stop();
        if (!here()) {
          S.mediaBusy = false;
          renderMedia();
          return;
        }
        if (!rec || rec.sec < 1) {
          S.media = null;
          S.mediaBusy = false;
          renderMedia();
          toast(t("voiceShort"));
          return;
        }
        m = S.media = { kind: "voice", bytes: rec.bytes, dur: rec.sec, wave: rec.wave };
      }
      S.stickUntil = Date.now() + 4000;
      r = await sender.sendMedia(v.raw, m.kind, m.bytes, m.kind === "voice" ? m.dur : 0, m.kind === "voice" ? m.wave : "");
    } catch (e) {
      r = { ok: false, why: "error" };
    }
    S.mediaBusy = false;
    if (!r.ok) {
      S.stickUntil = 0;
      if (r.why === "noKey") toast(v.group ? t("cantEncryptGroup") : tf("cantEncrypt", v.title));
      else toast(t(r.why === "big" ? (m.kind === "img" ? "photoBig" : "voiceBig") : "notSent"));
      renderMedia();
      return;
    }
    // taken: it is on the screen as "sending" and the server settles it in a moment
    if (S.media === m) dropMedia();
    renderMedia();
    const again = () => renderMessages();
    r.taken.then(again, again);
    r.committed.catch(() => toast(t("notSent")));
  }

  async function sendNow() {
    if (S.editing) return saveEdit();
    const v = S.open;
    const ta = $("msgIn");
    if (!v || S.sending || S.block || !ta.value.trim()) return;
    S.sending = true;
    S.stickUntil = Date.now() + 4000;
    syncSend();
    const chatId = v.id;
    const text = ta.value;
    const reply = S.reply ? { from: S.reply.from, quote: S.reply.quote } : null;
    let r;
    try {
      // the keys of the people in this chat are looked at first (a few seconds at most), so nothing is sealed for an old key
      if (S.refreshJob) await Promise.race([S.refreshJob, new Promise((res) => setTimeout(res, 4000))]);
      await showWarn(); // an unchecked key change closes the box, even if it was noticed only a moment ago
      if (S.block || !S.open || S.open.id !== chatId) {
        S.sending = false;
        S.stickUntil = 0;
        syncSend();
        return;
      }
      r = await sender.sendText(v.raw, text, reply);
    } catch (e) {
      r = { ok: false, why: "error" };
    }
    S.sending = false;
    const here = S.open && S.open.id === chatId;
    if (!r.ok) {
      S.stickUntil = 0;
      if (r.why === "noKey") toast(v.group ? t("cantEncryptGroup") : tf("cantEncrypt", v.title));
      else if (r.why !== "empty") toast(t("notSent"));
      syncSend();
      return;
    }
    // taken: it is on the screen as "sending" and the server settles it in a moment
    if (here) {
      ta.value = "";
      S.reply = null;
      autosize();
      renderReply();
    } else {
      S.drafts.delete(chatId);
    }
    syncSend();
    const again = () => renderMessages(); // the message is no longer "sending": its buttons come
    r.taken.then(again, again);
    r.committed.catch(() => {
      toast(t("notSent"));
      if (S.open && S.open.id === chatId) {
        if (!ta.value) {
          ta.value = text;
          autosize();
        }
        syncSend();
      } else {
        S.drafts.set(chatId, text);
      }
    });
  }

  /** The tab is in front and in use (not hidden, has the focus): only then does the page say "read" or "delivered". */
  const seen = () => document.visibilityState === "visible" && (typeof document.hasFocus !== "function" || document.hasFocus());

  function maybeRead() {
    const v = S.open;
    if (!v || v.request || !seen() || !(v.unread > 0)) return;
    const sig = v.id + "|" + v.lastTs + "|" + v.unread;
    if (S.readSig === sig) return;
    S.readSig = sig;
    sender.markRead(v.raw);
  }

  function maybeDeliver(list) {
    if (!seen()) return;
    for (const c of list) sender.markDelivered(c);
  }

  // ---------------------------------------------------------------------------------------------- people and my lists (stage 6)
  /** What is said under a request: a name that copies one of my contacts' names (the phone's warning), and that this is a stranger. */
  function requestNotes(v) {
    const known = [];
    for (const c of S.chats) {
      if (c.id === v.id || c.group === true || (c.status || "active") !== "active") continue;
      const who = (Array.isArray(c.members) ? c.members : []).find((m) => m !== uid);
      if (who) known.push([who, nameOf(c, who)]);
    }
    const near = similarNames(v.title, known)[0];
    const out = [];
    if (near) out.push(tf(near.same ? "lookSame" : "lookNear", near.name));
    out.push(t("strangerNote"));
    return out.join("\n\n");
  }

  const mustTell = (r) => {
    if (r.ok) r.committed.catch(() => toast(t("actionFailed")));
    else toast(t("actionFailed"));
  };

  function togglePref(field, id) {
    mustTell(people.setPref(field, id, !S.prefs[field].includes(id)));
  }

  function doAccept(id) {
    const c = S.chats.find((x) => x.id === id);
    if (c) mustTell(people.accept(c));
  }

  function doBlock(id, on) {
    const c = S.chats.find((x) => x.id === id);
    if (c) mustTell(people.setBlocked(c, on));
  }

  /** "Mark as read" from the list: my counter goes to 0 and the other side's ticks turn, as when the chat is opened (the unread mark goes too). */
  function doMarkRead(id) {
    const c = S.chats.find((x) => x.id === id);
    if (!c) return;
    sender.markRead(c);
    if (S.prefs.marked.includes(id)) togglePref("marked", id);
  }

  /** The menu of one chat (from the list or from its header): pin, mute, mark read / unread, archive, block. */
  function openChatMenu(id, btn) {
    const c = S.chats.find((x) => x.id === id);
    if (!c) return;
    const v = chatVm(c);
    closeMenu();
    S.menuFor = "chat:" + id;
    S.menuBtn = btn;
    btn.setAttribute("aria-expanded", "true");
    const menu = $("menu");
    menu.setAttribute("role", "menu");
    menu.setAttribute("aria-label", t("chatMenu"));
    menu.replaceChildren();
    const item = (label, fn, danger) => {
      const b = el("button", "item" + (danger ? " danger" : ""), label);
      b.type = "button";
      b.setAttribute("role", "menuitem");
      b.addEventListener("click", () => {
        closeMenu();
        fn();
      });
      menu.append(b);
    };
    item(t(v.pinned ? "unpin" : "pin"), () => togglePref("pins", id));
    if (!v.saved) item(t(v.muted ? "unmute" : "mute"), () => togglePref("muted", id));
    if (v.unread > 0) item(t("markRead"), () => doMarkRead(id));
    else item(t(v.marked ? "unmark" : "markUnread"), () => togglePref("marked", id));
    item(t(v.archived ? "unarchive" : "archive"), () => togglePref("archived", id));
    if (!v.group && !v.saved) item(t(v.iBlocked ? "unblock" : "block"), () => doBlock(id, !v.iBlocked), !v.iBlocked);
    placeMenu(btn);
    menu.querySelector("button").focus({ preventScroll: true });
  }

  /**
   * Makes the chat visible in the list (a chat in the archive, or a group while "Personal" is chosen) and opens it. With a keyboard the cursor
   * goes into the message box when it is open (the window of "new chat" gave the focus back to the "+" button when it closed).
   */
  function showChat(id) {
    const c = S.chats.find((x) => x.id === id);
    if (!c) return;
    if (!inFilter(chatVm(c))) {
      S.filter = S.prefs.archived.includes(id) ? "archive" : "all";
      chips();
    }
    openChat(id);
    // a chat with no message yet sits at the bottom of a long list: bring its row into view
    const row = $("list").querySelector(".item.on");
    if (row) row.scrollIntoView({ block: "nearest" });
    if (!coarse() && !$("chat").classList.contains("nowrite")) $("msgIn").focus({ preventScroll: true });
  }

  /** A chat that was just made reaches the list by itself a moment later: it is opened then. */
  function openWhenListed(id) {
    if (S.chats.some((x) => x.id === id)) return showChat(id);
    S.wantOpen = id;
    clearTimeout(S.wantTimer);
    S.wantTimer = setTimeout(() => (S.wantOpen = ""), 8000);
  }

  function ncFail(why) {
    S.ncBusy = false;
    $("ncGo").disabled = false;
    $("ncGo").textContent = t("ncGo");
    $("ncErr").textContent = t(why === "empty" ? "ncEmpty" : why === "notFound" ? "ncNotFound" : why === "self" ? "ncSelf" : "ncError");
    $("ncErr").hidden = false;
    $("ncId").focus();
  }

  function openNewChat() {
    S.ncToken++;
    S.ncBusy = false;
    closeMenu();
    $("ncId").value = "";
    $("ncErr").hidden = true;
    $("ncMine").textContent = user.handle ? tf("ncMine", user.handle) : "";
    $("ncGo").disabled = false;
    $("ncGo").textContent = t("ncGo");
    if (!$("newChat").open) $("newChat").showModal();
    $("ncId").focus();
  }

  async function goNewChat() {
    if (S.ncBusy) return;
    if (!cleanHandle($("ncId").value)) return ncFail("empty");
    const token = ++S.ncToken;
    S.ncBusy = true;
    $("ncErr").hidden = true;
    $("ncGo").disabled = true;
    $("ncGo").textContent = t("ncSearching");
    const found = await people.find($("ncId").value);
    if (token !== S.ncToken) return; // the window was closed meanwhile
    if (!found.ok) return ncFail(found.why);
    const made = await people.start(found.peer);
    if (token !== S.ncToken) return;
    if (!made.ok) return ncFail(made.why);
    S.ncBusy = false;
    $("newChat").close();
    openWhenListed(made.id);
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
  $("newBtn").addEventListener("click", openNewChat);
  $("ncGo").addEventListener("click", goNewChat);
  // a search still on its way is forgotten the moment the window is left (a dialog's "close" event comes a little later than Esc or the button)
  const ncForget = () => {
    S.ncToken++;
    S.ncBusy = false;
  };
  $("ncNo").addEventListener("click", () => {
    ncForget();
    $("newChat").close();
  });
  $("newChat").addEventListener("cancel", ncForget);
  $("newChat").addEventListener("close", ncForget);
  $("ncId").addEventListener("input", () => {
    const clean = cleanHandle($("ncId").value);
    if (clean !== $("ncId").value) $("ncId").value = clean; // what the phone lets in: a-z, 0-9, _ and -
    $("ncErr").hidden = true;
  });
  $("ncId").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.isComposing) {
      e.preventDefault();
      goNewChat();
    }
  });
  $("chatMore").addEventListener("click", () => {
    if (S.open) openChatMenu(S.open.id, $("chatMore"));
  });
  $("noticeYes").addEventListener("click", () => {
    const v = S.open;
    if (!v) return;
    if (v.iBlocked) doBlock(v.id, false);
    else if (v.request) doAccept(v.id);
  });
  $("noticeNo").addEventListener("click", () => {
    const v = S.open;
    if (v && v.request && !v.iBlocked) doBlock(v.id, true);
  });
  $("back").addEventListener("click", closeChat);
  $("older").addEventListener("click", loadOlder);
  $("sendBtn").addEventListener("click", sendNow);
  $("photoBtn").addEventListener("click", () => {
    if (!$("photoBtn").disabled) $("photoIn").click();
  });
  $("photoIn").addEventListener("change", () => {
    const file = $("photoIn").files && $("photoIn").files[0];
    $("photoIn").value = ""; // the same file can be chosen again
    if (file) pickPhoto(file);
  });
  $("micBtn").addEventListener("click", startRec);
  $("mediaSend").addEventListener("click", sendMediaNow);
  $("mediaX").addEventListener("click", () => {
    dropMedia();
    $("msgIn").focus();
  });
  // a picture pasted into the box (a screenshot, a copied photo) waits above the box like a chosen one; pasted text is left alone
  $("msgIn").addEventListener("paste", (e) => {
    const files = e.clipboardData && e.clipboardData.files ? [...e.clipboardData.files] : [];
    const pic = files.find((x) => /^image\//.test(x.type));
    if (pic && !$("photoBtn").disabled) {
      e.preventDefault();
      pickPhoto(pic);
    }
  });
  $("msgIn").addEventListener("input", () => {
    autosize();
    syncSend();
  });
  $("msgIn").addEventListener("keydown", (e) => {
    if (e.key === "Escape" && S.editing) {
      e.preventDefault();
      e.stopPropagation(); // Esc ends the change, it does not close the chat
      cancelEdit();
      return;
    }
    // Enter sends and Shift+Enter makes a new line (on a touch screen Enter is a new line and the button sends); Ctrl/Cmd+Enter always sends
    if (e.key === "Enter" && !e.isComposing && ((!e.shiftKey && !coarse()) || e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      sendNow();
    }
  });
  $("replyX").addEventListener("click", () => {
    S.reply = null;
    renderReply();
  });
  $("editX").addEventListener("click", () => {
    cancelEdit();
    $("msgIn").focus();
  });
  // the message menu: arrows move, Esc closes (and gives the focus back), a press outside, a scroll or a resize closes it
  $("menu").addEventListener("keydown", (e) => {
    const items = [...$("menu").querySelectorAll("button")];
    const i = items.indexOf(document.activeElement);
    const go = (n) => {
      e.preventDefault();
      if (items.length) items[(n + items.length) % items.length].focus();
    };
    if (e.key === "ArrowDown" || e.key === "ArrowRight") go(i + 1);
    else if (e.key === "ArrowUp" || e.key === "ArrowLeft") go(i - 1);
    else if (e.key === "Home") go(0);
    else if (e.key === "End") go(items.length - 1);
    else if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      closeMenu(true);
    } else if (e.key === "Tab") closeMenu();
  });
  const onPointer = (e) => {
    if (S.menuFor && !$("menu").contains(e.target) && e.target !== S.menuBtn) closeMenu();
  };
  const onShrink = () => closeMenu();
  document.addEventListener("pointerdown", onPointer, true);
  window.addEventListener("resize", onShrink);
  $("msgs").addEventListener("scroll", onShrink, { passive: true });
  $("list").addEventListener("scroll", onShrink, { passive: true });
  $("warnOk").addEventListener("click", async () => {
    for (const p of S.changed) await e2e.acknowledge(p);
    showWarn();
  });
  const onFocus = () => {
    maybeRead();
    maybeDeliver(S.chats);
  };
  window.addEventListener("focus", onFocus);
  $("shot").addEventListener("click", () => $("shot").close());
  $("shot").addEventListener("close", () => $("shotImg").removeAttribute("src"));
  const onKey = (e) => {
    if (e.key === "Escape" && S.menuFor) closeMenu(true);
    else if (e.key === "Escape" && S.editing) cancelEdit();
    else if (e.key === "Escape" && S.media && !document.querySelector("dialog[open]")) {
      dropMedia();
      $("msgIn").focus();
    } else if (e.key === "Escape" && $("app").classList.contains("open") && !document.querySelector("dialog[open]")) closeChat();
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
      onFocus();
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
            updateComposer();
            renderMessages();
          }
        }
        if (S.wantOpen && list.some((x) => x.id === S.wantOpen)) {
          const id = S.wantOpen;
          S.wantOpen = "";
          clearTimeout(S.wantTimer);
          showChat(id);
        }
        renderList();
        maybeDeliver(list);
        maybeRead();
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
        closeMenu();
        updateComposer();
        S.rows.clear();
        $("msgList").replaceChildren();
        renderMessages();
      }
    },
    stop() {
      leaveChat(); // also ends a recording and lets the microphone go
      for (const s of S.stops) s();
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onPointer, true);
      window.removeEventListener("resize", onShrink);
      document.removeEventListener("visibilitychange", onVis);
      window.removeEventListener("focus", onFocus);
      clearTimeout(rest);
      clearTimeout(toastTimer);
      clearTimeout(S.wantTimer);
      for (const p of photoUrls.values()) URL.revokeObjectURL(p.url);
    },
  };
}
