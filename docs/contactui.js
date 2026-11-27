// KHOSQ Web, stage 12 (6l): the contact card, the window of one person of a one-to-one chat. What the phone's contact card shows: the picture, the name
// (with the mark "verified"), the ID, "online" / "last seen", the "about" line, the groups we are both in, the verification (the eight pictures to
// compare, "the pictures match" / "remove"), the safety number (and the note when it changed), and the buttons: mute, block. It writes nothing
// to the database: muting and blocking go through the page's own functions (the same ones the chat menu uses), and the verification mark is kept in
// this browser (e2e.js). Everything somebody else wrote (a name, an "about", a group's title) goes in with textContent.
import { commonGroups, formatSeen, statusOf } from "./contact.js";

/**
 * @param {object} o
 * @param {ReturnType<typeof import("./contact.js").makeContact>} o.contact
 * @param {(key: string) => string} o.t
 * @param {(key: string, ...args: any[]) => string} o.tf
 * @param {(id: string) => object|undefined} o.getChat  a chat as the database holds it
 * @param {() => object[]} o.getChats  all my chats (for the groups in common)
 * @param {(chat: object) => object} o.vmOf  the page's view of a chat: {id, title, peer, handle, group, saved, avatarId, avatarV, iBlocked, muted}
 * @param {(who: string, name: string, v: number) => Node} o.avatar  a round picture (letters until the picture is there)
 * @param {() => ({seen: number, about: string, hide: boolean}|null)} o.getInfo  what the page knows about the other person now
 * @param {(id: string) => void} o.toggleMute
 * @param {(id: string, on: boolean) => void} o.setBlocked
 * @param {() => void} [o.onKeyState]  the verification or the "key changed" state may have changed (the header and the warning look again)
 * @param {Document} [o.doc]
 */
