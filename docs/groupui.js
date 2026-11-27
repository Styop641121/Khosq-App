// KHOSQ Web, stage 9 (6i): the two group windows. "New group" (a name and the people to put in) and "Group info" (who is in it, and for an admin:
// the picture, the name, the description, the announcement mode, adding and removing people, admins). It writes nothing itself: it asks groups.js,
// which writes what the phone writes. Everything somebody else wrote (a group's name, a person's name, a description) goes in with textContent.
import { adminsOf, cleanDesc, cleanTitle, contactsOf, iAmAdmin, isRealGroup } from "./groups.js";
import { avatarBytes } from "./media.js";

const list = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === "string") : []);

/**
 * @param {object} o
 * @param {ReturnType<typeof import("./groups.js").makeGroups>} o.groups
 * @param {string} o.uid  my account id
 * @param {(key: string) => string} o.t
 * @param {(key: string, ...args: any[]) => string} o.tf
 * @param {() => object[]} o.getChats  my chats as Firestore holds them
 * @param {(id: string) => object|undefined} o.getChat
 * @param {(who: string, name: string, v: number) => Node} o.avatarOf  a round picture (letters until the picture is there)
 * @param {(chat: object, who: string) => string} o.nameOf  a person's name as the chat knows it
 * @param {(chat: object, who: string) => number} o.pvOf  the version of a person's picture (0: none)
 * @param {(text: string) => void} o.toast  a short message (used once a window is closed)
 * @param {(id: string) => void} o.onCreated  a group was made: open it when it reaches the list
 * @param {(id: string) => void} o.onLeaving  I am about to leave a group (called before the write, so the page can tell it from being taken out)
 * @param {(id: string) => void} o.onLeft  I left a group: close it if it is the open chat
 * @param {Document} [o.doc]
 */
