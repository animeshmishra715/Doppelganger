# CLAUDE.md: Doppelgänger (Kiroween 2026)

Context for any Claude Code session working in this repo. Read this first, then the files it points to.

## 1. What this is

**Doppelgänger: Offline Sync That Knows Which Copy Is Real.**
An offline-first sync skeleton for field apps (rural health workers, village shops). Devices work fully offline. When they sync, conflicting copies of a record ("doppelgängers") go through a three-tier **Conflict Judge**, and duplicate records registered separately on two devices are detected and merged safely.

Pitch line: *"CRDTs make every copy identical. Doppelgänger makes sure it's also correct: offline sync where an AI judge resolves conflicts and never guesses a medicine dose."*

Owner: Animesh Mishra (solo), 3rd-year CSE student, strong in Java, DSA and backend.

## 2. Hackathon facts (Kiroween 2026, Devpost)

- **Deadline:** Dec 4, 2026, 2:00 PM PT = **Dec 5, 3:30 AM IST.** No phases, rounds or check-ins; one final submission. Drafts can be saved anytime.
- **Category:** Skeleton Crew (a flexible template with two distinct apps). Entered as such on Devpost.
- **Must be a new project**, started on/after Oct 30, 2026. Disclose any pre-existing code (libraries/frameworks are fine).
- **Must use Kiro** (IDE, CLI, Web or Crew). Judges are Kiro people and reward deep, visible Kiro use (specs, hooks, steering, property-based testing). Last year's grand prize was a template that used `.kiro/hooks` heavily.
- **Prizes of interest:** Overall ($30k/$20k/$10k), Best Skeleton Crew ($5k), Best Student Project ($1.5k per category), Best Demo, Bonus Blog Prize (100 winners), Social Blitz.
- **Kiro credits:** request via the Devpost Resources tab; official rules say by **Dec 1, 12:00 PM PT.**

