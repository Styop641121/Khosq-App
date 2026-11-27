// KHOSQ Web: the two sounds of a call, made with the browser's own sound engine (no sound files): the ring of an incoming call, and the "ringing"
// tone the caller hears while the other side has not answered. A browser lets a page make sound only after the person has touched it once (a click or
// a key); until then the sounds are silent and the screen (the call window, the page title) is what tells about the call.

/** @param {object} [win]  the window (a test hands in a pretend one) */
export function makeTones(win = window) {
  let ctx = null;
  let timer = null;
  let beeps = [];
  let mode = "";

  const Engine = () => win.AudioContext || win.webkitAudioContext;

  /** The sound engine, started if the browser allows; null when there is none. */
  function engine() {
    if (!ctx) {
      const E = Engine();
      if (!E) return null;
      try {
        ctx = new E();
      } catch (e) {
        return null;
      }
    }
    if (ctx.state === "suspended") {
      try {
        const r = ctx.resume();
        if (r && typeof r.catch === "function") r.catch(() => {});
      } catch (e) {
        // still silent
      }
    }
    return ctx;
  }

  /** One beep: the given pitches together for [ms] milliseconds, with soft edges so it does not click. */
  function beep(pitches, ms, level) {
    const c = engine();
    if (!c || c.state === "closed") return;
    try {
      const t0 = c.currentTime;
      const len = ms / 1000;
      const g = c.createGain();
      g.gain.setValueAtTime(0, t0);
      g.gain.linearRampToValueAtTime(level, t0 + 0.02);
      g.gain.setValueAtTime(level, Math.max(t0 + 0.02, t0 + len - 0.03));
      g.gain.linearRampToValueAtTime(0, t0 + len);
      g.connect(c.destination);
      for (const f of pitches) {
        const o = c.createOscillator();
        o.type = "sine";
        o.frequency.value = f;
        o.connect(g);
        o.start(t0);
        o.stop(t0 + len + 0.02);
      }
    } catch (e) {
      // no sound is not a reason to stop the call
    }
  }

  const PATTERNS = {
    ring: {
      every: 3000,
      play() {
        beep([880, 1100], 350, 0.09);
        beeps.push(win.setTimeout(() => beep([880, 1100], 350, 0.09), 500));
      },
    },
    ringback: {
      every: 4000,
      play() {
        beep([425], 1000, 0.07);
      },
    },
  };

  function stop() {
    if (timer) win.clearInterval(timer);
    timer = null;
    for (const b of beeps) win.clearTimeout(b);
    beeps = [];
    mode = "";
  }

  function start(kind) {
    if (mode === kind) return;
    stop();
    mode = kind;
    PATTERNS[kind].play();
    timer = win.setInterval(() => PATTERNS[kind].play(), PATTERNS[kind].every);
  }

  return {
    ring: () => start("ring"),
    ringback: () => start("ringback"),
    stop,
    /** Called on the first touch of the page: the sound engine may start now. */
    unlock: () => {
      engine();
    },
    mode: () => mode,
  };
}
