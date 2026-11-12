# Implementation Plan: Sync Engine & Conflict Judge

## Overview

The skeleton already contains the append-only operation log, the pure-Java `SyncEngine` with tier-1 conflict rules, `SimDevice`/`Simulation`, the existing unit and property-based test scaffolding, and the Spring Boot push/pull API. This plan builds on that foundation following the build order in ARCHITECTURE.md §12:

1. Complete the operation log layer (review-decision / duplicate log ops, `REVIEW_DECISION` disposition, fold logic)
2. Finish the simulator and property-based tests (10,000 scenarios, all 4 properties)
3. YAML domain spec loader and both demo app specs
4. Tier-2 AI judge and tier-3 review queue with API
5. Duplicate detector, phonetic matcher, and duplicate lifecycle API
6. TypeScript sync client (PWA, IndexedDB, outbox, HLC)
7. UI screens for Visit Ledger and Stock Ledger
8. Demo view (side-by-side simulated devices)
9. Evaluation set, README results, deploy

## Tasks

- [ ] 1. Extend the core domain model: review-decision and duplicate log operations
  - [x] 1.1 Add `REVIEW_DECISION` to `Disposition` enum and add `review-decision` and `delete-dispute-decision` to `OpKind`; also add `duplicate-merge` and `duplicate-undo` to `OpKind`
    - Extend `Disposition.java` with `REVIEW_DECISION`
    - Extend `OpKind.java` and its `json()` / `fromJson()` with the four new kinds
    - _Requirements: 7.4, 12.9, 12.11_

  - [ ] 1.2 Implement review-decision fold logic in `RecordState.applySet()`
    - A `review-decision` operation on a disputed field MUST clear `FieldState.candidates` and set value + hlc from the operation; `FieldState.EMPTY.candidates` being non-empty is the only signal that a field is disputed
    - A `delete-dispute-decision` with value `"delete"` MUST set `tombstone` and clear `deleteDispute`; value `"keep"` MUST clear only `deleteDispute`
    - A plain `SET` operation on a field that already has non-empty `candidates` MUST add to candidates, not clear them
    - _Requirements: 7.4, 3.8_

  - [ ] 1.3 Implement duplicate-merge and duplicate-undo fold logic in `RecordState`
    - `duplicate-merge`: copy `mergedFields` from the operation value onto the surviving record's `FieldState` using each field's merge policy; mark absorbed record as tombstoned (no entry in `Replica.records` after `duplicate-undo` rebuilds it)
    - `duplicate-undo`: remove absorbed record's tombstone and restore its `FieldState` from entries before the merge position
    - Alias table (`Map<String,String>` from absorbed recordId → surviving recordId) is derived inside `Replica` during `apply()` from these two kinds; `Replica.resolveAlias(String recordId)` returns the canonical id
    - _Requirements: 12.9, 12.11_

  - [ ]* 1.4 Unit tests for review-decision and duplicate-merge/undo fold
    - Test: reviewer decision on a critical field clears dispute and sets correct value
    - Test: delete-dispute-decision with "delete" tombstones the record
    - Test: delete-dispute-decision with "keep" clears deleteDispute but leaves record live
    - Test: duplicate-merge / duplicate-undo round-trip; both IDs resolve to surviving record after merge, back to original IDs after undo
    - _Requirements: 7.4, 12.9, 12.11_

- [ ] 2. Complete property-based tests to meet Requirements 9 and 13
  - [ ] 2.1 Extend `SyncPropertiesTest` scenarios to exercise `review-decision` paths: inject a reviewer decision operation after a critical conflict and after a delete-vs-edit dispute, then run `syncAll()` and re-check convergence
    - Decision op is authored with `deviceId = "server"` and `kind = "review-decision"`; use `SyncEngine.push()` to inject it
    - Annotate with `// Feature: sync-and-conflict-judge, Property 1: Convergence` and `// Feature: sync-and-conflict-judge, Property 6: Critical fields are never auto-merged`
    - _Requirements: 9.1, 9.4, 13.1_

  - [ ]* 2.2 Write property test for HLC monotonicity and round-trip (Property 5)
    - **Property 5: HLC monotonicity and round-trip**
    - Every successive `HlcClock.tick()` on the same node MUST return a strictly greater `Hlc`
    - `Hlc.parse(hlc.format())` MUST equal the original `Hlc` for any generated millis/counter/node triple
    - Annotate with `// Feature: sync-and-conflict-judge, Property 5: HLC monotonicity and round-trip`
    - _Requirements: 3.5, 3.6_

  - [ ]* 2.3 Write property test for counter delta correctness (Property 7)
    - **Property 7: Counter delta correctness**
    - For random base *b*, device-A value *a*, device-B value *c*: merged counter MUST equal `b + (a−b) + (c−b)`
    - Use `SyncEngine` directly; no Spring context needed
    - Annotate with `// Feature: sync-and-conflict-judge, Property 7: Counter delta correctness`
    - _Requirements: 5.1_

  - [ ] 2.4 Set the shared constant `TRIES` to `10_000` so that each property runs 10,000 scenarios (not 4 × 2,500 as currently configured), and add `@StatisticsReport` to each property method so jqwik prints per-scenario stats
    - Add `Statistics.collect("ops applied", ...)` and `Statistics.collect("conflicts by tier", ...)` inside the scenario runner
    - _Requirements: 13.2, 13.3_

