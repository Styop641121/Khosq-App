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
