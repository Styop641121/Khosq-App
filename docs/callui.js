// KHOSQ Web, stage 8 (6h): the call window. It does not trap the keyboard or the mouse: a voice call can be held while the chats are read and written.
// It shows what calls.js says (the state), makes the ring and the ringing tone, flashes the page title for an incoming call, and puts the other
// person's sound (and picture) on the screen. Everything somebody else wrote (a name) goes in with textContent.

/**
 * @param {object} o
 * @param {{accept: Function, hangup: Function, toggleMute: Function, toggleCamera: Function}} o.actions  what the buttons do (calls.js)
 * @param {(key: string) => string} o.t
 * @param {(uid: string, name: string) => Node} o.avatarFor  the other person's round picture
 * @param {{ring: Function, ringback: Function, stop: Function}} o.tones
 * @param {() => void} [o.onClosed]  the window closed while a button of it had the focus: the page puts the focus somewhere useful
 * @param {object} [o.win]
 * @param {Document} [o.doc]
 */
export function makeCallUi({ actions, t, avatarFor, tones, onClosed = () => {}, win = window, doc = document }) {
  const $ = (id) => doc.getElementById(id);
  let tick = null;
  let flash = null;
  let baseTitle = "";
  let lastPeer = "";
  let last = null;
  const attached = { remote: null, audio: null, local: null };

  const mmss = (s) => Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
  const noteText = (note) => {
    const k = "callN_" + note;
    const x = t(k);
    return x && x !== k ? x : t("callN_error");
  };

  function attach(node, stream, key) {
    const want = stream || null;
    if (attached[key] === want) return;
    attached[key] = want;
    node.srcObject = want;
    if (want && typeof node.play === "function") {
      try {
        const p = node.play();
        if (p && typeof p.catch === "function") p.catch(() => {});
      } catch (e) {
        // the browser asks for a touch first; the buttons of the window are that touch
      }
    }
  }

  function statusOf(s) {
    switch (s.phase) {
      case "incoming":
        return t(s.video ? "callIncomingVideo" : "callIncoming");
      case "outgoing":
        return t("callCalling");
      case "connecting":
        return t("callConnecting");
      case "connected":
        return mmss(Math.max(0, Math.floor((Date.now() - (s.startedAt || Date.now())) / 1000))) + (s.noCamera ? " · " + t("callNoCam") : "");
      case "ended":
        return s.note ? noteText(s.note) : "";
      default:
        return "";
    }
  }

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

  /** Draws the state. */
  function render(s) {
    last = s;
    const panel = $("call");
    const shown = s.phase !== "idle" && !(s.phase === "ended" && !s.note);
    const had = !panel.hidden && panel.contains(doc.activeElement);
    panel.hidden = !shown;
    panel.dataset.phase = s.phase;
    if (!shown) {
      tones.stop();
      title(false);
      if (tick) win.clearInterval(tick);
      tick = null;
      attach($("cpRemote"), null, "remote");
      attach($("cpAudio"), null, "audio");
      attach($("cpLocal"), null, "local");
      lastPeer = "";
      if (had) onClosed();
      return;
    }
    const incoming = s.phase === "incoming";
    const ended = s.phase === "ended";
    $("callName").textContent = s.peerName;
    panel.setAttribute("aria-label", s.peerName);
    if (lastPeer !== s.peerUid + "|" + s.peerName) {
      lastPeer = s.peerUid + "|" + s.peerName;
      $("cpAv").replaceChildren(avatarFor(s.peerUid, s.peerName));
    }
    $("cpStatus").textContent = statusOf(s);

    const accept = $("cpAccept");
    accept.hidden = !incoming;
    label("cpAccept", "callAnswer");
    accept.classList.toggle("vid", s.video);
    const end = $("cpEnd");
    end.hidden = ended;
    label("cpEnd", incoming ? "callDecline" : "callEnd");
    const controls = !incoming && !ended && Boolean(s.local);
    const mute = $("cpMute");
    mute.hidden = !controls;
    mute.setAttribute("aria-pressed", String(s.muted));
    label("cpMute", s.muted ? "callUnmute" : "callMute");
    const cam = $("cpCam");
    cam.hidden = !controls || !s.video || s.noCamera;
    cam.setAttribute("aria-pressed", String(!s.cameraOn));
    label("cpCam", s.cameraOn ? "callCamOff" : "callCamOn");

    const video = s.video && !incoming && !ended;
    $("cpVideo").hidden = !video;
    panel.classList.toggle("video", video);
    $("cpLocal").hidden = !(video && s.local && s.cameraOn && !s.noCamera);
    // a voice call plays on the audio element; a video call on the big picture (which has the sound too)
    attach($("cpRemote"), video ? s.remote : null, "remote");
    attach($("cpAudio"), video ? null : s.remote, "audio");
    attach($("cpLocal"), video && s.local ? s.local : null, "local");

    if (incoming) tones.ring();
    else if (s.phase === "outgoing") tones.ringback();
    else tones.stop();
    title(incoming, s.peerName);

    if (s.phase === "connected" && !tick) tick = win.setInterval(() => last && render(last), 1000);
    else if (s.phase !== "connected" && tick) {
      win.clearInterval(tick);
      tick = null;
    }
  }

  const wires = [
    ["cpAccept", () => actions.accept()],
    ["cpEnd", () => actions.hangup()],
    ["cpMute", () => actions.toggleMute()],
    ["cpCam", () => actions.toggleCamera()],
  ];
  for (const [id, fn] of wires) $(id).addEventListener("click", fn);

  return {
    render,
    /** The language changed: the words are written again. */
    relang() {
      if (last) {
        lastPeer = "";
        render(last);
      }
    },
    /** The page leaves: the window closes whatever it showed, the buttons are let go. */
    stop() {
      tones.stop();
      title(false);
      if (tick) win.clearInterval(tick);
      tick = null;
      attach($("cpRemote"), null, "remote");
      attach($("cpAudio"), null, "audio");
      attach($("cpLocal"), null, "local");
      $("call").hidden = true;
      last = null;
      lastPeer = "";
      for (const [id, fn] of wires) $(id).removeEventListener("click", fn);
    },
  };
}
