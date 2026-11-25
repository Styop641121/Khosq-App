// Voice transcription route. Runs with: cd worker && node --test   (a fake Firebase login and a fake Workers AI, no real calls)
import test from "node:test";
import assert from "node:assert/strict";
import worker from "./worker.js";

let seen = [];
function fakeNet(uid) {
  seen = [];
  globalThis.fetch = async (url) => {
    url = String(url);
    seen.push(url);
    if (url.includes("identitytoolkit")) return uid ? new Response(JSON.stringify({ users: [{ localId: uid }] }), { status: 200 }) : new Response("{}", { status: 400 });
    throw new Error("unexpected " + url);
  };
}
let n = 0;
const fresh = () => "S" + ++n + "_" + Math.random().toString(36).slice(2);

function fakeAI(script) {
  const calls = [];
  return {
    calls,
    run: async (model, args) => {
      calls.push({ model, args });
      const f = script[model];
      if (!f) throw new Error("no such model " + model);
      return f(args, calls.filter((c) => c.model === model).length);
    },
  };
}
const TURBO = "@cf/openai/whisper-large-v3-turbo";
const OLD = "@cf/openai/whisper";

const audio = (len = 3000) => {
  const b = new Uint8Array(len);
  for (let i = 0; i < len; i++) b[i] = (i * 7 + 3) & 255;
  return b;
};
const call = (body, env, { uid = fresh(), query = "", headers = {} } = {}) => {
  fakeNet(uid);
  return worker.fetch(
    new Request("https://w.example/ai/transcribe" + query, { method: "POST", headers: { Authorization: "Bearer x", "Content-Type": "audio/mp4", ...headers }, body }),
    env,
  );
};
const fromB64 = (s) => Uint8Array.from(Buffer.from(s, "base64"));

test("transcription is off without the AI binding", async () => {
  assert.equal((await call(audio(), {})).status, 501);
});

test("a person who is not signed in gets 401, and the AI is not called", async () => {
  const ai = fakeAI({ [TURBO]: () => ({ text: "x" }) });
  const r = await call(audio(), { AI: ai }, { uid: null });
  assert.equal(r.status, 401);
  assert.equal(ai.calls.length, 0);
});

test("a body over 1 MB is 413; exactly 1 MB is taken", async () => {
  const ai = fakeAI({ [TURBO]: () => ({ text: "ok" }) });
  assert.equal((await call(audio(1_000_001), { AI: ai })).status, 413);
  assert.equal(ai.calls.length, 0);
  assert.equal((await call(audio(1_000_000), { AI: ai })).status, 200);
});

test("a declared length over 1 MB is refused before the body is read", async () => {
  const ai = fakeAI({ [TURBO]: () => ({ text: "ok" }) });
  const r = await call(audio(), { AI: ai }, { headers: { "Content-Length": "5000000" } });
  assert.equal(r.status, 413);
  assert.equal(ai.calls.length, 0);
});

test("a tiny body and unknown languages are 400", async () => {
  const ai = fakeAI({ [TURBO]: () => ({ text: "ok" }) });
  assert.equal((await call(audio(50), { AI: ai })).status, 400);
  assert.equal((await call(audio(), { AI: ai }, { query: "?lang=xx" })).status, 400);
  assert.equal((await call(audio(), { AI: ai }, { query: "?lang=fr" })).status, 400);
  assert.equal((await call(audio(), { AI: ai }, { query: "?lang=__proto__" })).status, 400);
  assert.equal(ai.calls.length, 0);
});

test("the turbo model gets the audio as base64, and no language when it is automatic", async () => {
  const bytes = audio(5000);
  const ai = fakeAI({ [TURBO]: () => ({ text: "  Բարև ձեզ  ", word_count: 2 }) });
  const r = await call(bytes, { AI: ai });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { text: "Բարև ձեզ", from: "auto", via: "whisper-turbo", empty: false });
  assert.equal(ai.calls.length, 1);
  const a = ai.calls[0].args;
  assert.deepEqual(fromB64(a.audio), bytes);
  assert.equal(a.task, "transcribe");
  assert.equal(a.condition_on_previous_text, false);
  assert.equal("language" in a, false);
});

test("a big note survives the base64 step", async () => {
  const bytes = audio(900_000);
  const ai = fakeAI({ [TURBO]: () => ({ text: "ok" }) });
  assert.equal((await call(bytes, { AI: ai })).status, 200);
  assert.deepEqual(fromB64(ai.calls[0].args.audio), bytes);
});

