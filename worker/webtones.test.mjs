// The two sounds of a call (docs/tones.js) with a pretend sound engine and pretend timers: what is played when, how often, and that it stops.
import test from "node:test";
import assert from "node:assert/strict";
import { makeTones } from "../docs/tones.js";

/** A pretend window: a sound engine that records every beep, and timers the test moves by hand. */
function makeWin({ engine = true, suspended = false, stuck = false, throwOnCreate = false, resumeThrows = false } = {}) {
  let now = 0;
  let seq = 0;
  const timers = new Map();
  const log = { beeps: [], created: 0, resumed: 0 };
  class Ctx {
    constructor() {
      if (throwOnCreate) throw new Error("not allowed");
      log.created++;
      this.state = suspended ? "suspended" : "running";
      this.currentTime = 0;
      this.destination = {};
    }
    resume() {
      log.resumed++;
      if (resumeThrows) throw new Error("no");
      if (!stuck) this.state = "running"; // [stuck]: a browser that keeps the engine asleep until the person touches the page
      return Promise.resolve();
    }
    createGain() {
      const g = { gain: { setValueAtTime() {}, linearRampToValueAtTime() {} }, connect() {} };
      return g;
    }
    createOscillator() {
      const o = { type: "", frequency: { value: 0 }, connect() {}, start() { log.beeps.push({ at: now, f: o.frequency.value }); }, stop() {} };
      return o;
    }
  }
  const win = {
    ...(engine ? { AudioContext: Ctx } : {}),
    setTimeout: (fn, ms) => { const id = ++seq; timers.set(id, { fn, at: now + ms, every: 0 }); return id; },
    setInterval: (fn, ms) => { const id = ++seq; timers.set(id, { fn, at: now + ms, every: ms }); return id; },
    clearTimeout: (id) => timers.delete(id),
    clearInterval: (id) => timers.delete(id),
  };
  const advance = (ms) => {
    const end = now + ms;
    for (;;) {
      let next = null;
      for (const [id, t] of timers) if (t.at <= end && (!next || t.at < next.t.at)) next = { id, t };
      if (!next) break;
      now = next.t.at;
      if (next.t.every) next.t.at += next.t.every;
      else timers.delete(next.id);
      next.t.fn();
    }
    now = end;
  };
  return { win, log, advance, pending: () => timers.size };
}
const times = (log, f) => log.beeps.filter((b) => b.f === f).map((b) => b.at);

test("the ring: two double beeps (880 + 1100 Hz) every three seconds, the second half a second after the first", () => {
  const w = makeWin();
  const t = makeTones(w.win);
  t.ring();
  assert.equal(t.mode(), "ring");
  assert.deepEqual(times(w.log, 880), [0], "the first beep is at once");
  w.advance(500);
  assert.deepEqual(times(w.log, 880), [0, 500], "and the second 0.5 s later");
  w.advance(2500);
  assert.deepEqual(times(w.log, 880), [0, 500, 3000], "then again every three seconds");
  w.advance(500);
  assert.deepEqual(times(w.log, 1100), [0, 500, 3000, 3500], "both pitches sound together");
  t.stop();
});

test("the tone of a call that is being made: one long 425 Hz beep every four seconds", () => {
  const w = makeWin();
  const t = makeTones(w.win);
  t.ringback();
  assert.equal(t.mode(), "ringback");
  w.advance(8100);
  assert.deepEqual(times(w.log, 425), [0, 4000, 8000]);
  assert.deepEqual(times(w.log, 880), [], "not the ring");
  t.stop();
});

test("the same sound asked for again does not start a second one; another sound replaces the first", () => {
  const w = makeWin();
  const t = makeTones(w.win);
  t.ring();
  t.ring();
  t.ring();
  assert.equal(times(w.log, 880).length, 1, "asked for again: nothing new starts");
  assert.equal(w.pending(), 2, "one repeat and one half-beep");
  t.ringback();
  assert.equal(t.mode(), "ringback");
  w.advance(10);
  assert.equal(w.pending(), 1, "the ring's timers are gone, only the new repeat remains");
  w.advance(4000);
  assert.deepEqual(times(w.log, 880), [0], "the ring did not come back");
  t.stop();
});

test("stop: nothing sounds any more, not even the half-beep that was waiting, and the same sound can start again", () => {
  const w = makeWin();
  const t = makeTones(w.win);
  t.ring();
  t.stop();
  assert.equal(t.mode(), "");
  assert.equal(w.pending(), 0);
  w.advance(10000);
  assert.deepEqual(times(w.log, 880), [0], "only the first beep was played");
  t.ring();
  assert.equal(times(w.log, 880).length, 2, "it starts again");
  t.stop();
  t.stop();
});

test("a browser that has not let the page make sound yet: the engine is asked to start (resume) on every beep and on unlock", () => {
  const w = makeWin({ suspended: true, stuck: true });
  const t = makeTones(w.win);
  t.ring();
  assert.ok(w.log.resumed >= 1, "asked to resume");
  const before = w.log.resumed;
  w.advance(3000);
  assert.ok(w.log.resumed > before, "asked again at the next beep");
  const n = w.log.created;
  t.unlock();
  assert.equal(w.log.created, n, "one engine only");
  t.stop();
});

test("unlock creates the engine (inside the touch), without playing anything", () => {
  const w = makeWin({ suspended: true });
  const t = makeTones(w.win);
  assert.equal(w.log.created, 0, "nothing is made before it is needed");
  t.unlock();
  assert.equal(w.log.created, 1);
  assert.equal(w.log.resumed, 1);
  assert.deepEqual(w.log.beeps, []);
});

test("no sound engine, an engine that refuses, or one that cannot resume: silent, no error, the screen is what tells", () => {
  for (const o of [{ engine: false }, { throwOnCreate: true }, { suspended: true, resumeThrows: true }]) {
    const w = makeWin(o);
    const t = makeTones(w.win);
    assert.doesNotThrow(() => {
      t.unlock();
      t.ring();
      w.advance(7000);
      t.ringback();
      w.advance(9000);
      t.stop();
    }, JSON.stringify(o));
    assert.equal(w.pending(), 0);
  }
});

test("the old Safari name of the engine (webkitAudioContext) is used when there is no other", () => {
  const w = makeWin();
  w.win.webkitAudioContext = w.win.AudioContext;
  delete w.win.AudioContext;
  const t = makeTones(w.win);
  t.ring();
  assert.equal(w.log.created, 1);
  assert.equal(times(w.log, 880).length, 1);
  t.stop();
});
