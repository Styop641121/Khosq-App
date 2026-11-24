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
