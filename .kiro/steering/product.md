# Product steering: Doppelgänger

## What this is
An offline-first sync skeleton (Kiroween 2026, Skeleton Crew category) for field apps in low-connectivity areas. Its unique part is the three-tier Conflict Judge:
1. Deterministic rules from the domain spec (counter, set-union, append-only, latest).
2. AI judge for ambiguous fields, with confidence and explanation.
3. Critical fields are never auto-merged; they go to human review.

It also detects duplicate records: the same patient or item registered separately on two offline devices under different spellings (phonetic matching, blocking, AI judge for uncertain pairs, human review for risky merges, reversible merges).

Core message: "CRDTs make every copy identical. Doppelgänger makes sure the result is also correct."

## Depth must be visible
The project must not look like a form app with an AI pop-up. Always keep these visible in the demo, README and video:
- Property-based test report (10,000+ random multi-device scenarios, zero violations).
- Judge evaluation score on the hand-written case set, with every critical case escalated.
- Clock-skew and delete-versus-edit handling.
- Duplicate patient detection.

## Users
- Rural health workers recording patient visits offline (Visit Ledger demo).
- Village shop owners and family members updating stock offline (Stock Ledger demo).
- Developers who want to build their own offline app on the skeleton.

## Principles
- Safety over automation: when unsure, escalate to a human; never silently pick a value for a critical field.
- Every resolution is explained and logged.
- Convergence must not depend on calling the AI more than once: an AI decision is stored as a new server operation.
- Order operations with hybrid logical clocks, never device wall-clock time alone.
- Deletes are tombstones. Delete-versus-edit conflicts go to human review.
- Duplicate merges keep the old record IDs as aliases and can always be undone.
- Shared skeleton code is domain-agnostic; app-specific behaviour lives only in the domain spec YAML and UI screens.
- Only fictional demo data. Never send unnecessary personal data to the AI.

## Tech stack
- Backend: Java 21, Spring Boot 3, PostgreSQL (H2 for local dev), Maven.
- Frontend: plain TypeScript web app (PWA) with IndexedDB for local storage.
- AI: pluggable provider interface; Gemini as the default implementation. Structured JSON output only.
- Tests: JUnit 5 and jqwik for property-based tests.
