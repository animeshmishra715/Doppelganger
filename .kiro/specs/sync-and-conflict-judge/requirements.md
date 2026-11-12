# Requirements Document

## Introduction

Doppelgänger is an offline-first sync skeleton for field apps used where internet is unreliable (rural health workers, small shops). Devices keep working fully offline. When they reconnect, copies of the same record that were edited separately ("doppelgängers") are reconciled.

Existing sync engines resolve conflicts mechanically (last-write-wins, CRDT merge). They guarantee every copy ends up identical, not that the result is correct. Doppelgänger adds a **Conflict Judge**: deterministic rules handle simple conflicts, an AI handles ambiguous ones, and safety-critical fields are never auto-merged but escalated to a human with an explanation.

Doppelgänger also catches a second kind of doppelgänger that sync engines ignore: **duplicate records**. When two workers each register the same person or item offline under slightly different spellings, the system detects the likely duplicate, decides clear cases with rules, uses the AI for uncertain ones, and sends risky merges to a human.

The skeleton is domain-agnostic. Each app declares its data model and merge rules in a **domain spec** file. Two demo apps prove this: **Visit Ledger** (health worker patient visits) and **Stock Ledger** (village shop inventory).

## Glossary

- **Device:** A browser client running an app built on the skeleton.
- **Sync Server:** The Spring Boot backend that stores the authoritative state.
- **Operation:** A single recorded change to one field of one record, with device ID, timestamp, and base version.
- **Base version:** The last server version of a record that the device had seen before editing (the common ancestor).
- **Conflict:** Two or more devices changed the same field of the same record from the same base version to different values.
- **Domain spec:** A YAML file declaring an app's record types, fields, field merge policies, and invariants.
- **Merge policy:** How a field resolves conflicts: `counter`, `set-union`, `append-only`, `latest`, `ai-judge`, or `critical`.
- **Review queue:** The list of conflicts waiting for a human decision.
- **Resolution record:** An audit entry describing how a conflict was resolved, by which tier, and why.
- **Hybrid logical clock (HLC):** A timestamp combining physical time and a logical counter, used to order Operations correctly even when device clocks are wrong.
- **Tombstone:** A marker recording that a record was deleted, kept so that deletions sync correctly instead of records reappearing.
- **Duplicate candidate:** A pair of records of the same type that may describe the same real-world person or item.
- **Match score:** A number from 0 to 1 estimating how likely a Duplicate candidate pair is the same entity.
- **Identity fields:** The fields a domain spec marks as used for duplicate detection (for example name, age, village).

## Requirements

### Requirement 1: Offline-first local storage

**User Story:** As a field worker, I want the app to work fully without internet, so that I can record data wherever I am.

#### Acceptance Criteria

1. WHEN the device has no network connection THE SYSTEM SHALL allow creating, reading, updating, and deleting records using local storage (IndexedDB).
2. WHEN a user changes a record THE SYSTEM SHALL save the change locally before any network call and record it as an Operation in a local outbox.
3. WHEN the app is closed and reopened offline THE SYSTEM SHALL restore all local records and pending Operations without loss.
4. THE SYSTEM SHALL display a clear online/offline indicator and the number of pending Operations.

### Requirement 2: Sync on reconnect

**User Story:** As a field worker, I want my changes to upload automatically when I get signal, so that I don't have to remember to sync.

#### Acceptance Criteria

1. WHEN the device regains connectivity THE SYSTEM SHALL push pending Operations to the Sync Server in the order they were made.
2. WHEN the push succeeds THE SYSTEM SHALL pull all server changes made since the device's last sync and apply them locally.
3. IF a sync attempt fails midway THEN THE SYSTEM SHALL keep unacknowledged Operations in the outbox and retry with exponential backoff.
4. WHEN the same Operation is received by the Sync Server more than once THE SYSTEM SHALL apply it only once (idempotent sync).
5. THE SYSTEM SHALL provide a manual "Sync now" action.

### Requirement 3: Field-level conflict detection

**User Story:** As a developer, I want conflicts detected per field using a three-way comparison, so that unrelated edits to the same record never count as conflicts.

#### Acceptance Criteria

1. WHEN an incoming Operation changes a field THE SYSTEM SHALL compare the base version, the current server value, and the incoming value for that field.
2. IF only one side changed the field since the base version THEN THE SYSTEM SHALL apply the change without raising a Conflict.
3. IF both sides changed the same field to the same value THEN THE SYSTEM SHALL treat it as resolved with no Conflict, except that for `counter` fields the same final value does not imply no conflict — the deltas are always combined regardless of whether the resulting values happen to be equal.
4. IF both sides changed the same field to different values THEN THE SYSTEM SHALL raise a Conflict and pass it to the Conflict Judge.
5. THE SYSTEM SHALL timestamp every Operation with a hybrid logical clock and SHALL NOT rely on device wall-clock time alone for ordering.
6. WHEN a device's clock is ahead of or behind the server by any amount THE SYSTEM SHALL still order Operations consistently on every device.
7. WHEN a record is deleted THE SYSTEM SHALL keep a Tombstone rather than removing it, so the deletion reaches every device.
8. IF one device deletes a record while another device edits it offline THEN THE SYSTEM SHALL keep the record with the edits applied, mark it as "deleted elsewhere", and add it to the review queue so a human confirms the deletion or keeps the record.

