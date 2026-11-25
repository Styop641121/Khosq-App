# KHOSQ (Kotlin)

Native Android messenger: Kotlin, Jetpack Compose (Material 3), Firebase (Auth + Firestore).

- `firestore.rules` - paste into Firebase Console -> Firestore -> Rules (only after the "Rules and Worker tests" workflow is green).
- `worker/worker.js` - paste into the Cloudflare Worker. `worker/worker.test.mjs` tests it (`cd worker && node --test`).
- `rules-test/` - Firestore rules tests, run by `.github/workflows/rules.yml` in the Firebase emulator.

## Phase 3a (messenger gaps)

- Chat list: categories (All, Personal, Groups, Archive), archive, "mark as unread". Archive and the unread mark are private lists in `prefs/{uid}` (fields `archived`, `marked`).
- Chat: a pinned message (`pin` = message id in the chat document; only the id is stored, the text stays sealed), media grid (photos of the chat), one-time location message (`kind: "loc"`, coordinates travel as sealed text).
- Contact card: groups in common, photos, safety number, block button.
- New permissions: `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`. Asked only when the user taps "Send location"; the position is read once, nothing keeps listening.
- `firestore.rules` did not change in this phase (new fields fit the existing rules); `rules-test/` has new tests for them.
- Full-text search over the messages of all chats is NOT in 3a: it needs the local index of phase 4 (the server only holds sealed text, and reading every chat on each search would use up the free Firestore reads).

## Phase 3b-a (group permissions and polls)

- Group "only admins can write" mode: `sendMode: "admins"` in the chat document. Enforced by `canSend()` in `firestore.rules`; the input is replaced by a note for non-admins. Only an admin can change it (`sendMode` is a protected field).
- Admins can make a member an admin or take the role away (tap the member in the group card). The admin who does it always stays an admin, so a group never ends up without one this way.
- Polls: `kind: "poll"`. The question and options travel as sealed JSON text (`data/Poll.kt`): one question (max 120 chars) and 2-6 options (max 60 chars each).
- Votes: `votes.{uid}` in the message document, one field per voter, each sealed with the message's own key (field tag `v`, the voter's uid in the AAD). The server sees that someone voted, never for what. Tapping the option you already chose takes the vote back. A poll keeps its wrapped keys in memory (`Message.wraps`) because a vote needs them.
- `firestore.rules` changed: `sendMode` in `canSend()` and in the protected fields, `votes` (own key only) allowed in message updates, and a direct-chat request can no longer be created with `admins`, `sendMode`, `group` or `blocked` (closes a way for a requester to make themselves admin of a direct chat). `rules-test/` covers all of it.
- Not in 3b-a: invite link (needs the Worker, 3b-b); group tasks and events (need the task storage of phase 4a); "only admins pin" is hidden in the screen but not enforced by the rules (pinning only stores a message id).

## Phase 3b-b (group invite links)

- An admin opens the group card -> "Invite link" and gets `https://<worker>/j/<chatId>.<version>.<signature>`. They can send it anywhere (the share sheet), copy it, or "Reset" it (every older link stops working).
- Opening the link: the web page of the Worker has one button that opens the app (`intent://join/...` -> `khosq://join/...`). The app looks at the group (preview, no joining), asks "Join <group>?", and only then joins. A new member sees only messages written after joining (older message keys are not wrapped for them).
- All the logic is in the Worker (`worker/worker.js`, routes `POST /group/invite`, `POST /group/join`, `GET /j/<token>`); the app holds no secret. A link is signed with an HMAC (secret `INVITE_KEY`, a Cloudflare Secret). The link version lives in `invites/{chatId}` (no client access under the rules). Joining adds the caller to `members`, `names`, `handles` and writes a system line, through the Firestore REST API with the service account.
- Limits: a group takes 50 members through links; 20 invite/join calls per minute per user (per Worker instance); only an admin of a real group (not "saved messages") gets a link; a "reset" link version, a forged or altered token, and a full group are refused.
- Cloudflare: add the Secret `INVITE_KEY` (any long random text) and paste the new `worker.js`. Without `INVITE_KEY` the invite routes answer 501 and everything else works as before.
- `firestore.rules` did not change. `worker/invite.test.mjs` has 13 tests (run by `node --test` in the "Rules and Worker tests" workflow).
- The manifest has two new intent filters on `MainActivity` (`khosq://join` and the Worker host `/j/`). If `PushConfig.WORKER_URL` ever changes, change the host in the manifest too.
- A member who joins later (by a link, or added by an admin) has no key for the older sealed messages. Those are now left out of the list (`withoutOlderThanMe` in `Repository.kt`), instead of a line of "could not open" for each.
- Known limits: anyone with the link can join (the app has no phone verification), and a removed member can join again until the link is reset. Approval by an admin is not in this phase.