### Submission requirements (all mandatory)
- [ ] Public **GitHub** repo (not GitLab/Bitbucket) with all source, assets and run instructions.
- [ ] Open-source **license visible in the repo's About section.**
- [ ] **`/.kiro` directory at the repo root. Never add it to `.gitignore`** (can disqualify).
- [ ] **Two separate app folders** (Skeleton Crew rule): `visit-ledger/` and `stock-ledger/`.
- [ ] URL to a **working deployed app** (login details go in the private Devpost field if needed).
- [ ] Text description of features (see `docs/DEVPOST_STORY.md`).
- [ ] **Demo video under 3 minutes**, public on YouTube, showing it working. No unlicensed music or trademarks.
- [ ] **Write-up of how Kiro was used** (specs, hooks, steering, etc.).
- [ ] Optional bonus links: Social Blitz post (#KiroweenHackathon26 at the start), blog post written for Kiroween 2026.

### Honesty rules for the submission
- The Kiro write-up must describe what was really done in Kiro. Do the spec flow (requirements → design → tasks), hooks and steering **in Kiro itself** so this is true. Claude Code can help, but don't let it replace the Kiro workflow the judges are scoring.
- Story sections (challenges, learnings, numbers) must come from the real build. `docs/DEVPOST_STORY.md` has `[TODO]` markers for these; never fill them with invented content.
- Use only fictional demo data. No real patient data anywhere.

## 3. Source-of-truth files

| File | Purpose |
|---|---|
| `.kiro/specs/sync-and-conflict-judge/requirements.md` | 13 requirements in Kiro/EARS format. The spec. |
| `.kiro/steering/product.md` | Product purpose, safety principles, tech stack. |
| `docs/ARCHITECTURE.md` | Full architecture: op log, flow, judge tiers, duplicates, YAML spec, API, tables, tests, folders. |
| `docs/DEVPOST_STORY.md` | Devpost "About the project" draft with `[TODO]` gaps. |

If anything here conflicts with those files, the files win; update this one.

## 4. Key design decisions (don't change without a reason)

1. **Server-authoritative append-only operation log** in PostgreSQL is the single source of truth. Devices push operations and pull "everything after log position N". No device-to-device sync.
2. **Operation** = one field of one record changed: `opId` (unique, makes resend idempotent), `deviceId`, `recordType`, `recordId`, `field`, `kind` (set/create/delete), `value`, `baseVersion`, `hlc`.
3. **Three-way compare** per field (base, server, incoming). Only both-sides-changed-differently is a conflict.
4. **Hybrid logical clocks** for ordering. Never trust device wall-clock time alone.
5. **Deletes are tombstones.** Delete-vs-edit goes to human review; record kept with the edit, marked "deleted elsewhere".
6. **Conflict Judge tiers:**
   - Tier 1, rules: `counter` (combine deltas: base 10, sales of 2 and 3 → 5), `set-union`, `append-only` (both kept, HLC order), `latest` (higher HLC wins, tie by device ID). Invariants checked after merge; violation → review.
   - Tier 2, AI: `ai-judge` fields. Structured JSON: value, confidence 0–1, explanation. Low confidence or invalid output → review.
   - Tier 3, human: `critical` fields (medicine dose, price) are **never auto-merged**; field shown as "disputed" on all devices.
7. **Every resolution, including AI decisions, is written back as a new operation** → convergence never depends on calling the AI more than once. Every resolution gets an audit record (never deleted).
8. **AI behind an interface** `AiJudgeProvider` (`resolveField`, `judgeDuplicate`). `GeminiJudgeProvider` for real use; deterministic `FakeJudgeProvider` for tests. Send the AI only the fields it needs.
9. **Duplicate detection:** identity fields + match methods from the YAML (`exact`, `phonetic` tuned for Indian names in English letters, `numeric-tolerance`, `ai-judge`), **blocking** to avoid all-pairs comparison, match score with three bands (auto-merge / ask AI / ignore). A differing critical field always forces review. Merges keep old IDs in `record_aliases`, are reversible, and "not the same" decisions are remembered.
10. **Domain-agnostic skeleton:** each app = a YAML domain spec + screens. Sync/judge/duplicate logic is shared and lives only in `skeleton/`.
11. **No custom model training.** Use Gemini + an evaluation set + reviewer decisions as few-shot examples. Fine-tuning is a post-hackathon idea.

## 5. Tech stack

- Backend: Java 21, Spring Boot 3, Maven. PostgreSQL (H2 for local dev).
- Client: TypeScript PWA, IndexedDB.
- AI: Gemini via `AiJudgeProvider`.
- Tests: JUnit 5 + **jqwik** (property-based testing).
- Deploy: free host such as Render.
- License: choose MIT or GPL-3.0 when creating the repo.

## 6. Repo layout (Skeleton Crew: two app folders required)

```
doppelganger/
├── CLAUDE.md
├── .kiro/                 specs, steering, hooks (commit it!)
├── skeleton/
│   ├── server/            shared Spring Boot: sync API, judge, duplicates, simulator
│   └── sync-client/       shared TS sync library: IndexedDB, outbox, push/pull, HLC
├── visit-ledger/          app 1: YAML domain spec + screens
├── stock-ledger/          app 2: YAML domain spec + screens
├── demo/                  side-by-side simulated devices view
├── eval/                  hand-written judge cases (30+)
└── docs/                  ARCHITECTURE.md, DEVPOST_STORY.md
```

## 7. Domain specs

**Visit Ledger** (patient): name `latest`, visitNotes `append-only`, symptoms `set-union`, medicineDose `critical`, followUp `ai-judge`. Identity: name `phonetic`, age `numeric-tolerance` (±2), village `exact`. Blocking: village + phonetic name key.

**Stock Ledger** (item): name `latest`, quantity `counter` (invariant `quantity >= 0`), price `critical`, supplierNotes `ai-judge`. Identity: name `ai-judge` ("Parle-G 100g" ≈ "parle g biscuit small"), unitSize `exact`.

Example YAML is in `docs/ARCHITECTURE.md` section 6.

## 8. Build plan and status

Work in this order. Tick boxes as you go and keep this section current.

### Setup (now)
- [ ] Request Kiro credits (Devpost Resources tab).
- [ ] Create public GitHub repo `doppelganger` with license; push `.kiro/`, `docs/`, `CLAUDE.md`.
- [ ] In Kiro: load the requirements spec, generate **design.md** and **tasks.md**; compare with `docs/ARCHITECTURE.md` and note differences and decisions.
- [x] Devpost draft started: name, pitch (option B), category Skeleton Crew, story draft pasted.

### Phase 1: core sync engine (days 1–5)
Goal: two devices edit offline, sync, and end up identical and correct for simple fields. No AI, no UI polish.
- [x] Spring Boot project in `skeleton/server` with H2.
- [x] Operation model + append-only `operations` table.
- [x] `POST /api/sync/push` (idempotent by opId), `GET /api/sync/pull?since=N`.
- [x] Three-way compare; tier-1 rules (counter, set-union, append-only, latest).
- [x] HLC implementation; tombstones; delete-vs-edit → review.
- [x] Simulator: in-memory fake devices + fake network (drop halfway, deliver twice), using the real merge engine.
- [x] jqwik properties: convergence, no data loss, idempotency.
- **Done when:** stock example (10, sales of 2 and 3) gives 5 and thousands of random scenarios pass. **Met on 13 Nov 2026** (31 tests; 4 properties × 2,500 scenarios, 3 devices).

Phase 1 was written with Claude Code, before the Kiro design/tasks step. Say so in the Kiro write-up.

#### How Phase 1 is built (read before changing `skeleton/server`)
- Run tests: `cd skeleton/server` then `.\mvnw.cmd test` (Maven wrapper; no Maven install needed). Run server: `.\mvnw.cmd spring-boot:run`. The machine has JDK 25; the build targets Java 21.
- `core/SyncEngine` is plain Java (no Spring). It judges each pushed op **once** and writes the decision into the log as a `LogEntry` = op + `disposition` (`APPLIED`, `MERGED`, `DISPUTED`, `NO_CHANGE`, `REJECTED`) + `note` (the explanation). A rule merge is the device op as `MERGED` followed by a `set` op from device `server` holding the result.
- `core/Replica` / `RecordState` is the fold that turns log entries into state. Server and devices run the same fold and it never calls the judge, so convergence is by construction. The TypeScript client must implement this same fold.
- `baseVersion` = log position of the last entry that touched the record when the device edited. The base **value** for three-way compare is the device's own previous unsynced edit of that field if there is one, otherwise the server value at `baseVersion` (`SyncEngine.baseValue`).
- Disputed field: value stays as it was, incoming values are kept as `candidates`; any later edit of a disputed field also becomes a candidate until a reviewer decides (Phase 3). `openReviews()` is derived from state.
- Delete-vs-edit: edit first → the delete is `DISPUTED`; delete first → a `create` op from `server` restores the record. Both leave `deleteDispute` set ("deleted elsewhere").
- `server/SyncService` rebuilds the engine by replaying the `operations` table on startup. There is no `records` table yet; state is held in memory.
- `server/BuiltInSpecs` hard-codes the two record types. Temporary: Phase 2's YAML loader replaces it.
- `sim/` has `SimDevice` and `Simulation` (7 network fault modes). `SyncPropertiesTest` holds the jqwik properties.

#### Open decisions from Phase 1 (owner to decide, then update the spec in Kiro)
1. **Requirement 3.3 vs counters.** The spec says "both sides changed to the same value → no conflict". For a counter that loses a sale (10 → 8 on both devices is two sales of 2, result must be 6). The engine combines deltas for counters even when the values match. Requirement 3.3 needs a counter exception.
2. **Set-union removals.** Per requirement 5.2 an item is removed only if both sides removed it, so a removal made on one device is undone when it conflicts.
3. **"Never apply either value" for critical fields (7.1).** The first dose to reach the server arrives with no conflict and is applied; the second is held as disputed. The server cannot know a conflict is coming. Devices show the field as disputed with both values.
4. **HLC limits.** HLC guarantees the same order on every device and that an edit sorts after everything its device had seen. It cannot tell which of two truly concurrent edits was later if one clock is wrong.
5. **jqwik 1.10.2 prints a notice that AI agents must not use the library.** Decide whether to keep it, pin 1.9.x, or switch library (see chat of 13 Nov).

### Phase 2: domain specs + apps
- [ ] YAML domain spec loader with validation (refuse to start on invalid spec, clear errors).
- [ ] Invariant checking.
- [ ] `skeleton/sync-client` (IndexedDB, outbox, retry with backoff, online/offline indicator, pending count, "Sync now").
- [ ] Basic screens for `visit-ledger/` and `stock-ledger/`.

### Phase 3: AI judge + human review
- [ ] `AiJudgeProvider`, `GeminiJudgeProvider`, `FakeJudgeProvider`.
- [ ] Tier 2 with confidence threshold; failure/invalid output → review.
- [ ] Tier 3 review queue, disputed state on devices, reviewer decision as new op.
- [ ] Resolution audit trail + per-record history view.
- [ ] Safety property test: conflicting critical field never auto-changed.

### Phase 4: duplicate detection
- [ ] Phonetic matcher for Indian names, numeric tolerance, exact.
- [ ] Blocking keys; match score; three bands; AI for middle band.
- [ ] Merge with aliases; undo; remembered "not the same".
- [ ] Add duplicate registrations to the property tests.
- **Cut plan if short on time:** phonetic matching + human review only; skip AI band and undo.

### Phase 5: demo, proof, submission
- [ ] `demo/` view: 2+ simulated devices side by side, offline/online toggles, set-clock-wrong button, animated conflicts through tiers, scripted duplicate-patient scene.
- [ ] `eval/` set of 30+ cases; report judge score and "all critical cases escalated".
- [ ] Full test run of 10,000+ scenarios with a report; publish results in README.
- [ ] README: what it is, how to run, architecture link, results, Kiro usage.
- [ ] Deploy; working URL.
- [ ] Demo video (<3 min). Suggested arc: problem (two health workers, wrong dose) → two phones offline → sync → rule merge, AI merge with explanation, red disputed dose → duplicate patient caught → test report → Kiro specs/hooks shown.
- [ ] Fill `[TODO]`s in `docs/DEVPOST_STORY.md` from real experience; Kiro write-up; screenshots (3:2).
- [ ] Optional: Social Blitz post; blog post (e.g. "how property-based tests found bugs in my sync engine").
- [ ] Submit before **Dec 5, 3:30 AM IST.** Aim for Dec 3 to leave buffer.

## 9. Kiro usage to build (for scoring and the write-up)
- Specs: requirements → design → tasks, refined in Kiro.
- Steering: `product.md` (exists); add `tech.md` and `structure.md`.
- Hooks (ideas): on change to a domain spec YAML → regenerate/validate and re-run property tests; on new judge case in `eval/` → re-run evaluation; on save in `skeleton/server` merge code → run property tests.
- Property-based testing via jqwik, tied to spec requirements 9 and 13.

## 10. Scope guard
Don't add: QR/device-to-device transfer, voice input, more demo apps, SMS fallback. Depth in sync, conflicts, duplicates and proof beats breadth.
