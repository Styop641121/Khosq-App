// KHOSQ Web, stage 10 (6m): the window of a group call. It shows what gcalls.js says (the state): a ring for a call that comes in, and for a call
// that goes on, one tile for each person (their picture when the camera is on, else their round avatar), a mark for a microphone that is off,
// the time, and the buttons. It does not trap the keyboard or the mouse: a call can be held while the chats are read and written; the window can be
// made small (a bar) and the sound goes on. Everything somebody else wrote (a name) goes in with textContent.

/**
 * @param {object} o
 * @param {{join: Function, decline: Function, leave: Function, toggleMute: Function, toggleCamera: Function}} o.actions  what the buttons do (gcalls.js)
 * @param {(key: string) => string} o.t
 * @param {(key: string, ...args: string[]) => string} o.tf
 * @param {(uid: string, name: string) => Node} o.avatarFor  a person's round picture
 * @param {(chatId: string, title: string) => Node} o.groupAvatar  the group's round picture
 * @param {{ring: Function, ringback: Function, stop: Function}} o.tones
 * @param {() => void} [o.onClosed]  the window closed while a button of it had the focus: the page puts the focus somewhere useful
 * @param {object} [o.win]
 * @param {Document} [o.doc]
 */
export function makeGroupCallUi({ actions, t, tf, avatarFor, groupAvatar, tones, onClosed = () => {}, win = window, doc = document }) {
  const $ = (id) => doc.getElementById(id);
  let tick = null;
  let flash = null;
  let baseTitle = "";
  let lastKey = "";
  let last = null;
  let mini = false;
  const tiles = new Map(); // "uid" -> { el, video, av, name, mic, state, key }

  const mmss = (s) => {
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    return (h ? h + ":" + String(m).padStart(2, "0") : String(m)) + ":" + String(s % 60).padStart(2, "0");
  };
  const noteText = (note) => {
    const k = "callN_" + note;
    const x = t(k);
    return x && x !== k ? x : t("callN_error");
  };

  function title(on, name) {
    if (on) {
      if (flash) return;
      baseTitle = doc.title;
      let n = 0;
      const set = () => {
        doc.title = n++ % 2 === 0 ? "\u{1F4DE} " + name : baseTitle;
      };
      set();
      flash = win.setInterval(set, 1000);
    } else if (flash) {
      win.clearInterval(flash);
      flash = null;
      doc.title = baseTitle;
    }
  }

  const label = (id, key) => {
    const b = $(id);
    const text = t(key);
    b.setAttribute("aria-label", text);
    b.title = text;
    const lb = b.querySelector(".lb");
    if (lb) lb.textContent = text;
  };

  function play(video) {
    if (typeof video.play !== "function") return;
    try {
      const p = video.play();
      if (p && typeof p.catch === "function") p.catch(() => {});
    } catch (e) {
      // the browser asks for a touch first; the buttons of the window are that touch
    }
  }

  function makeTile(uid) {
    const el = doc.createElement("div");
    el.className = "gt";
    el.dataset.uid = uid;
    const video = doc.createElement("video");
    video.autoplay = true;
    video.playsInline = true;
    const av = doc.createElement("span");
    av.className = "gt-av";
    const bar = doc.createElement("span");
    bar.className = "gt-bar";
    const mic = doc.createElement("span");
    mic.className = "gt-mic";
    mic.hidden = true;
    mic.textContent = "\u{1F507}";
    const name = doc.createElement("span");
    name.className = "gt-name";
    const state = doc.createElement("span");
    state.className = "gt-state";
    state.hidden = true;
    bar.append(mic, name);
    el.append(video, av, bar, state);
    return { el, video, av, name, mic, state, key: "", stream: null };
  }

  /** One tile is drawn from one person (or me): [p] {uid, name, state, stream, muted, cam, you}. */
  function drawTile(tl, p, videoCall) {
    const key = p.uid + "|" + p.name;
    if (tl.key !== key) {
      tl.key = key;
      tl.av.replaceChildren(avatarFor(p.uid, p.name));
    }
    tl.name.textContent = p.you ? t("gcSelf") : p.name;
    tl.mic.hidden = !p.muted;
    tl.mic.title = t("gcMicOff");
    tl.mic.setAttribute("aria-label", t("gcMicOff"));
    const want = p.stream || null;
    if (tl.stream !== want) {
      tl.stream = want;
      tl.video.srcObject = want;
      if (want) play(tl.video);
    }
    tl.video.muted = p.you === true; // my own sound is never played back to me
    const picture = videoCall && p.cam && p.state !== "lost";
    tl.el.classList.toggle("novid", !picture);
    tl.el.classList.toggle("you", p.you === true);
    tl.state.hidden = p.you === true || p.state === "connected";
    tl.state.textContent = p.state === "lost" ? t("gcPeerLost") : t("gcPeerConnecting");
    tl.el.classList.toggle("lost", p.state === "lost");
  }

  function drawGrid(s) {
    const grid = $("gcGrid");
    const list = [];
    if (s.phase === "active" || s.phase === "joining") {
      list.push({ key: "me", uid: s.myUid || "", name: s.myName || "", state: "connected", stream: s.local, muted: s.muted, cam: s.cameraOn && !s.noCamera, you: true });
      for (const p of s.people) list.push(p);
    }
    const keep = new Set(list.map((p) => p.key || p.uid));
    for (const [uid, tl] of tiles) {
      if (keep.has(uid)) continue;
      tl.video.srcObject = null;
      tl.el.remove();
      tiles.delete(uid);
    }
    let prev = null;
    for (const p of list) {
      const k = p.key || p.uid;
      let tl = tiles.get(k);
      if (!tl) {
        tl = makeTile(k);
        tiles.set(k, tl);
      }
      drawTile(tl, p, s.video);
      // the tiles stay in the order of the list without being taken out of the page (a picture that is moved would stop and start again)
      const want = prev ? prev.nextSibling : grid.firstChild;
      if (tl.el.parentNode !== grid || tl.el !== want) grid.insertBefore(tl.el, want);
      prev = tl.el;
    }
    grid.dataset.n = String(Math.max(1, Math.min(6, list.length)));
  }

  function statusOf(s) {
    switch (s.phase) {
      case "ringing":
        return t(s.video ? "gcRingVideo" : "gcRing");
      case "joining":
        return t("callConnecting");
      case "active":
        if (s.startedAt) return mmss(Math.max(0, Math.floor((Date.now() - s.startedAt) / 1000))) + " · " + tf("gcPeople", String(s.count));
        return s.people.length ? t("callConnecting") : s.outgoing ? t("callCalling") : t("gcWaiting");
      case "ended":
        return s.note ? noteText(s.note) : "";
      default:
        return "";
    }
  }

  /** Draws the state. */
  function render(s) {
    last = s;
    const panel = $("gcall");
    const shown = s.phase !== "idle" && !(s.phase === "ended" && !s.note);
    const had = !panel.hidden && panel.contains(doc.activeElement);
    panel.hidden = !shown;
    panel.dataset.phase = s.phase;
    if (!shown) {
      tones.stop();
      title(false);
      if (tick) win.clearInterval(tick);
      tick = null;
      drawGrid({ phase: "idle", people: [], local: null });
      lastKey = "";
      mini = false;
      panel.classList.remove("mini");
      if (had) onClosed();
      return;
    }
    const ringing = s.phase === "ringing";
    const ended = s.phase === "ended";
    const inCall = s.phase === "active" || s.phase === "joining";
    $("gcName").textContent = s.title || t("group");
    panel.setAttribute("aria-label", s.title || t("group"));
    if (lastKey !== s.chatId + "|" + s.title) {
      lastKey = s.chatId + "|" + s.title;
      $("gcAv").replaceChildren(groupAvatar(s.chatId, s.title || t("group")));
    }
    $("gcStatus").textContent = statusOf(s);
    $("gcRing").hidden = !ringing;
    if (ringing) {
      $("gcHost").textContent = tf("gcInvite", s.hostName || "?");
      const names = s.ringIn.map((p) => p.name).join(", ");
      $("gcIn").textContent = names ? tf("gcInCall", names) : "";
      $("gcIn").hidden = !names;
    }
    panel.classList.toggle("video", s.video && inCall);
    panel.classList.toggle("mini", mini && inCall);
    drawGrid({ ...s, myName: s.myName });
    $("gcGrid").hidden = !inCall;

    const accept = $("gcAccept");
    accept.hidden = !ringing;
    label("gcAccept", "gcJoin");
    accept.classList.toggle("vid", s.video);
    const end = $("gcEnd");
    end.hidden = ended;
    label("gcEnd", ringing ? "callDecline" : "gcLeave");
    const controls = inCall && Boolean(s.local);
    const mute = $("gcMute");
    mute.hidden = !controls;
    mute.setAttribute("aria-pressed", String(s.muted));
    label("gcMute", s.muted ? "callUnmute" : "callMute");
    const cam = $("gcCam");
    cam.hidden = !controls || !s.video || s.noCamera;
    cam.setAttribute("aria-pressed", String(!s.cameraOn));
    label("gcCam", s.cameraOn ? "callCamOff" : "callCamOn");
    const m = $("gcMini");
    m.hidden = !inCall;
    m.setAttribute("aria-pressed", String(mini));
    label("gcMini", mini ? "gcMaxi" : "gcMin");

    if (ringing) tones.ring();
    else if (s.phase === "active" && !s.people.length && s.outgoing) tones.ringback();
    else tones.stop();
    title(ringing, s.hostName || s.title);

    if (s.phase === "active" && s.startedAt && !tick) tick = win.setInterval(() => last && render(last), 1000);
    else if ((s.phase !== "active" || !s.startedAt) && tick) {
      win.clearInterval(tick);
      tick = null;
    }
  }

  const wires = [
    ["gcAccept", () => actions.join()],
    ["gcEnd", () => actions.leave()],
    ["gcMute", () => actions.toggleMute()],
    ["gcCam", () => actions.toggleCamera()],
    [
      "gcMini",
      () => {
        mini = !mini;
        if (last) render(last);
      },
    ],
  ];
  for (const [id, fn] of wires) $(id).addEventListener("click", fn);

  return {
    render,
    /** The language changed: the words are written again. */
    relang() {
      if (last) {
        lastKey = "";
        for (const tl of tiles.values()) tl.key = "";
        render(last);
      }
    },
    /** The page leaves: the window closes whatever it showed, the buttons are let go. */
    stop() {
      tones.stop();
      title(false);
      if (tick) win.clearInterval(tick);
      tick = null;
      drawGrid({ phase: "idle", people: [], local: null });
      $("gcall").hidden = true;
      last = null;
      lastKey = "";
      for (const [id, fn] of wires) $(id).removeEventListener("click", fn);
    },
  };
}
