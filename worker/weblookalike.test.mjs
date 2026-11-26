// "Somebody asking to write to you has the same name as one of your contacts" (docs/lookalike.js). The fixture holds what the phone's real Kotlin
// code (VerifyLogic.nameKey / similarNames in data/Verify.kt) answered for 103 names and 309 lists of contacts; the page's code must give the same.
// Runs with: cd worker && node --test
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { nameKey, similarNames, MIN_KEY, NEAR_KEY } from "../docs/lookalike.js";

const fx = JSON.parse(readFileSync(new URL("./web.lookalike.fixture.json", import.meta.url), "utf8"));

test("the key of a name is the phone's, for 103 names in three alphabets, with accents, spaces and odd letters", () => {
  assert.ok(fx.keys.length >= 100);
  for (const [name, key] of fx.keys) assert.equal(nameKey(name), key, JSON.stringify(name));
});

test("the lists of look-alikes are the phone's, 309 of them, in the same order", () => {
  assert.ok(fx.sims.length >= 300);
  let withAnswers = 0;
  for (const c of fx.sims) {
    const got = similarNames(c.name, c.known);
    assert.deepEqual(got, c.out, JSON.stringify(c.name) + " among " + JSON.stringify(c.known));
    if (c.out.length) withAnswers++;
  }
  assert.ok(withAnswers > 20, "the cases are not all empty answers: " + withAnswers);
});

test("what the warning is for: the same name in another alphabet, case or word order", () => {
  const known = [["a", "Armen Petrosyan"], ["b", "Ani"]];
  for (const n of ["Արմեն Պետրոսյան", "Պետրոսյան Արմեն", "Армен Петросян", "ARMEN PETROSYAN", "petrosyan, armen"]) {
    const r = similarNames(n, known);
    assert.equal(r.length, 1, n);
    assert.deepEqual(r[0], { id: "a", name: "Armen Petrosyan", same: true });
  }
});

test("one letter apart counts only for long names; short and very short names say nothing", () => {
  assert.equal(similarNames("Armen Petrosyam", [["a", "Armen Petrosyan"]]).map((m) => m.same).join(), "false");
  assert.equal(similarNames("Ann", [["a", "Ana"]]).length, 0, "a short name that differs by a letter is another name");
  assert.equal(similarNames("An", [["a", "An"]]).length, 0, "two letters are too common to say anything");
  assert.equal(similarNames("", [["a", ""]]).length, 0);
  assert.ok(MIN_KEY === 3 && NEAR_KEY === 9);
});

test("the same name comes before the near one", () => {
  const r = similarNames("Armen Petrosyan", [["n", "Armen Petrosyam"], ["s", "Petrosyan Armen"]]);
  assert.deepEqual(r.map((m) => m.id + m.same), ["strue", "nfalse"]);
});