### Requirement 4: Domain spec drives merge behaviour

**User Story:** As a developer building a new app on the skeleton, I want to declare merge rules in a spec file, so that I don't write conflict code by hand.

#### Acceptance Criteria

1. THE SYSTEM SHALL load each app's record types, fields, merge policies, and invariants from a domain spec YAML file.
2. WHEN the domain spec is invalid (unknown policy, missing field type, duplicate names) THE SYSTEM SHALL refuse to start and report the exact problem.
3. THE SYSTEM SHALL support the merge policies `counter`, `set-union`, `append-only`, `latest`, `ai-judge`, and `critical`.
4. THE SYSTEM SHALL support invariants, expressed as simple rules over fields (for example "stock quantity >= 0"), that are checked after every merge.

### Requirement 5: Tier 1, deterministic rule resolution

**User Story:** As a shop owner, I want simple conflicts like two stock sales resolved correctly without anyone's help, so that counts stay right.

#### Acceptance Criteria

1. WHEN a Conflict is on a `counter` field THE SYSTEM SHALL combine both sides' deltas from the base version (for example base 10, device A sells 2, device B sells 3, result 5).
2. WHEN a Conflict is on a `set-union` field THE SYSTEM SHALL keep the union of both sides' additions and remove only items both sides removed.
3. WHEN a Conflict is on an `append-only` field THE SYSTEM SHALL keep both entries, ordered by timestamp.
4. WHEN a Conflict is on a `latest` field THE SYSTEM SHALL keep the value with the later hybrid logical clock timestamp, breaking ties by device ID.
5. WHEN a Tier 1 resolution violates an invariant THE SYSTEM SHALL escalate the Conflict to the review queue instead of applying it.

### Requirement 6: Tier 2, AI judge for ambiguous conflicts

**User Story:** As a field worker, I want conflicts in free-text or judgement fields merged sensibly, so that neither person's information is lost.

#### Acceptance Criteria

1. WHEN a Conflict is on an `ai-judge` field THE SYSTEM SHALL send the AI the base value, both conflicting values, field description, and timestamps, and no unrelated personal data.
2. THE SYSTEM SHALL require the AI to return structured output: proposed value, a confidence score from 0 to 1, and a plain-language explanation.
3. IF the AI's confidence is below the configured threshold THEN THE SYSTEM SHALL send the Conflict to the review queue with the AI's proposal attached.
4. IF the AI is unavailable or returns invalid output THEN THE SYSTEM SHALL send the Conflict to the review queue and continue syncing other records.
5. WHEN the AI's resolution is accepted THE SYSTEM SHALL store it as a new server Operation, so that every device receives the same result (convergence does not depend on the AI being called more than once).

### Requirement 7: Tier 3, critical fields are never auto-merged

**User Story:** As a health supervisor, I want conflicting medicine doses flagged rather than silently resolved, so that no patient gets the wrong dose.

#### Acceptance Criteria

1. WHEN a Conflict is on a `critical` field THE SYSTEM SHALL NOT apply either value automatically.
2. WHEN a critical Conflict is raised THE SYSTEM SHALL keep the record usable, mark the field as "disputed" on every device, and show both values with who recorded them and when.
3. THE SYSTEM SHALL add the Conflict to the review queue with an AI-written summary explaining the disagreement and what to check.
4. WHEN a reviewer picks or enters a value THE SYSTEM SHALL apply it as a new Operation and clear the disputed state on all devices after their next sync.

### Requirement 8: Explanations and audit trail

**User Story:** As a supervisor, I want to see why every conflict was resolved the way it was, so that I can trust the system.

#### Acceptance Criteria

1. WHEN any Conflict is resolved THE SYSTEM SHALL create a Resolution record containing the field, both values, the base value, the tier that resolved it, the final value, and the explanation.
2. THE SYSTEM SHALL show the Resolution history for each record in the app.
3. THE SYSTEM SHALL never delete Resolution records.

### Requirement 9: Correctness guarantees

**User Story:** As a developer, I want the sync engine proven against data loss, so that I can trust it in the field.

#### Acceptance Criteria

1. THE SYSTEM SHALL guarantee that after all devices sync with no new edits, every device holds identical data (convergence).
2. THE SYSTEM SHALL guarantee that no Operation acknowledged by the server is lost.
3. THE SYSTEM SHALL guarantee that applying the same batch of Operations twice gives the same result as applying it once.
4. THE SYSTEM SHALL include property-based tests that generate random offline edit sequences across multiple simulated devices and check guarantees 1 to 3.

### Requirement 10: Two demo apps on one skeleton