test("a named language is passed on", async () => {
  const ai = fakeAI({ [TURBO]: () => ({ text: "ok" }) });
  for (const lang of ["hy", "ru", "en"]) {
    const j = await (await call(audio(), { AI: ai }, { query: "?lang=" + lang })).json();
    assert.equal(j.from, lang);
  }
  assert.deepEqual(ai.calls.map((c) => c.args.language), ["hy", "ru", "en"]);
});

test("an answer in the nested shape is read too", async () => {
  const ai = fakeAI({ [TURBO]: () => ({ result: { text: "Привет" } }) });
  assert.equal((await (await call(audio(), { AI: ai })).json()).text, "Привет");
});

test("options the platform refuses are dropped one form at a time", async () => {
  const ai = fakeAI({
    [TURBO]: (args) => {
      if (args.task || args.condition_on_previous_text !== undefined) throw new Error("unknown option");
      return { text: "ok" };
    },
  });
  const j = await (await call(audio(), { AI: ai }, { query: "?lang=ru" })).json();
  assert.deepEqual([j.text, j.via], ["ok", "whisper-turbo"]);
  assert.equal(ai.calls.length, 2);
  assert.equal(ai.calls[1].args.language, "ru");
});

test("a language the model does not take: it listens without being told", async () => {
  const ai = fakeAI({
    [TURBO]: (args) => {
      if (args.language) throw new Error("bad language");
      return { text: "ok" };
    },
  });
  const j = await (await call(audio(), { AI: ai }, { query: "?lang=hy" })).json();
  assert.equal(j.text, "ok");
  assert.equal(ai.calls.length, 3);
  assert.equal("language" in ai.calls[2].args, false);
});

test("the main model fails: the older model gets the bytes as a list of numbers", async () => {
  const bytes = audio(400);
  const ai = fakeAI({
    [TURBO]: () => {
      throw new Error("cannot decode");
    },
    [OLD]: () => ({ text: "Hello" }),
  });
  const j = await (await call(bytes, { AI: ai }, { query: "?lang=en" })).json();
  assert.deepEqual([j.text, j.via], ["Hello", "whisper"]);
  const old = ai.calls.filter((c) => c.model === OLD);
  assert.equal(old.length, 1);
  assert.deepEqual(old[0].args.audio, Array.from(bytes));
  assert.equal(old[0].args.language, "en");
});

test("no speech: 200 with an empty text, and the older model is not asked", async () => {
  const ai = fakeAI({ [TURBO]: () => ({ text: "   " }), [OLD]: () => ({ text: "should not be asked" }) });
  const r = await call(audio(), { AI: ai });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { text: "", from: "auto", via: "whisper-turbo", empty: true });
  assert.equal(ai.calls.filter((c) => c.model === OLD).length, 0);
});

test("both models fail: 502 with a short reason", async () => {
  const ai = fakeAI({
    [TURBO]: () => {
      throw new Error("AiError: 5006: cannot decode audio\nfile");
    },
    [OLD]: () => {
      throw new Error("down");
    },
  });
  const r = await call(audio(), { AI: ai });
  assert.equal(r.status, 502);
  const t = await r.text();
  assert.match(t, /^transcription failed: /);
  assert.match(t, /cannot decode audio/);
  assert.ok(!t.includes("\n"));
  assert.ok(t.length < 220);
});

test("the day's free allowance used up: 503, and the older model is not bothered", async () => {
  const limit = () => {
    throw new Error("AiError: 4006: you have used up your daily free allocation of 10,000 neurons");
  };
  const ai = fakeAI({ [TURBO]: limit, [OLD]: limit });
  assert.equal((await call(audio(), { AI: ai })).status, 503);
  assert.equal(ai.calls.filter((c) => c.model === OLD).length, 0);
  assert.equal(ai.calls.length, 1);
});

test("6 notes a minute per person, then 429; another person is not held back", async () => {
  const ai = fakeAI({ [TURBO]: () => ({ text: "ok" }) });
  const uid = fresh();
  for (let i = 0; i < 6; i++) assert.equal((await call(audio(300), { AI: ai }, { uid })).status, 200);
  assert.equal((await call(audio(300), { AI: ai }, { uid })).status, 429);
  assert.equal((await call(audio(300), { AI: ai }, { uid: fresh() })).status, 200);
});

test("nothing but the login check goes out on the network", async () => {
  const ai = fakeAI({ [TURBO]: () => ({ text: "ok" }) });
  await call(audio(), { AI: ai });
  assert.equal(seen.length, 1);
  assert.match(seen[0], /identitytoolkit/);
});