- [ ] 3. Checkpoint — run `cd skeleton/server && ./mvnw test` and confirm all existing tests pass before proceeding
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. YAML domain spec loader and demo app specs
  - [ ] 4.1 Create `dev.doppelganger.spec` package with `IdentityFieldSpec`, `MatchMethod` enum, `DomainSpecLoader`
    - `MatchMethod`: `EXACT`, `PHONETIC`, `NUMERIC_TOLERANCE`, `AI_JUDGE`
    - `IdentityFieldSpec`: field name, `MatchMethod`, optional `tolerance` for `NUMERIC_TOLERANCE`
    - `DomainSpecLoader.load(Path)` reads YAML (using Spring's `SnakeYAML` already on the classpath), builds a `RecordSpec` for each record type, validates that all policies are known, all field types are present, no duplicate names, invariant expressions parse; throws `IllegalArgumentException` with an exact error message on failure
    - Add `blockingKey` list and `thresholds` (`review`, `autoMerge`) to `RecordSpec` for later use by the duplicate detector
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

  - [ ] 4.2 Write `visit-ledger/domain-spec.yaml` and `stock-ledger/domain-spec.yaml`
    - Visit Ledger: `patient` record with `name` (latest), `visitNotes` (append-only), `symptoms` (set-union), `medicineDose` (critical), `followUp` (ai-judge), `age` (latest), `village` (latest); identity fields: `name` (phonetic), `age` (numeric-tolerance, tolerance: 2), `village` (exact); blockingKey: [village, name.phonetic]; thresholds: review 0.6, autoMerge 0.9
    - Stock Ledger: `item` record with `name` (latest), `quantity` (counter, invariant: quantity >= 0), `price` (critical), `supplierNotes` (ai-judge); identity fields: `name` (ai-judge), `unitSize` (exact); blockingKey: [name.phonetic]; thresholds: review 0.6, autoMerge 0.9
    - All demo data MUST be fictional (no real personal data)
    - _Requirements: 10.1, 10.2, 10.3_

  - [ ] 4.3 Wire `DomainSpecLoader` into Spring Boot: replace `BuiltInSpecs` hardcoded Java spec with a `@Bean` that calls `DomainSpecLoader.load()` for each YAML file in `visit-ledger/` and `stock-ledger/`, then merges them into one `DomainSpec`; the server MUST refuse to start if any YAML is invalid
    - _Requirements: 4.1, 4.2, 10.3_

  - [ ]* 4.4 Unit tests for `DomainSpecLoader`
    - Test: valid visit-ledger spec loads without error, field policies match expected values, invariant on quantity parses correctly
    - Test: unknown policy name → `IllegalArgumentException` with the field name in the message
    - Test: missing field type → `IllegalArgumentException` with the field name
    - Test: duplicate record type name → `IllegalArgumentException`
    - Test: invalid invariant expression → `IllegalArgumentException`
    - _Requirements: 4.1, 4.2_

- [ ] 5. Tier-2 AI judge and tier-3 review queue
  - [ ] 5.1 Create `dev.doppelganger.judge` package with `AiJudgeProvider` interface, `FieldConflict`, `FieldVerdict`, `DuplicatePair`, `MatchVerdict` records, and `FakeJudgeProvider`
    - `AiJudgeProvider`: methods `resolveField(FieldConflict)` and `judgeDuplicate(DuplicatePair)`
    - `FakeJudgeProvider`: if `incomingValue` starts with `"FAIL:"` → throw `RuntimeException`; if starts with `"LOW:"` → return verdict with confidence 0.1; otherwise → return verdict with confidence 0.95; for `judgeDuplicate`: if either record ID contains `"FAIL"` → throw; if contains `"LOW"` → return `MatchVerdict(same=false, 0.1, ...)`; otherwise → `MatchVerdict(same=true, 0.95, ...)`
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

  - [ ] 5.2 Implement `GeminiJudgeProvider`
    - POST to Gemini API with structured JSON prompt; parse response JSON for `proposedValue`, `confidence`, `explanation`; throw `RuntimeException` on invalid output or API error
    - Confidence must be validated to be in [0, 1]; null proposed value must throw
    - Use `spring.ai.gemini.api-key` application property (or env var `GEMINI_API_KEY`); use `RestClient` already available via Spring Boot
    - Only send fields needed for the decision (no unrelated personal data)
    - _Requirements: 6.1, 6.2_

  - [ ] 5.3 Extend `SyncService.push()` to call the AI judge for `ai-judge` field conflicts after `SyncEngine.push()` returns
    - Inspect `PushResult.appended()` for entries with `Disposition.DISPUTED` whose field policy is `AI_JUDGE` (not `CRITICAL`)
    - For each such entry call `AiJudgeProvider.resolveField()`; if high confidence (>= configured threshold, default 0.7), construct a server `Operation` with `kind = SET`, `deviceId = "server"` and push it back via `SyncEngine.push()`; otherwise leave in review queue
    - If the AI throws or returns invalid output, leave the item in the review queue and log the error; do NOT propagate the exception — the sync continues
    - _Requirements: 6.3, 6.4, 6.5_

  - [ ] 5.4 Extend `SyncService` to generate AI summaries for `critical` field conflicts
    - After `SyncEngine.push()`, for each `DISPUTED` entry on a `CRITICAL` field, call `AiJudgeProvider.resolveField()` in summary mode (flag in `FieldConflict`); store `FieldVerdict.explanation` in the review queue row's `aiSummary` column; if AI unavailable use fallback text `"AI summary unavailable — please review both values manually."`
    - _Requirements: 7.3_

  - [ ] 5.5 Add `review_queue` and `resolutions` JPA entities and repositories to the `server` package
    - `ReviewQueueEntity`: id, record type, record id, field (nullable), current value, candidate values JSON, ai summary, status (OPEN/CLOSED), created timestamp
    - `ResolutionEntity`: mirrors `Resolution` record; never deleted; store tier, all values, explanation, incomingOpId, resolutionOpId
    - `SyncService` MUST persist new `ReviewQueueEntity` rows for all DISPUTED items after each push, and persist `Resolution` objects from `SyncEngine.resolutions()`
    - _Requirements: 8.1, 8.3_

  - [ ] 5.6 Implement `ReviewController` at `/api/review`
    - `GET /api/review` → list open `ReviewQueueEntity` rows; include `aiSummary` in response
    - `POST /api/review/{id}/decide` with body `{ "value": "...", "decision": "approve|reject|keep|delete" }` → write a `review-decision` or `delete-dispute-decision` `Operation` (deviceId: `"server"`) via `SyncService.push()`, close the queue item, persist a `Resolution`
    - _Requirements: 7.4, 8.1, 8.2_

  - [ ] 5.7 Implement `HistoryController` at `/api/records/{type}/{id}/history`
    - `GET` returns all `ResolutionEntity` rows for the given record, ordered by creation time; never returns deleted rows (there are none — requirement 8.3)
    - _Requirements: 8.2, 8.3_

  - [ ]* 5.8 Unit tests for `SyncService` AI integration with `FakeJudgeProvider`
    - Test: `ai-judge` conflict with normal value → AI resolves it → server-authored SET op appears in log → field value is set, not disputed
    - Test: `ai-judge` conflict with `"LOW:"` prefix value → low confidence → item added to review queue → field remains DISPUTED
    - Test: `ai-judge` conflict with `"FAIL:"` prefix value → AI throws → item added to review queue → sync continues for subsequent ops
    - Test: `critical` field conflict → `aiSummary` populated on review queue row
    - _Requirements: 6.3, 6.4, 7.3_

  - [ ]* 5.9 Write property test for AI unavailability safety (Property 8)
    - **Property 8: AI unavailability safety**
    - For any `ai-judge` field conflict where `FakeJudgeProvider` throws (value starts with `"FAIL:"`), the field MUST be DISPUTED and all other operations in the same batch MUST be processed normally (their log entries appended, non-AI fields applied)
    - Annotate with `// Feature: sync-and-conflict-judge, Property 8: AI unavailability safety`
    - _Requirements: 6.4_

- [ ] 6. Checkpoint — run all tests with FakeJudgeProvider; confirm AI-judge and review-queue flows pass before proceeding
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. Duplicate detector
  - [ ] 7.1 Create `dev.doppelganger.dup` package with `PhoneticMatcher`, `MatchScorer`, `DuplicateDetector`, `DuplicateCandidate`
    - `PhoneticMatcher.score(String, String)`: normalize (lowercase, strip diacritics, collapse repeated consonants), apply substitution rules (`kh→k`, `gh→g`, `sh→s`, `mohd→mohammad`, `kumar/kumaar→kumar`), compute Jaro-Winkler similarity; return score in [0, 1]
    - `MatchScorer.score(RecordState, RecordState, RecordSpec)`: for each identity field, compute similarity using declared `MatchMethod`; weight scores to produce overall score in [0, 1]
    - `DuplicateDetector.check(RecordKey, Replica, RecordSpec, AiJudgeProvider)`: (1) compute block key; (2) find records sharing the block key; (3) score each pair; (4) route per band: score ≥ autoMerge and no critical diff → `AUTO_MERGED`; score ≥ review → ask AI → low confidence → `SENT_TO_REVIEW`; score < review → `DIFFERENT`; any critical field differs → `SENT_TO_REVIEW` regardless
    - Duplicate detection fires when ALL identity fields of a record are non-null for the first time, or when an identity field is subsequently updated; NOT on `CREATE` itself
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 12.8_

  - [ ] 7.2 Wire `DuplicateDetector` into `SyncService`: after each `push()`, for every record whose identity fields became fully populated or changed, call `DuplicateDetector.check()`; for `AUTO_MERGED` pairs write a `duplicate-merge` `Operation` via `SyncEngine.push()`; for `SENT_TO_REVIEW` pairs add a `ReviewQueueEntity` row of type `DUPLICATE`
    - _Requirements: 12.2, 12.5, 12.6_

  - [ ] 7.3 Implement `duplicate_decisions` persistence: a `DuplicateDecisionEntity` (recordPairKey = canonical sorted pair, decision = `SAME|NOT_SAME`) is written when the reviewer decides; `DuplicateDetector.check()` looks up existing decisions before creating new candidates
    - _Requirements: 12.10_

  - [ ] 7.4 Implement `POST /api/duplicates/{id}/undo` on `ReviewController`
    - Write a `duplicate-undo` `Operation` via `SyncService.push()` to restore both records; persist a `Resolution`; close the review queue item
    - _Requirements: 12.11_

  - [ ]* 7.5 Unit tests for `DuplicateDetector` and `PhoneticMatcher`
    - Test: exact-match pair (score 1.0) → `AUTO_MERGED`
    - Test: near-match pair (score between thresholds, `FakeJudgeProvider` high confidence) → `AUTO_MERGED`
    - Test: near-match pair (score between thresholds, `FakeJudgeProvider` `"LOW:"`) → `SENT_TO_REVIEW`
    - Test: clearly different pair (score < review threshold) → `DIFFERENT`, no candidate created
    - Test: critical field differs → `SENT_TO_REVIEW` regardless of score
    - Test: remembered "not the same" decision suppresses future candidates for the same pair
    - Phonetic fixture list: `"Ramesh Kumar"/"Ramesh Kumaar"`, `"Mohd Ali"/"Mohammad Ali"`, `"Priya"/"Priyaa"` all MUST have score > 0.8
    - _Requirements: 12.3, 12.5, 12.6, 12.8, 12.10_

  - [ ]* 7.6 Write property test for duplicate merge round-trip (Property 10)
    - **Property 10: Duplicate merge round-trip**
    - For any two records merged as duplicates, both original IDs MUST resolve to the merged record via `Replica.resolveAlias()`, the merged record MUST contain combined field values per merge policy, and a `Resolution` MUST exist for the merge
    - Annotate with `// Feature: sync-and-conflict-judge, Property 10: Duplicate merge round-trip`
    - _Requirements: 12.9_

- [ ] 8. Checkpoint — run all tests; verify duplicate detector, merge/undo log ops, and phonetic fixture list all pass before proceeding
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. TypeScript sync client (`skeleton/sync-client`)
  - [ ] 9.1 Scaffold the `skeleton/sync-client` TypeScript package with `tsconfig.json`, `package.json`, and an `src/` directory; add `idb` (IndexedDB wrapper) as the only new dependency
    - _Requirements: 1.1, 1.2, 1.3_

  - [ ] 9.2 Implement `HlcClock.ts`: same tick/observe algorithm as the Java `HlcClock`; `tick()` returns a string in `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'-{counter4hex}-{nodeId}` format; `observe(remoteHlc: string)` advances the clock
    - _Requirements: 3.5, 3.6_

  - [ ] 9.3 Implement `LocalStore.ts`: IndexedDB schema with `records`, `outbox`, and `pulled_position` stores; expose `getRecord`, `putRecord`, `addToOutbox`, `getPending`, `markAcked`, `removePending`, `getPulledPosition`, `setPulledPosition`
    - _Requirements: 1.1, 1.2, 1.3_

  - [ ] 9.4 Implement `SyncClient.ts`: outbox pattern
    - `push()` → POST `/api/sync/push` with pending ops in creation order; mark each acknowledged opId as `acked: true` in the outbox; keep the locally applied field value unchanged until the matching `LogEntry` is seen in pull (no flicker)
    - `pull()` → GET `/api/sync/pull?since={pulledPosition}`; for each `LogEntry`: if opId matches an `acked` outbox entry, remove it from the outbox and apply the authoritative server value; apply all other entries normally; call `clock.observe()` for every entry HLC
    - On failure, leave unacknowledged ops in the outbox; retry with exponential backoff
    - Expose `sync()` = push then pull; `pendingCount()` for the UI indicator
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [ ] 9.5 Implement online/offline detection: listen to `window online/offline` events; when `online` fires, call `sync()` automatically; expose `isOnline()` for the UI indicator
    - _Requirements: 2.1, 1.4_

  - [ ]* 9.6 Write unit tests for `HlcClock.ts` and `SyncClient.ts` using Vitest
    - Test: `tick()` always returns a strictly greater HLC even when `Date.now()` goes backward
    - Test: `observe()` after a remote HLC means the next `tick()` sorts after the remote value
    - Test: push + pull happy path with a mock `fetch`; acked ops removed from outbox after matching `LogEntry` seen in pull
    - Test: push failure leaves ops in outbox; exponential backoff counter increments
    - _Requirements: 2.3, 2.4, 3.5_

- [ ] 10. Visit Ledger and Stock Ledger UI screens
  - [ ] 10.1 Scaffold `visit-ledger/` as a Vite + TypeScript PWA; install `skeleton/sync-client` as a local package; add `manifest.json` and service worker for offline use
    - Use only fictional demo data; no real patient or personal information anywhere in fixtures or comments
    - _Requirements: 10.1, 10.3, 1.1_

  - [ ] 10.2 Implement Visit Ledger screens: patient list, patient detail (all fields), add/edit patient form; `medicineDose` shows "disputed — both values" badge when `FieldState.candidates` is non-empty; pending op count and online/offline indicator visible in the header
    - _Requirements: 7.2, 1.4, 10.1_

  - [ ] 10.3 Implement resolution history drawer for Visit Ledger: `GET /api/records/patient/{id}/history` → show tier, both values, final value, explanation per resolution
    - _Requirements: 8.2_

  - [ ] 10.4 Implement the reviewer panel inside Visit Ledger: open review queue items (`GET /api/review`), display both candidate values + AI summary, submit decision (`POST /api/review/{id}/decide`); clear the disputed badge after the decision syncs back via pull
    - _Requirements: 7.2, 7.4_

  - [ ] 10.5 Scaffold `stock-ledger/` as a Vite + TypeScript PWA; implement item list, item detail (name, quantity, price, supplierNotes), add/edit item form with same disputed/indicator behaviour as Visit Ledger
    - _Requirements: 10.2, 10.3_

  - [ ]* 10.6 Write Vitest component tests for the disputed-field badge
    - Test: when `candidates` is non-empty the badge renders both candidate values
    - Test: when `candidates` is empty the badge does not render
    - _Requirements: 7.2_

- [ ] 11. Demo view (`demo/`)
  - [ ] 11.1 Create `demo/index.html` as a Vite + TypeScript single-page app. Instantiate two (or more) independent `SyncClient` instances from `skeleton/sync-client`, each with its own IndexedDB namespace (e.g. `demo-device-a`, `demo-device-b`), pointing at the real server (`skeleton/server`). Render each client as a panel side by side. Each panel has: a toggle button that connects or disconnects that client's sync loop (simulates going offline/online), and a clock-skew slider (±8 h) that biases that client's `HlcClock` by the chosen offset. Do NOT use the Java `Simulation` class or any server-side code in the demo — the browser cannot load JVM classes.
    - _Requirements: 11.1, 11.2, 11.5_

  - [ ] 11.2 Implement conflict animation: when simulated devices reconnect, animate each conflict through its tier — tier-1 merges highlight the rule applied, tier-2 AI merges show confidence and explanation, tier-3 critical conflicts show "disputed" with both values and AI summary
    - _Requirements: 11.3_

  - [ ] 11.3 Implement the scripted duplicate demo button: pre-populate both panels with the same patient using different spellings; reconnect; animate the duplicate candidate flow from phonetic match through AI judge to the reviewer decision panel
    - _Requirements: 11.4_

- [ ] 12. Evaluation set and README results
  - [ ] 12.1 Create `eval/` directory; write at least 30 YAML cases each containing a `FieldConflict` or `DuplicatePair`, the expected outcome (expected tier, expected final value, `critical: true` flag)
    - Cover: each tier-1 policy (counter, set-union, append-only, latest) × at least 2 cases; tier-2 AI with high and low confidence; tier-3 critical (100% must go to review); invariant-violation escalation; duplicate auto-merge; duplicate sent to review; duplicate "not the same"
    - Use only fictional data
    - _Requirements: 13.5_

  - [ ] 12.2 Implement `JudgeEvaluator`: reads all YAML cases from `eval/`, runs each against `GeminiJudgeProvider` (or `FakeJudgeProvider` when `--fake` flag), reports total cases, cases matching expected outcome, and count of critical cases correctly routed to review (must be 100%); writes results to `eval/results.md`
    - _Requirements: 13.5_

  - [ ] 12.3 Update `README.md` with: build/run instructions, latest jqwik statistics output (copy from CI), and `eval/results.md` content; add a note that full WCAG validation requires manual testing with assistive technologies
    - _Requirements: 13.6_

- [ ] 13. Final checkpoint — run `cd skeleton/server && ./mvnw test`; confirm all 10,000 scenarios pass, all eval cases produce expected outcomes, and both app UIs compile without errors
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- Each task references specific requirements for traceability
- The `SyncEngine` MUST remain pure Java with no I/O at every step; AI calls stay in `SyncService`
- `FakeJudgeProvider` is used for all automated tests; `GeminiJudgeProvider` is used only in the eval set and in production
- `REVIEW_DECISION` disposition and `review-decision` op kind are the ONLY mechanisms that clear a disputed field — a plain `SET` on a disputed field MUST add a candidate, not clear it
- Duplicate detection does NOT fire on `CREATE`; it fires when all identity fields are first fully populated, and again on each identity-field update
- Devices in the simulator and in the real client mark ops `acked` immediately after push acknowledgement, but keep the locally applied value until the matching `LogEntry` appears in pull (no UI flicker)

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["1.4", "2.1", "2.2", "2.3"] },
    { "id": 3, "tasks": ["2.4", "4.1"] },
    { "id": 4, "tasks": ["4.2", "4.4"] },
    { "id": 5, "tasks": ["4.3", "5.1"] },
    { "id": 6, "tasks": ["5.2", "5.3", "5.4", "5.5"] },
    { "id": 7, "tasks": ["5.6", "5.7", "5.8", "5.9"] },
    { "id": 8, "tasks": ["7.1"] },
    { "id": 9, "tasks": ["7.2", "7.3", "7.5"] },
    { "id": 10, "tasks": ["7.4", "7.6", "9.1"] },
    { "id": 11, "tasks": ["9.2", "9.3"] },
    { "id": 12, "tasks": ["9.4", "9.5"] },
    { "id": 13, "tasks": ["9.6", "10.1"] },
    { "id": 14, "tasks": ["10.2", "10.3", "10.4", "10.5"] },
    { "id": 15, "tasks": ["10.6", "11.1"] },
    { "id": 16, "tasks": ["11.2", "11.3"] },
    { "id": 17, "tasks": ["12.1"] },
    { "id": 18, "tasks": ["12.2"] },
    { "id": 19, "tasks": ["12.3"] }
  ]
}
```
