## Inspiration

Community health workers in rural India often work for hours without signal. Two of them can visit the same patient on the same day, both offline. One writes the dose as 5 mg, the other as 10 mg. When their phones finally sync, today's tools quietly pick a winner, usually whichever edit has the later timestamp.

Offline sync engines are good at one thing: making every copy of the data **identical**. They don't check whether that copy is **correct**. A patient record that converges to the wrong dose is still wrong.

There's a second, quieter problem. Two workers each register the same patient offline: "Ramesh Kumar, 45, Rampur" and "Ramesh Kumaar, 46, Rampur". Now one person has two half-histories, and no sync engine notices.

We wanted to build the missing layer: a sync skeleton that knows which copy is real.

## What it does

**Doppelgänger** is an offline-first sync skeleton for field apps. Devices work fully offline. When they reconnect, every conflicting copy of a record (a "doppelgänger") goes through a three-tier **Conflict Judge**:

| Tier | Handles | Decision |
|---|---|---|
| 1. Rules | Counters, sets, notes, simple fields | Deterministic merge from the domain spec |
| 2. AI judge | Free-text and judgement fields | Proposed value + confidence + explanation |
| 3. Human review | Critical fields (dose, price), low-confidence AI, delete-vs-edit | Never auto-merged; flagged on every device |

For a counter like shop stock, two offline sales from the same starting value are combined rather than overwritten:

$$ v_{\text{merged}} = b + (a - b) + (c - b) $$

where \\( b \\) is the base value both devices started from and \\( a, c \\) are their edited values. Base 10, one device sells 2, the other sells 3: the result is 5, not 8 or 7.

It also catches **duplicate records**: phonetic name matching tuned for Indian name spellings, blocking to avoid comparing everything with everything, an AI judge for uncertain pairs, and human review whenever a critical field differs. Every merge is explained, logged and reversible.

The skeleton is domain-agnostic. Each app is just a YAML domain spec plus its screens. Two apps run on it:

- **Visit Ledger**: patient visits for health workers.
- **Stock Ledger**: inventory for a small village shop.

## How we built it

- **Spec-driven with Kiro.** We started from requirements written in Kiro's spec format (13 requirements covering sync, the three tiers, duplicates and correctness), then used Kiro to generate and refine the design and task list.
- **Steering files** encode the project's safety rules (never auto-merge a critical field, order by hybrid logical clocks, deletes are tombstones), so every generated change follows them.
- **Hooks:** _[TODO: describe the hooks you actually built, e.g. "when a domain spec YAML changes, a hook regenerates the validators and re-runs the property tests"]_
- **Backend:** Java 21 + Spring Boot. The server keeps an append-only operation log as the single source of truth. Every resolution, including AI decisions, is written back as a new operation, so all devices converge without calling the AI twice.
- **Ordering:** hybrid logical clocks, because phone clocks can't be trusted.
- **Client:** a TypeScript PWA storing records and an outbox in IndexedDB.
- **AI:** Gemini behind a swappable `AiJudgeProvider` interface, returning structured JSON only. A deterministic fake provider is used in tests.

## Proving it works

We didn't want a demo that only works on the happy path.

- **Property-based tests (jqwik):** _[TODO: N]_ randomly generated scenarios, each with 3+ simulated devices making random edits, deletes, clock skew, duplicate registrations, interrupted syncs and repeated syncs. Properties checked: convergence, no data loss, idempotency, and "a conflicting critical field is never auto-changed". Result: _[TODO: e.g. "0 violations"]_.
- **Judge evaluation set:** _[TODO: N]_ hand-written conflict and duplicate cases. The judge resolved _[TODO: X of N]_ as expected, and _[TODO: all K]_ critical cases went to human review.

## Challenges we ran into

_[TODO: write from real experience after building. Likely candidates, keep only the ones that actually happened:]_
- _Getting three-way merge right when the same device syncs twice or a sync drops halfway._
- _Making AI decisions safe in a system that must converge (AI answers aren't deterministic)._
- _Phonetic matching for Indian names written in English letters._
- _A bug the property tests found that manual testing never would have: [describe it]._

## Accomplishments that we're proud of

_[TODO: e.g. the property-test result, the first time the dose conflict was correctly flagged, two apps on one skeleton]_

## What we learned

_[TODO: real lessons, e.g. why convergence is not correctness, property-based testing and shrinking, hybrid logical clocks, spec-driven development with Kiro]_

## What's next

- Fine-tune a small model on real reviewer decisions once enough exist.
- Device-to-device sync for areas with no connectivity at all.
- An SMS fallback for pushing critical changes.
- Pilot conversations with organisations that run community health worker programmes.
