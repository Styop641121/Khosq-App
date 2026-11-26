// KHOSQ Web, stage 6 (6f): "a person asking to write to you has the same name as one of your contacts". The phone shows a warning on such a
// request (a scammer copies the name of somebody you know); this is the same arithmetic as data/Verify.kt (VerifyLogic.nameKey and
// similarNames), and the test of this page makes the phone's own Kotlin code answer the same questions and compares.
//
// The web page does not know which contacts are "verified" (that mark lives on the phone), so it only ever uses the two softer warnings.

const ARMENIAN = {
  ա: "a", բ: "b", գ: "g", դ: "d", ե: "e", զ: "z", է: "e", ը: "e", թ: "t", ժ: "zh",
  ի: "i", լ: "l", խ: "kh", ծ: "ts", կ: "k", հ: "h", ձ: "dz", ղ: "gh", ճ: "ch",
  մ: "m", յ: "y", ն: "n", շ: "sh", ո: "o", չ: "ch", պ: "p", ջ: "j", ռ: "r",
  ս: "s", վ: "v", տ: "t", ր: "r", ց: "ts", ւ: "u", փ: "p", ք: "k", օ: "o", ֆ: "f",
  և: "ev",
};
const CYRILLIC = {
  а: "a", б: "b", в: "v", г: "g", д: "d", е: "e", ё: "e", ж: "zh", з: "z", и: "i",
  й: "y", к: "k", л: "l", м: "m", н: "n", о: "o", п: "p", р: "r", с: "s", т: "t",
  у: "u", ф: "f", х: "kh", ц: "ts", ч: "ch", ш: "sh", щ: "sh", ъ: "", ы: "i", ь: "",
  э: "e", ю: "yu", я: "ya",
};
const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

/** Names shorter than this are too common to say anything. */
export const MIN_KEY = 3;
/** Names this long may differ by one letter and still be counted (a short name that differs by a letter is another name). */
export const NEAR_KEY = 9;

/** One word of a name in plain Latin letters: Armenian and Russian letters by sound, accents and case removed, everything that is not a letter dropped. */
function latinWord(word) {
  const lower = word.toLowerCase().replace(/ու/g, "u");
  let out = "";
  for (let i = 0; i < lower.length; i++) {
    const ch = lower.charAt(i); // a UTF-16 unit, like Kotlin's Char
    if (has(ARMENIAN, ch)) out += ARMENIAN[ch];
    else if (has(CYRILLIC, ch)) out += CYRILLIC[ch];
    else for (const c of ch.normalize("NFD")) if (c >= "a" && c <= "z") out += c;
  }
  return out;
}

/** The name reduced to a comparable key: its words in Latin letters, sorted (so "Petrosyan Armen" and "Armen Petrosyan" meet), joined. */
export function nameKey(name) {
  // the separators are the same as the phone's: Java's \s is the ASCII spaces only
  return String(name)
    .split(/[ \t\n\x0B\f\r,._-]+/)
    .map(latinWord)
    .filter((w) => w.length > 0)
    .sort()
    .join("");
}

function distanceAtMostOne(a, b) {
  if (a === b) return true;
  const la = a.length;
  const lb = b.length;
  if (Math.abs(la - lb) > 1) return false;
  let i = 0;
  while (i < la && i < lb && a[i] === b[i]) i++;
  if (la === lb) return a.substring(i + 1) === b.substring(i + 1);
  if (la > lb) return a.substring(i + 1) === b.substring(i);
  return a.substring(i) === b.substring(i + 1);
}

/**
 * Which of [known] ([id, name] pairs) look like [name]: the same name in another alphabet, case or word order, or (for long names) one letter
 * apart. The same name comes first. Each answer is {id, name, same}.
 */
export function similarNames(name, known) {
  const key = nameKey(name);
  if (key.length < MIN_KEY) return [];
  const out = [];
  for (const [id, other] of known) {
    const k = nameKey(other);
    if (k.length < MIN_KEY) continue;
    if (k === key) out.push({ id, name: other, same: true });
    else if (key.length >= NEAR_KEY && k.length >= NEAR_KEY && distanceAtMostOne(k, key)) out.push({ id, name: other, same: false });
  }
  return out.sort((a, b) => (b.same ? 1 : 0) - (a.same ? 1 : 0));
}