export function makeContactUi({ contact, t, tf, getChat, getChats, vmOf, avatar, getInfo, toggleMute, setBlocked, onKeyState = () => {}, doc = document }) {
  const $ = (id) => doc.getElementById(id);
  const el = (tag, cls, text) => {
    const n = doc.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  };
  const st = { id: "", peer: "", view: null, token: 0, busy: false, clock: 0, drawnAv: "" };
  const dlg = () => $("contact");

  function err(key) {
    $("ctErr").textContent = key ? t(key) : "";
    $("ctErr").hidden = !key;
  }

  const current = () => {
    if (!st.id) return null;
    const raw = getChat(st.id);
    if (!raw) return null;
    const vm = vmOf(raw);
    return vm && !vm.group && !vm.saved && vm.peer ? vm : null;
  };

  function drawStatus() {
    const s = statusOf(getInfo(), Date.now());
    const n = $("ctStatus");
    n.textContent = s.kind === "online" ? t("ctOnline") : s.kind === "seen" ? tf("ctSeen", formatSeen(s.ts)) : "";
    n.classList.toggle("on", s.kind === "online");
    n.hidden = s.kind === "none";
  }

  /** The mark next to the name: "verified" or "key changed" (nothing while never confirmed). */
  function drawMark() {
    const state = st.view && st.view.ready ? st.view.state : "none";
    const b = $("ctVer");
    b.hidden = state === "none";
    b.textContent = state === "verified" ? "✓" : state === "changed" ? "⚠" : "";
    b.className = "vb " + state;
    b.setAttribute("aria-label", state === "verified" ? t("ctVerOk") : state === "changed" ? t("ctVerChanged") : "");
    b.title = b.getAttribute("aria-label");
  }

  function drawVerify() {
    const box = $("ctVerBox");
    const v = st.view;
    const vm = current();
    if (!v || !vm) {
      box.hidden = true;
      return;
    }
    box.hidden = false;
    const state = v.ready ? v.state : "none";
    box.dataset.state = state;
    $("ctVerHead").textContent = state === "verified" ? t("ctVerOk") : state === "changed" ? t("ctVerChanged") : t("ctVerNone");
    const code = $("ctCode");
    code.replaceChildren();
    $("ctConfirm").hidden = true;
    $("ctClear").hidden = true;
    if (!v.ready) {
      $("ctVerText").textContent = t("ctNoKey");
      code.hidden = true;
      return;
    }
    $("ctVerText").textContent = state === "verified" ? tf("ctVerOkText", vm.title) : state === "changed" ? t("ctVerChangedText") : tf("ctVerNoneText", vm.title);
    code.hidden = false;
    for (const p of v.pictures) code.append(el("li", "", p));
    $("ctConfirm").hidden = state === "verified";
    $("ctConfirm").textContent = t(state === "changed" ? "ctReconfirm" : "ctConfirm");
    $("ctClear").hidden = state === "none";
  }

  function drawNumber() {
    const v = st.view;
    $("ctNumBox").hidden = !(v && v.ready);
    if (!(v && v.ready)) return;
    $("ctNum").textContent = v.number;
    // the phone shows this note only while the pictures' own warning is not showing
    $("ctChanged").hidden = !(v.changed && v.state !== "changed");
  }

  function drawButtons() {
    for (const id of ["ctConfirm", "ctClear", "ctAck", "ctMute", "ctBlock"]) $(id).disabled = st.busy;
  }

  /** Draws the whole window from what the page knows now. */
  function render() {
    const vm = current();
    if (!vm) return;
    $("ctName").textContent = vm.title;
    $("ctHandle").textContent = vm.handle ? "@" + vm.handle : "";
    $("ctHandle").hidden = !vm.handle;
    const key = vm.avatarId + "|" + vm.title + "|" + vm.avatarV;
    if (st.drawnAv !== key) {
      st.drawnAv = key;
      $("ctAv").replaceChildren(avatar(vm.avatarId, vm.title, vm.avatarV));
    }
    drawStatus();
    drawMark();
    const info = getInfo();
    const about = info && info.about ? info.about : "";
    $("ctAbout").textContent = about || t("ctNoAbout");
    $("ctAbout").classList.toggle("none", !about);
    const groups = commonGroups(getChats(), vm.peer);
    $("ctGroupsBox").hidden = groups.length === 0;
    $("ctGroups").replaceChildren(...groups.map((g) => el("li", "", g.title || t("group"))));
    $("ctMute").textContent = t(vm.muted ? "unmute" : "mute");
    $("ctBlock").textContent = t(vm.iBlocked ? "unblock" : "block");
    $("ctBlock").classList.toggle("danger", !vm.iBlocked);
    drawVerify();
    drawNumber();
    drawButtons();
  }

  /** Looks at the verification of this person again (the key, the mark, the "changed" note). */
  async function loadView() {
    const token = st.token;
    const peer = st.peer;
    let r = null;
    try {
      r = await contact.view(peer);
    } catch (e) {
      r = null;
    }
    if (token !== st.token || !dlg().open) return;
    st.view = r || { ready: false, state: "none", number: "", pictures: [], changed: false };
    drawMark();
    drawVerify();
    drawNumber();
  }

  /** One of the buttons that change the verification: nothing else starts meanwhile; afterwards the window and the page look again. */
  async function act(fn) {
    if (st.busy || !st.id) return;
    st.busy = true;
    drawButtons();
    err("");
    const token = st.token;
    try {
      const ok = await fn(st.peer);
      if (ok === false && token === st.token) err("ctNoKey");
    } catch (e) {
      if (token === st.token) err("spFail");
    } finally {
      st.busy = false;
    }
    if (token === st.token && dlg().open) {
      drawButtons();
      await loadView();
    }
    onKeyState();
  }

  function open(id) {
    st.id = id;
    const vm = current();
    if (!vm) {
      st.id = "";
      return false;
    }
    st.peer = vm.peer;
    st.view = null;
    st.token++;
    st.busy = false;
    st.drawnAv = "";
    err("");
    render();
    if (!dlg().open) dlg().showModal();
    clearInterval(st.clock);
    st.clock = setInterval(() => dlg().open && drawStatus(), 20000);
    loadView();
    return true;
  }

  function close() {
    if (dlg().open) dlg().close();
  }

  const on = [];
  const listen = (id, type, fn) => {
    const n = $(id);
    n.addEventListener(type, fn);
    on.push(() => n.removeEventListener(type, fn));
  };
  listen("ctX", "click", close);
  listen("ctClose", "click", close);
  listen("contact", "close", () => {
    clearInterval(st.clock);
    st.id = "";
    st.peer = "";
    st.view = null;
    st.token++;
    st.busy = false;
  });
  listen("ctConfirm", "click", () => act((peer) => contact.confirm(peer)));
  listen("ctClear", "click", () => act((peer) => contact.clear(peer)));
  listen("ctAck", "click", () => act((peer) => contact.acknowledge(peer)));
  listen("ctMute", "click", () => {
    if (st.id) toggleMute(st.id);
  });
  listen("ctBlock", "click", () => {
    const vm = current();
    if (!vm) return;
    setBlocked(vm.id, !vm.iBlocked);
    close();
  });

  return {
    open,
    close,
    /** My chats, my prefs or what is known about the person changed: the window looks again (and closes when the chat is gone). */
    refresh() {
      if (!dlg().open || !st.id) return;
      if (!current()) return close();
      render();
    },
    /** The language changed. */
    relang() {
      st.drawnAv = "";
      if (dlg().open && st.id) render();
    },
    stop() {
      clearInterval(st.clock);
      for (const off of on) off();
    },
  };
}