export function makeGroupUi({ groups, uid, t, tf, getChats, getChat, avatarOf, nameOf, pvOf, toast, onCreated, onLeaving = () => {}, onLeft, doc = document }) {
  const $ = (id) => doc.getElementById(id);
  const el = (tag, cls, text) => {
    const n = doc.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  };
  const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);
  const num0 = (v) => Math.max(0, num(v));

  /** One person of a list of people to choose from: a round picture, the name, a tick. */
  function pickRow(c, picked, onChange) {
    const li = el("li");
    const b = el("button", "pk");
    b.type = "button";
    b.setAttribute("role", "checkbox");
    b.setAttribute("aria-checked", "false");
    b.dataset.id = c.uid;
    b.append(avatarOf(c.uid, c.name, c.pv), el("span", "nm", c.name || c.handle || "?"), el("span", "ck", "✓"));
    b.addEventListener("click", () => {
      if (picked.has(c.uid)) picked.delete(c.uid);
      else picked.add(c.uid);
      b.setAttribute("aria-checked", String(picked.has(c.uid)));
      onChange();
    });
    li.append(b);
    return li;
  }

  // ---------------------------------------------------------------------------------------------- new group
  const ng = { people: [], picked: new Set(), busy: false, token: 0 };

  function syncNg() {
    const n = ng.picked.size;
    $("ngCount").hidden = ng.people.length === 0;
    $("ngCount").textContent = tf("ngPick", n);
    $("ngGo").disabled = ng.busy || !cleanTitle($("ngName").value) || n === 0;
    $("ngGo").textContent = t(ng.busy ? "ngBusy" : "ngGo");
  }

  function openNew() {
    ng.token++;
    ng.busy = false;
    ng.picked = new Set();
    ng.people = contactsOf(getChats(), uid);
    $("ngName").value = "";
    $("ngErr").hidden = true;
    $("ngNone").hidden = ng.people.length > 0;
    const changed = () => {
      $("ngErr").hidden = true;
      syncNg();
    };
    $("ngList").replaceChildren(...ng.people.map((c) => pickRow(c, ng.picked, changed)));
    syncNg();
    if (!$("newGroup").open) $("newGroup").showModal();
    $("ngName").focus();
  }

  function goNew() {
    if (ng.busy) return;
    const r = groups.create($("ngName").value, ng.people.filter((c) => ng.picked.has(c.uid)));
    if (!r.ok) return syncNg();
    const token = ++ng.token;
    ng.busy = true;
    $("ngErr").hidden = true;
    syncNg();
    r.committed.then(
      () => {
        if (token !== ng.token) return; // the window was left meanwhile; the group is made all the same and shows in the list
        ng.busy = false;
        $("newGroup").close();
        onCreated(r.id);
      },
      () => {
        if (token !== ng.token) return;
        ng.busy = false;
        $("ngErr").textContent = t("ngFail");
        $("ngErr").hidden = false;
        syncNg();
      },
    );
  }

  $("ngName").addEventListener("input", () => {
    $("ngErr").hidden = true;
    syncNg();
  });
  $("ngName").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.isComposing) {
      e.preventDefault();
      if (!$("ngGo").disabled) goNew();
    }
  });
  $("ngGo").addEventListener("click", goNew);
  $("ngNo").addEventListener("click", () => $("newGroup").close());
  $("newGroup").addEventListener("close", () => {
    ng.token++;
    ng.busy = false;
  });

  // ---------------------------------------------------------------------------------------------- group info
  const gi = { id: "", mode: "", picked: new Set(), cands: [], ask: null, busyPhoto: false };

  const err = (key) => {
    $("giErr").textContent = t(key);
    $("giErr").hidden = false;
  };

  /** Runs the answer of groups.js: a refusal is told at once, a write the server refuses later is told when it comes. */
  function run(r) {
    $("giErr").hidden = true;
    if (!r.ok) {
      err(r.why === "big" ? "giPhotoBig" : "giFail");
      return false;
    }
    r.committed.catch(() => err("giFail"));
    return true;
  }

  const chatNow = () => getChat(gi.id);

  function closeEditors() {
    gi.mode = "";
    gi.picked.clear();
    $("giEdit").hidden = true;
    $("giAdd").hidden = true;
    gi.ask = null;
    $("giAsk").hidden = true;
  }

  function syncEdit() {
    const c = chatNow();
    if (!c || !gi.mode) return;
    if (gi.mode === "rename") {
      const v = cleanTitle($("giEditIn").value);
      $("giEditGo").disabled = !v || v === (c.title || "");
    } else if (gi.mode === "desc") {
      $("giEditGo").disabled = cleanDesc($("giEditTa").value) === (typeof c.desc === "string" ? c.desc : "");
    }
  }

  function syncAdd() {
    const n = gi.picked.size;
    $("giAddGo").disabled = n === 0;
    $("giAddGo").textContent = tf("giAddGo", n);
  }

  function startEdit(mode) {
    const c = chatNow();
    if (!c || !iAmAdmin(c, uid)) return;
    closeEditors();
    $("giErr").hidden = true;
    gi.mode = mode;
    const rename = mode === "rename";
    $("giEditLb").textContent = t(rename ? "giRenameLb" : "giDescLb");
    $("giEditLb").htmlFor = rename ? "giEditIn" : "giEditTa";
    $("giEditIn").hidden = !rename;
    $("giEditTa").hidden = rename;
    $("giEditIn").value = rename ? c.title || "" : "";
    $("giEditTa").value = rename ? "" : typeof c.desc === "string" ? c.desc : "";
    $("giEdit").hidden = false;
    syncEdit();
    (rename ? $("giEditIn") : $("giEditTa")).focus();
  }

  function saveEdit() {
    const c = chatNow();
    if (!c || !gi.mode || $("giEditGo").disabled) return;
    const r = gi.mode === "rename" ? groups.rename(c, $("giEditIn").value) : groups.setDesc(c, $("giEditTa").value);
    if (run(r) || r.why === "same") closeEditors();
  }

  function startAdd() {
    const c = chatNow();
    if (!c || !iAmAdmin(c, uid)) return;
    closeEditors();
    $("giErr").hidden = true;
    gi.mode = "add";
    const inGroup = new Set(list(c.members));
    gi.cands = contactsOf(getChats(), uid).filter((p) => !inGroup.has(p.uid));
    $("giAddNone").hidden = gi.cands.length > 0;
    $("giAddList").replaceChildren(...gi.cands.map((p) => pickRow(p, gi.picked, syncAdd)));
    $("giAddList").hidden = gi.cands.length === 0;
    $("giAddGo").hidden = gi.cands.length === 0;
    syncAdd();
    $("giAdd").hidden = false;
    const first = $("giAddList").querySelector("button") || $("giAddNo");
    first.focus();
  }

  function saveAdd() {
    const c = chatNow();
    if (!c || gi.mode !== "add" || !gi.picked.size) return;
    if (run(groups.addMembers(c, gi.cands.filter((p) => gi.picked.has(p.uid))))) closeEditors();
  }

  function ask(kind, who, name) {
    $("giErr").hidden = true;
    closeEditors();
    gi.ask = { kind, who, name };
    $("giAskText").textContent = kind === "leave" ? t("giLeaveAsk") : tf("giRemoveAsk", name);
    $("giAskYes").textContent = t(kind === "leave" ? "giLeaveYes" : "giRemove");
    $("giAsk").hidden = false;
    $("giAskNo").focus();
  }

  function answerAsk() {
    const c = chatNow();
    const a = gi.ask;
    if (!c || !a) return;
    if (a.kind === "leave") {
      const id = c.id;
      onLeaving(id);
      const r = groups.leave(c);
      if (!r.ok) return err("giFail");
      r.committed.catch(() => toast(t("giFail")));
      gi.ask = null;
      $("ginfo").close();
      onLeft(id);
      return;
    }
    const r = groups.removeMember(c, a.who, a.name);
    gi.ask = null;
    $("giAsk").hidden = true;
    run(r);
  }

  async function pickPhoto(file) {
    const c = chatNow();
    if (!c || !iAmAdmin(c, uid) || gi.busyPhoto) return;
    $("giErr").hidden = true;
    gi.busyPhoto = true;
    $("giPhoto").disabled = true;
    try {
      const bytes = await avatarBytes(file);
      const now = chatNow();
      if (now && gi.id === c.id) run(groups.setPhoto(now, bytes));
    } catch (e) {
      err(e && e.message === "big" ? "giPhotoBig" : "giPhotoBad");
    } finally {
      gi.busyPhoto = false;
      $("giPhoto").disabled = false;
    }
  }

  /** Draws the window from the chat as it is now (also called each time the chat changes, so a change made on the phone shows here). */
  function render() {
    const d = $("ginfo");
    if (!d.open) return;
    const c = chatNow();
    if (!isRealGroup(c) || !list(c.members).includes(uid)) {
      closeEditors();
      d.close();
      return;
    }
    const admin = iAmAdmin(c, uid);
    const admins = adminsOf(c);
    const members = list(c.members);
    const title = c.title || t("group");
    $("giAv").replaceChildren(avatarOf(c.id, title, num0(c.gpv)));
    $("giName").textContent = title;
    $("giSub").textContent = tf("members", members.length);
    const desc = typeof c.desc === "string" ? c.desc : "";
    $("giDescBox").hidden = !desc;
    $("giDesc").textContent = desc;
    $("giActs").hidden = !admin;
    $("giPhotoX").hidden = !(num0(c.gpv) > 0);
    const only = (c.sendMode || "all") !== "all";
    $("giModeB").setAttribute("aria-checked", String(only));
    $("giModeNote").textContent = t(only ? "giModeOn" : "giModeOff");
    if (!admin && (gi.mode || (gi.ask && gi.ask.kind !== "leave"))) closeEditors();
    if (gi.ask && gi.ask.kind === "remove" && !members.includes(gi.ask.who)) {
      gi.ask = null;
      $("giAsk").hidden = true;
    }
    $("giMembers").replaceChildren(
      ...members.map((who) => {
        const name = who === uid ? t("you") : nameOf(c, who);
        const li = el("li");
        li.dataset.id = who;
        const isAdm = admins.includes(who);
        const who2 = el("span", "gwho");
        const top = el("span", "gtop");
        top.append(el("b", "", name));
        if (isAdm) top.append(el("span", "adm", t("giAdmin")));
        who2.append(top);
        const handle = c.handles && typeof c.handles[who] === "string" ? c.handles[who] : "";
        if (handle) who2.append(el("small", "", "@" + handle));
        li.append(avatarOf(who, name, pvOf(c, who)), who2);
        if (admin && who !== uid) {
          const mk = el("button", "mini", t(isAdm ? "giUnAdmin" : "giMakeAdmin"));
          mk.type = "button";
          mk.dataset.act = "admin";
          mk.setAttribute("aria-label", tf(isAdm ? "giUnAdminOf" : "giMakeAdminOf", name));
          mk.addEventListener("click", () => {
            const now = chatNow();
            if (now) run(groups.setAdmin(now, who, nameOf(now, who), !adminsOf(now).includes(who)));
          });
          const rm = el("button", "mini danger", t("giRemove"));
          rm.type = "button";
          rm.dataset.act = "remove";
          rm.setAttribute("aria-label", tf("giRemoveOf", name));
          rm.addEventListener("click", () => ask("remove", who, nameOf(c, who)));
          const acts = el("span", "acts");
          acts.append(mk, rm);
          li.append(acts);
        }
        return li;
      }),
    );
    syncEdit();
  }

  function openInfo(id) {
    const c = getChat(id);
    if (!isRealGroup(c) || !list(c.members).includes(uid)) return;
    gi.id = id;
    closeEditors();
    $("giErr").hidden = true;
    gi.busyPhoto = false;
    $("giPhoto").disabled = false;
    if (!$("ginfo").open) $("ginfo").showModal();
    render();
    $("giClose").focus();
  }

  $("giPhoto").addEventListener("click", () => $("giFile").click());
  $("giFile").addEventListener("change", () => {
    const file = $("giFile").files && $("giFile").files[0];
    $("giFile").value = ""; // the same file can be chosen again
    if (file) pickPhoto(file);
  });
  $("giPhotoX").addEventListener("click", () => {
    const c = chatNow();
    if (c) run(groups.removePhoto(c));
  });
  $("giDescB").addEventListener("click", () => startEdit("desc"));
  $("giRenameB").addEventListener("click", () => startEdit("rename"));
  $("giModeB").addEventListener("click", () => {
    const c = chatNow();
    if (c) run(groups.setAdminOnly(c, (c.sendMode || "all") === "all"));
  });
  $("giAddB").addEventListener("click", startAdd);
  $("giEditIn").addEventListener("input", syncEdit);
  $("giEditTa").addEventListener("input", syncEdit);
  $("giEditIn").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.isComposing) {
      e.preventDefault();
      saveEdit();
    }
  });
  $("giEditGo").addEventListener("click", saveEdit);
  $("giEditNo").addEventListener("click", closeEditors);
  $("giAddGo").addEventListener("click", saveAdd);
  $("giAddNo").addEventListener("click", closeEditors);
  $("giLeave").addEventListener("click", () => ask("leave"));
  $("giAskNo").addEventListener("click", () => {
    gi.ask = null;
    $("giAsk").hidden = true;
  });
  $("giAskYes").addEventListener("click", answerAsk);
  $("giX").addEventListener("click", () => $("ginfo").close());
  $("giClose").addEventListener("click", () => $("ginfo").close());
  // Esc first closes what is open inside the window (an editor, a question), and only then the window
  $("ginfo").addEventListener("cancel", (e) => {
    if (gi.mode || gi.ask) {
      e.preventDefault();
      closeEditors();
    }
  });
  $("ginfo").addEventListener("close", () => {
    closeEditors();
    gi.id = "";
  });

  return {
    openNew,
    openInfo,
    /** The chat list changed: the window of a group shows the new state (or closes, when I am no longer in the group). */
    refresh: render,
    /** The language changed. */
    relang() {
      if ($("newGroup").open) syncNg();
      if ($("ginfo").open) {
        if (gi.mode === "rename" || gi.mode === "desc") $("giEditLb").textContent = t(gi.mode === "rename" ? "giRenameLb" : "giDescLb");
        if (gi.ask) $("giAskText").textContent = gi.ask.kind === "leave" ? t("giLeaveAsk") : tf("giRemoveAsk", gi.ask.name);
        if (gi.ask) $("giAskYes").textContent = t(gi.ask.kind === "leave" ? "giLeaveYes" : "giRemove");
        render();
        syncAdd();
      }
    },
    /** The page stops (log out): the windows close. */
    stop() {
      if ($("newGroup").open) $("newGroup").close();
      if ($("ginfo").open) $("ginfo").close();
    },
  };
}