## Phase 4a (tasks and reminders)

- A personal list of tasks with an optional date, time and reminder. Settings -> "Assistant" -> "Tasks and reminders". A quick line at the bottom adds a task in one go; the plus opens the full form (title, note, date, time, "Remind me"). Sections: Overdue, Today, Upcoming, No date, Done (collapsed; "Clear finished tasks").
- A chat message can become a task: long-press a text message -> "Add to my tasks" (the form opens with the message text; the task remembers which chat it came from and can open it).
- Storage: only on the phone. A small SQLite database (`tasks.db`, framework `SQLiteOpenHelper`, no Room/KSP) in the app's private folder; `allowBackup` is off, so it is not in any backup. Nothing is sent to Firebase or the Worker. The text of a task made from a message is stored as plain text in that private database (the person chose to copy it).
- Reminders: `AlarmManager.setExactAndAllowWhileIdle` (`Reminders.kt`). The alarm wakes `ReminderReceiver`, which shows a notification (channel `reminders`, high importance) with "Done" and "Snooze 10 min" buttons; a tap opens the task list. Snooze moves only the next ring (`remind_at`), not the date.
- Restart and update clear all alarms, so `BootReceiver` (BOOT_COMPLETED and MY_PACKAGE_REPLACED) and `KhosqApp.onCreate` set them again; a reminder whose time passed meanwhile rings at once, exactly once (`TaskStore.claimFire` is the one place that decides). "Force stop" in Android settings cancels alarms until the app is opened again.
- Permissions: `USE_EXACT_ALARM` (granted at install on Android 13+; Google Play restricts it to alarm/calendar apps, which does not matter for this APK) and `SCHEDULE_EXACT_ALARM` (Android 12-12L, granted by default). If exact alarms are ever switched off, reminders fall back to inexact ones (a few minutes late). Notifications need the `POST_NOTIFICATIONS` permission the app already asks for.
- The rules (due dates, reminder times, groups, sorting) are in `data/Tasks.kt` with no Android code in it, so they can be checked on any computer.
- Not in 4a: repeating tasks, group tasks/events, creating tasks by voice or by the assistant (phase 4c).

## Phase 4b (KHOSQ Memory)