**User Story:** As a judge, I want to see two different apps built on the same skeleton, so that I can see it is reusable.

#### Acceptance Criteria

1. THE SYSTEM SHALL include **Visit Ledger**, whose domain spec has a patient record with: name (`latest`), visit notes (`append-only`), symptoms (`set-union`), medicine dose (`critical`), and follow-up advice (`ai-judge`), and Identity fields name (`phonetic`), age (`numeric-tolerance`), and village (`exact`).
2. THE SYSTEM SHALL include **Stock Ledger**, whose domain spec has an item record with: name (`latest`), quantity (`counter`, invariant quantity >= 0), price (`critical`), and supplier notes (`ai-judge`), and Identity fields name (`ai-judge`, so "Parle-G 100g" and "parle g biscuit small" can match) and unit size (`exact`).
3. THE SYSTEM SHALL keep app-specific code limited to the domain spec and UI screens; sync and conflict logic SHALL be shared.
4. THE SYSTEM SHALL use only fictional demo data, with no real patient or personal information.

### Requirement 11: Demo simulation mode

**User Story:** As a presenter, I want to simulate several offline devices on one screen, so that I can show a conflict being created and resolved in under a minute.

#### Acceptance Criteria

1. THE SYSTEM SHALL provide a demo view showing two or more simulated devices side by side, each with its own local storage.
2. THE SYSTEM SHALL let the presenter toggle each simulated device offline or online.
3. WHEN simulated devices reconnect THE SYSTEM SHALL animate each Conflict through its tier (rule, AI, or human review) and show the explanation.
4. THE SYSTEM SHALL include a scripted demo scenario in which two simulated devices register the same patient offline with different spellings, and show the Duplicate candidate being detected and resolved.
5. THE SYSTEM SHALL let the presenter set a simulated device's clock wrong by hours and show that Operations are still ordered correctly.

### Requirement 12: Duplicate record detection and merging

**User Story:** As a health supervisor, I want to know when two workers have registered the same patient separately, so that each patient has one complete history instead of two partial ones.

#### Acceptance Criteria

1. THE SYSTEM SHALL let each record type in the domain spec mark Identity fields and give each a match method: `exact`, `phonetic`, `numeric-tolerance` (for example age within 2 years), or `ai-judge`.
2. WHEN new records arrive at the Sync Server THE SYSTEM SHALL compare them against existing records of the same type and create Duplicate candidates with a Match score.
3. THE SYSTEM SHALL compare names phonetically so that common spelling variants of Indian names in English letters match (for example "Ramesh Kumar" and "Ramesh Kumaar", "Mohd" and "Mohammad").
4. THE SYSTEM SHALL limit comparisons using blocking (for example only comparing records from the same village or with similar name keys) so that detection does not compare every record with every other record.
5. WHEN the Match score is at or above the configured auto-merge threshold and all Identity fields agree within their match methods THE SYSTEM SHALL merge the pair automatically, subject to criterion 8.
6. WHEN the Match score is between the configured review threshold and the auto-merge threshold THE SYSTEM SHALL ask the AI judge whether the records describe the same entity, requiring a yes/no answer, a confidence score, and an explanation; low-confidence answers SHALL go to the review queue.
7. WHEN the Match score is below the review threshold THE SYSTEM SHALL treat the records as different and create no Duplicate candidate.
8. IF a record type has any `critical` field whose values differ between the two records THEN THE SYSTEM SHALL NOT merge automatically and SHALL send the pair to the review queue.
9. WHEN a merge is approved THE SYSTEM SHALL combine the two records into one, merge their fields using each field's merge policy, keep both original record IDs as aliases so later Operations from any device still find the record, and create a Resolution record.
10. WHEN a reviewer marks a pair as "not the same" THE SYSTEM SHALL remember that decision and not suggest the same pair again.
11. THE SYSTEM SHALL let a reviewer undo a merge, restoring both original records and their histories.

### Requirement 13: Visible proof of correctness

**User Story:** As a judge, I want to see evidence that the sync engine is correct, so that I can tell this is more than a demo that works on the happy path.

#### Acceptance Criteria

1. THE SYSTEM SHALL include a property-based test suite that simulates at least 3 devices making random offline edits, deletions, clock skew, duplicate registrations, interrupted syncs, and repeated syncs.
2. THE SYSTEM SHALL run at least 10,000 generated scenarios in the full test run.
3. WHEN the test suite finishes THE SYSTEM SHALL produce a report showing the number of scenarios run, Operations applied, Conflicts raised by tier, and any violations of convergence, no-data-loss, or idempotency.
4. WHEN a property fails THE SYSTEM SHALL output the smallest reproducing scenario (shrinking) so the bug can be fixed.
5. THE SYSTEM SHALL include an evaluation set of at least 30 hand-written conflict and duplicate cases with expected outcomes, and report how many the Conflict Judge handles correctly, with a separate count proving every `critical` case went to review.
6. THE SYSTEM SHALL publish the latest test and evaluation results in the README.
