// KHOSQ Web, stage 11 (6k): the window "Me" (the dialog #about): my picture, my name and "about", the chat background, the theme, "Show online"; below
// them what was there before (the sign-in checks, the key's fingerprint, log out). It writes nothing itself: the profile goes through profile.js
// (which writes what the phone writes), the theme and the background go through look.js (kept in this browser only).
// Everything that comes from the server (my own name and "about", which anybody with my key could have changed on the phone) goes in with textContent.
import { avatarBytes } from "./media.js";
import { shownName } from "./profile.js";

/**
 * @param {object} o
 * @param {ReturnType<typeof import("./profile.js").makeProfile>} o.profile
 * @param {{name?: string, handle?: string, about?: string, pv?: number, hideSeen?: boolean}} o.user  my profile (profile.js changes it in place)
 * @param {(who: string, name: string, v: number, cls?: string) => Node} o.avatar  a round picture (letters until the picture is there)
 * @param {string} o.uid
 * @param {(key: string) => string} o.t
 * @param {(key: string, ...args: any[]) => string} o.tf
 * @param {(text: string) => void} o.toast
 * @param {typeof import("./look.js")} o.look
 * @param {Document} [o.doc]
 */
export function makeSettingsUi({ profile, user, avatar, uid, t, tf, toast, look, doc = document }) {
  const $ = (id) => doc.getElementById(id);
  const el = (tag, cls, text) => {
    const n = doc.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  };
  const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);
  const st = { busy: false, inflight: 0, savedName: null, savedAbout: null, copyTimer: 0, swatchKey: "", drawnPv: -1, drawnName: null, drawnHandle: null };
  const dlg = () => $("about");

  function err(key) {
    $("spErr").textContent = key ? t(key) : "";
    $("spErr").hidden = !key;
  }

  /** The "Save" button: on when something differs from what is saved, and the name is not empty (the phone's rule). */
  function syncSave() {
    const changed = $("spName").value.trim() !== shownName(user) || $("spAbout").value.trim() !== (user.about || "");
    $("spSave").disabled = st.busy || !changed || $("spName").value.trim() === "";
  }

  function drawSwatches() {
    const dark = look.isDark();
    const key = dark + "|" + look.getWall();
    if (key === st.swatchKey && $("spWalls").children.length === look.WALL_COUNT) return;
    st.swatchKey = key;
    const box = $("spWalls");
    box.replaceChildren();
    look.WALLS.forEach((w, i) => {
      const b = el("button", "wall");
      b.type = "button";
      b.setAttribute("role", "radio");
      b.setAttribute("aria-checked", String(look.getWall() === i));
      b.setAttribute("aria-label", i === 0 ? t("spWall0") : tf("spWallN", i));
      b.dataset.wall = String(i);
      if (w) {
        const [a, c] = dark ? w.dark : w.light;
        b.dataset.wp = w.pattern;
        b.style.setProperty("--wa", a);
        b.style.setProperty("--wb", c);
        b.style.setProperty("--wi", dark ? "rgba(255,255,255,0.12)" : "rgba(0,0,0,0.1)");
      }
      box.append(b);
    });
  }

  function drawThemes() {
    for (const b of $("spThemes").children) b.setAttribute("aria-checked", String(b.dataset.theme === look.getTheme()));
  }

  /** Draws the window from my profile as it is now (called each time the profile changes, so a change made on the phone shows here). */
  function render() {
    const name = shownName(user) || "KHOSQ";
    $("name").textContent = name;
    $("handle").textContent = user.handle ? "@" + user.handle : "";
    $("idChip").hidden = !user.handle;
    const pv = num(user.pv);
    if (st.drawnPv !== pv || st.drawnName !== name) {
      st.drawnPv = pv;
      st.drawnName = name;
      $("avatar").replaceChildren(avatar(uid, name, pv));
    }
    $("spPhotoX").hidden = !(pv > 0);
    // A field is rewritten only when what is saved changed (a change made on the phone), never while one of my own writes is on its way: the
    // page hears about that write at once (and about its being taken back when the server refuses it), and what is typed must stay for a new try.
    const savedName = shownName(user);
    const savedAbout = user.about || "";
    if (st.savedName !== savedName) {
      st.savedName = savedName;
      if (st.inflight === 0) $("spName").value = savedName;
    }
    if (st.savedAbout !== savedAbout) {
      st.savedAbout = savedAbout;
      if (st.inflight === 0) $("spAbout").value = savedAbout;
    }
    $("spOnline").setAttribute("aria-checked", String(user.hideSeen !== true));
    drawSwatches();
    drawThemes();
    syncSave();
  }

  /**
   * Starts one of my own writes ([start] returns profile.js's answer) and waits for the server. While it is on its way the buttons are off and the
   * typed fields are left alone. Resolves true when the server took it, false when it was refused (the reason is shown), null when there was nothing to do.
   */
  async function mine(start, failKey) {
    st.inflight++;
    let r;
    try {
      r = start();
      if (!r.ok) return null;
      st.busy = true;
      syncSave();
      $("spPhoto").disabled = true;
      $("spPhotoX").disabled = true;
      $("spOnline").disabled = true;
      try {
        await r.committed;
        return true;
      } catch (e) {
        err(failKey);
        return false;
      }
    } finally {
      st.inflight--;
      st.busy = false;
      $("spPhoto").disabled = false;
      $("spPhotoX").disabled = false;
      $("spOnline").disabled = false;
      render();
    }
  }

  async function save() {
    if ($("spSave").disabled) return;
    err("");
    const nameRaw = $("spName").value;
    const aboutRaw = $("spAbout").value;
    let cleaned = null;
    const ok = await mine(() => {
      const r = profile.save(nameRaw, aboutRaw);
      cleaned = r;
      return r;
    }, "spFail");
    if (ok !== true) return;
    // what was typed, now saved without its padding: shown like that, unless more was typed meanwhile
    if ($("spName").value === nameRaw) $("spName").value = cleaned.name;
    if ($("spAbout").value === aboutRaw) $("spAbout").value = cleaned.about;
    syncSave();
    toast(t("spSaved"));
  }

  async function pickPhoto(file) {
    if (st.busy || !file) return;
    err("");
    st.busy = true;
    syncSave();
    $("spPhoto").disabled = true;
    let bytes;
    try {
      bytes = await avatarBytes(file);
    } catch (e) {
      st.busy = false;
      $("spPhoto").disabled = false;
      err(e && e.message === "big" ? "giPhotoBig" : "giPhotoBad");
      return render();
    }
    st.busy = false;
    await mine(() => {
      const r = profile.setPhoto(bytes);
      if (!r.ok) err(r.why === "big" ? "giPhotoBig" : "giPhotoBad");
      return r;
    }, "spFail");
  }

  async function removePhoto() {
    if (st.busy) return;
    err("");
    await mine(() => profile.removePhoto(), "spFail");
  }

  async function toggleOnline() {
    if (st.busy) return;
    err("");
    await mine(() => profile.setShowOnline(user.hideSeen === true), "spFail"); // hidden now: show; shown now: hide
  }

  async function copyId() {
    if (!user.handle) return;
    let done = false;
    try {
      await doc.defaultView.navigator.clipboard.writeText(user.handle);
      done = true;
    } catch (e) {
      done = false;
    }
    const note = $("idCopied");
    note.textContent = t(done ? "spCopied" : "spCopyFail");
    note.hidden = false;
    clearTimeout(st.copyTimer);
    st.copyTimer = setTimeout(() => (note.hidden = true), 2000);
  }

  // ---------------------------------------------------------------------------------------------- wiring
  const on = [];
  const listen = (id, type, fn) => {
    const n = typeof id === "string" ? $(id) : id;
    n.addEventListener(type, fn);
    on.push(() => n.removeEventListener(type, fn));
  };
  listen("spSave", "click", save);
  listen("spName", "input", syncSave);
  listen("spAbout", "input", syncSave);
  listen("spName", "keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      save();
    }
  });
  listen("spPhoto", "click", () => {
    if (!st.busy) $("spFile").click();
  });
  listen("spFile", "change", () => {
    const f = $("spFile").files && $("spFile").files[0];
    $("spFile").value = "";
    pickPhoto(f);
  });
  listen("spPhotoX", "click", removePhoto);
  listen("spOnline", "click", toggleOnline);
  listen("idChip", "click", copyId);
  listen("spWalls", "click", (e) => {
    const b = e.target.closest && e.target.closest(".wall");
    if (!b || !$("spWalls").contains(b)) return;
    look.setWall(Number(b.dataset.wall));
    st.swatchKey = "";
    drawSwatches();
  });
  listen("spThemes", "click", (e) => {
    const b = e.target.closest && e.target.closest("button[data-theme]");
    if (!b) return;
    look.setTheme(b.dataset.theme);
    st.swatchKey = "";
    drawThemes();
    drawSwatches(); // the colours of the squares follow a dark or light page
  });

  return {
    /** Draws the window (call before it is shown, and each time my profile changed). */
    render,
    /** The language changed: the words are drawn again (the fields keep what was typed). */
    relang() {
      st.swatchKey = "";
      st.drawnPv = -1;
      render();
    },
    stop() {
      clearTimeout(st.copyTimer);
      for (const off of on) off();
    },
  };
}