- Settings -> "Assistant" -> "KHOSQ Memory", and the chat menu ("Switch Memory on/off"). The person switches Memory on **per chat** (or "Switch on for all chats"), after a dialog that says what is saved. A chat with Memory off has no rows at all.
- What is saved: the text of text messages and the question of polls (`MemoryLogic.indexText`). Not saved: photos, voice, places, system lines, deleted messages, and the "could not open" marker.
- Where: only on the phone, `memory.db` (framework SQLite, `data/MemoryStore.kt`). Each text is sealed with AES-256-GCM (`MemorySeal`, fresh nonce, the chat and message numbers are signed with it). The key is 32 random bytes, kept sealed by an Android Keystore key (`khosq_memory_wrap`) and held in memory while the app runs, so a search over 20 000 messages takes about 0.2 s. If the phone loses the Keystore key, the saved texts cannot be opened: they are dropped and a new key is made. `allowBackup` is off.
- How it fills: a chat's messages are already fully opened in memory while the chat is open, so `AppViewModel.openChat` hands every emission to `memorySync` (no extra Firestore reads). For a chat with Memory on that is not open, the chat-list update triggers a short read (`memoryPull`, 8 s, one chat at a time, at most once per 2 minutes per chat). A message deleted for everyone is dropped from Memory too.
- What the person can do: search (Memory screen, or the chat-list search which now also shows "Messages in Memory"), open a saved message, edit the saved copy (the real message does not change), take one out (it is not saved again), switch a chat off (deletes its saved texts), delete everything.
- Search: every word of the query must be in the text, any order, case-insensitive; "ё" = "е" and the Armenian "և" = "եվ" (`MemoryLogic.fold`). Newest first, at most 80 results.
- Nothing is sent to Firebase or the Worker; `firestore.rules`, the Worker and the signing key did not change.
- Jump to a message: a search result (chat-list search, or "Open the chat" in the Memory sheet), a task made from a message, and a starred message open their chat scrolled to that message, which flashes for about 2 seconds (`AppState.jump`, taken by `ChatScreen`; a request older than 15 seconds is ignored; a message that is no longer in the chat just opens the chat).
- Not in 4b: photos/voice, history older than what the chat has loaded, the assistant screen that will use this search (4c).

## Phase 4c (KHOSQ Assistant)

- Where: the sparkle button in the chat-list top bar, and Settings -> "Assistant" -> "KHOSQ Assistant" (route `assistant`, `ui/Assistant.kt`).
- No language model, no network: `data/Assistant.kt` reads a sentence in Armenian, Russian or English with plain rules (`AssistantParser`) and finds one of: help, **tasks** ("what do I have tomorrow", "unfinished / done / overdue / all tasks", with a day or period), **reminder / new task** ("remind me tomorrow at 10 to call Arman", "in 2 hours", "on Friday evening", "add task buy bread"), **find** ("find the address sent last week"; also any sentence with no other meaning is taken as a search). Summaries and translation are recognised and answered honestly as "not yet" (they need a model; translation is 4d).
- The reminder verb is also read with common spellings ("հիշեցրու", "հիշացրու", "հիշցրու", "remind", "напомни") and the first four words may hold it ("ինձ հիշեցրու …"); "05:14 ին" with the suffix apart is read as a time. When a search finds nothing, the answer reminds how to write a reminder.
- Days and periods: today, tomorrow, day after tomorrow, yesterday, a weekday (future for tasks, past for searches), this / last / next week (Monday to Sunday), this / last month, "last N days". The answer always writes out the period it understood, with dates. Times: `10:30`, `at 10`, `ժամը 10-ին`, `в 10`, `5pm`, morning/evening words; plain hours are a 24-hour clock; "in N minutes/hours".
- Where answers come from (and only there): the tasks of this phone (`TaskStore`) and the saved texts of chats with Memory on (`MemoryStore.find`). With Memory on in no chat, nothing is searched in messages and the answer says so, with a button to the Memory screen. Every answer ends with a source line (tasks: "only on this phone"; messages: the chats searched); a found message opens its chat at that message.
- Search: words are folded like Memory search, stop words and the period words are dropped, long words are shortened by two letters (`AssistantLogic.stem`, so "հասցեն" finds "հասցե"), and a word may also be in the sender's or chat's name. All words must match; if nothing does and there are three or more words, results missing one word are shown and marked as such. Newest / best match first, at most 30.
- A "remind me …" / "add task …" sentence is the person's own command, so the task is **added at once** (`Reminders.store`, no confirmation). The answer shows what was understood (title, date, time, "no reminder" warnings) with **Change** (the normal task form) and **Undo** (deletes the task again). If saving fails, the answer offers Add / Cancel.
- The talk is kept only while the app runs (30 questions), never saved; the trash button clears it.
- No change to Firebase, `firestore.rules`, the Worker, the manifest or the signing key.
- Not in 4c: summaries (need a model), translation (4d), voice (4e), "important" marks on tasks, group tasks.
