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
