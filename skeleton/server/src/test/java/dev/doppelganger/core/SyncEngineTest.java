package dev.doppelganger.core;

import dev.doppelganger.core.FieldState.Note;
import dev.doppelganger.server.BuiltInSpecs;
import dev.doppelganger.sim.SimDevice;
import dev.doppelganger.sim.Simulation;
import dev.doppelganger.sim.Simulation.Fault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hand-written scenarios, one per rule. All data is fictional. */
class SyncEngineTest {

    private final DomainSpec spec = new DomainSpec(BuiltInSpecs.patient(), BuiltInSpecs.item());
    private Simulation sim;
    private SimDevice a;
    private SimDevice b;

    @BeforeEach
    void setUp() {
        sim = new Simulation(spec);
        a = sim.addDevice("device-a");
        b = sim.addDevice("device-b");
    }

    private RecordKey sharedItem(long quantity) {
        RecordKey item = a.create("item");
        a.set(item, "name", "Biscuit packet");
        a.set(item, "quantity", quantity);
        a.set(item, "price", 10);
        sim.syncAll();
        return item;
    }

    private RecordKey sharedPatient() {
        RecordKey patient = a.create("patient");
        a.set(patient, "name", "Asha Devi");
        a.set(patient, "medicineDose", "5 mg");
        a.addToSet(patient, "symptoms", "fever");
        sim.syncAll();
        return patient;
    }

    private RecordState server(RecordKey key) {
        return sim.engine().snapshot().get(key);
    }

    private void assertConverged() {
        Replica server = sim.engine().snapshot();
        for (SimDevice d : sim.devices()) {
            assertThat(d.pendingCount()).isZero();
            assertThat(d.view()).isEqualTo(server);
        }
    }

    // ---------------------------------------------------------------- tier 1 rules

    @Test
    void counter_twoOfflineSalesAreCombined() {
        RecordKey item = sharedItem(10);
        a.add(item, "quantity", -2);
        b.add(item, "quantity", -3);
        sim.syncAll();

        assertThat(server(item).value("quantity")).isEqualTo(5L);
        assertThat(sim.engine().resolutions()).hasSize(1);
        assertThat(sim.engine().resolutions().get(0).tier()).isEqualTo(1);
        assertConverged();
    }

    @Test
    void counter_severalOfflineSalesOnEachDeviceAllCount() {
        RecordKey item = sharedItem(20);
        a.add(item, "quantity", -2);
        a.add(item, "quantity", -1);
        b.add(item, "quantity", -3);
        b.add(item, "quantity", -4);
        b.add(item, "quantity", 5);     // restock
        sim.syncAll();

        assertThat(server(item).value("quantity")).isEqualTo(15L);
        assertConverged();
    }

    @Test
    void counter_twoIdenticalSalesAreStillTwoSales() {
        RecordKey item = sharedItem(10);
        a.add(item, "quantity", -2);
        b.add(item, "quantity", -2);    // both devices now show 8
        sim.syncAll();

        assertThat(server(item).value("quantity")).isEqualTo(6L);
    }

    @Test
    void counter_mergeThatBreaksInvariantGoesToReview() {
        RecordKey item = sharedItem(3);
        a.add(item, "quantity", -2);
        b.add(item, "quantity", -2);
        sim.syncAll();

        FieldState quantity = server(item).field("quantity");
        assertThat(quantity.value()).isEqualTo(1L);          // first sale stands, second is not applied
        assertThat(quantity.disputed()).isTrue();
        assertThat(sim.engine().openReviews()).hasSize(1);
        assertConverged();
    }

    @Test
    void sameDeviceEditingTwiceOfflineIsNotAConflict() {
        RecordKey item = sharedItem(10);
        a.set(item, "name", "Biscuit small");
        a.set(item, "name", "Biscuit packet small");
        sim.syncAll();

        assertThat(server(item).value("name")).isEqualTo("Biscuit packet small");
        assertThat(sim.engine().resolutions()).isEmpty();
    }

    @Test
    void editsToDifferentFieldsOfOneRecordAreNotAConflict() {
        RecordKey item = sharedItem(10);
        a.set(item, "name", "Biscuit small");
        b.add(item, "quantity", -1);
        sim.syncAll();

        assertThat(server(item).value("name")).isEqualTo("Biscuit small");
        assertThat(server(item).value("quantity")).isEqualTo(9L);
        assertThat(sim.engine().resolutions()).isEmpty();
    }

    @Test
    void setUnion_additionsFromBothSidesAreKept() {
        RecordKey patient = sharedPatient();
        a.addToSet(patient, "symptoms", "cough");
        b.addToSet(patient, "symptoms", "rash");
        sim.syncAll();

        assertThat(server(patient).value("symptoms")).isEqualTo(List.of("cough", "fever", "rash"));
        assertConverged();
    }

    @Test
    void setUnion_itemIsRemovedOnlyIfBothSidesRemovedIt() {
        RecordKey patient = sharedPatient();
        a.addToSet(patient, "symptoms", "cough");
        sim.syncAll();

        a.removeFromSet(patient, "symptoms", "fever");
        a.removeFromSet(patient, "symptoms", "cough");
        b.removeFromSet(patient, "symptoms", "fever");
        b.addToSet(patient, "symptoms", "rash");
        sim.syncAll();

        assertThat(server(patient).value("symptoms")).isEqualTo(List.of("cough", "rash"));
    }

    @Test
    void appendOnly_bothNotesKeptInClockOrder() {
        RecordKey patient = sharedPatient();
        a.append(patient, "visitNotes", "Morning visit: fever lower.");
        sim.advanceTime(60_000);
        b.append(patient, "visitNotes", "Evening visit: eating well.");
        sim.sync(b);                    // the later note reaches the server first
        sim.syncAll();

        @SuppressWarnings("unchecked")
        List<Note> notes = (List<Note>) server(patient).value("visitNotes");
        assertThat(notes).extracting(Note::text)
                .containsExactly("Morning visit: fever lower.", "Evening visit: eating well.");
        assertConverged();
    }

    @Test
    void latest_laterEditWinsWhicheverArrivesFirst() {
        RecordKey patient = sharedPatient();
        a.set(patient, "name", "Asha Devi Sharma");
        sim.advanceTime(60_000);
        b.set(patient, "name", "Asha D. Sharma");   // made later
        sim.sync(b);                                // but arrives first
        sim.syncAll();

        assertThat(server(patient).value("name")).isEqualTo("Asha D. Sharma");
        assertConverged();
    }

    @Test
    void latest_withAWrongClockTheWinnerIsStillTheSameWhicheverArrivesFirst() {
        Object[] winners = new Object[2];
        for (int run = 0; run < 2; run++) {
            setUp();
            RecordKey patient = sharedPatient();
            a.setClockSkewMillis(-5 * 3_600_000L);      // device A's clock is five hours behind
            a.set(patient, "name", "Asha Devi Sharma");
            sim.advanceTime(1_000);
            b.set(patient, "name", "Asha Sharma");
            sim.sync(run == 0 ? a : b);
            sim.syncAll();
            winners[run] = server(patient).value("name");
            assertConverged();
        }
        assertThat(winners[0]).isEqualTo(winners[1]);
    }

    @Test
    void latest_editMadeAfterSeeingAnotherEditSortsAfterItEvenWithASlowClock() {
        RecordKey patient = sharedPatient();
        a.setClockSkewMillis(-5 * 3_600_000L);      // device A's clock is five hours behind
        b.set(patient, "name", "Asha Sharma");
        sim.syncAll();                              // A pulls B's edit
        Operation later = a.set(patient, "name", "Asha Devi Sharma");

        String seen = server(patient).field("name").hlc();
        assertThat(Hlc.compare(later.hlc(), seen)).isPositive();
    }

    // ---------------------------------------------------------------- escalation

    @Test
    void critical_conflictingDoseIsNeverAutoMerged() {
        RecordKey patient = sharedPatient();
        a.set(patient, "medicineDose", "10 mg");
        b.set(patient, "medicineDose", "2.5 mg");
        sim.syncAll();

        FieldState dose = server(patient).field("medicineDose");
        assertThat(dose.value()).isEqualTo("10 mg");                 // first to arrive, no conflict then
        assertThat(dose.deviceId()).isEqualTo("device-a");
        assertThat(dose.candidates()).extracting(FieldState.Candidate::value).containsExactly("2.5 mg");
        assertThat(dose.candidates()).extracting(FieldState.Candidate::deviceId).containsExactly("device-b");
        assertThat(sim.engine().resolutions()).isEmpty();
        assertThat(sim.engine().openReviews()).hasSize(1);
        assertConverged();                                           // disputed on every device
    }

    @Test
    void critical_editWithoutConflictIsApplied() {
        RecordKey patient = sharedPatient();
        b.set(patient, "medicineDose", "10 mg");
        sim.syncAll();

        assertThat(server(patient).field("medicineDose").value()).isEqualTo("10 mg");
        assertThat(server(patient).field("medicineDose").disputed()).isFalse();
    }

    @Test
    void deleteThenOfflineEdit_recordIsKeptAndMarkedDeletedElsewhere() {
        RecordKey patient = sharedPatient();
        a.delete(patient);
        b.append(patient, "visitNotes", "Visited today.");
        sim.sync(a);                    // delete arrives first
        sim.syncAll();

        RecordState record = server(patient);
        assertThat(record.deleted()).isFalse();
        assertThat(record.deleteDispute().deviceId()).isEqualTo("device-a");
        assertThat(record.value("visitNotes")).isNotNull();
        assertThat(sim.engine().openReviews()).hasSize(1);
        assertConverged();
    }

    @Test
    void offlineEditThenDelete_recordIsKeptAndMarkedDeletedElsewhere() {
        RecordKey patient = sharedPatient();
        a.delete(patient);
        b.set(patient, "name", "Asha Devi Sharma");
        sim.sync(b);                    // edit arrives first
        sim.syncAll();

        RecordState record = server(patient);
        assertThat(record.deleted()).isFalse();
        assertThat(record.deleteDispute().deviceId()).isEqualTo("device-a");
        assertThat(record.value("name")).isEqualTo("Asha Devi Sharma");
        assertConverged();
    }

    @Test
    void deleteWithoutConflictReachesEveryDeviceAsATombstone() {
        RecordKey patient = sharedPatient();
        a.delete(patient);
        sim.syncAll();

        assertThat(server(patient).deleted()).isTrue();
        assertThat(b.view().get(patient).deleted()).isTrue();
        assertConverged();
    }

    // ---------------------------------------------------------------- unreliable network

    @Test
    void resendingABatchChangesNothing() {
        RecordKey item = sharedItem(10);
        a.add(item, "quantity", -2);
        sim.sync(a, Fault.ACK_LOST);            // server got it, device does not know
        long head = sim.engine().head();
        sim.sync(a, Fault.DELIVERED_TWICE);     // device resends, network duplicates

        assertThat(sim.engine().head()).isEqualTo(head);
        assertThat(server(item).value("quantity")).isEqualTo(8L);
        sim.syncAll();
        assertConverged();
    }

    @Test
    void syncDroppedHalfwayLosesNothing() {
        RecordKey item = sharedItem(10);
        a.add(item, "quantity", -1);
        a.add(item, "quantity", -1);
        a.add(item, "quantity", -1);
        b.add(item, "quantity", -4);
        sim.sync(a, Fault.DROPPED_HALFWAY);
        sim.syncAll();

        assertThat(server(item).value("quantity")).isEqualTo(3L);
        assertConverged();
    }

    @Test
    void restoringFromTheLogGivesTheSameState() {
        RecordKey item = sharedItem(10);
        RecordKey patient = sharedPatient();
        a.add(item, "quantity", -2);
        b.add(item, "quantity", -3);
        a.set(patient, "medicineDose", "10 mg");
        b.set(patient, "medicineDose", "2.5 mg");
        b.delete(patient);
        sim.syncAll();

        SyncEngine restored = SyncEngine.restore(spec, sim.engine().pull(0));
        assertThat(restored.snapshot()).isEqualTo(sim.engine().snapshot());
        assertThat(restored.openReviews()).isEqualTo(sim.engine().openReviews());
    }

    @Test
    void malformedOperationIsRefusedAndNothingIsApplied() {
        Operation bad = new Operation("op-1", "device-x", "item", "r1", null, OpKind.SET, 1, 0, "not-a-clock");
        assertThatThrownBy(() -> sim.engine().push(List.of(bad))).isInstanceOf(IllegalArgumentException.class);
        assertThat(sim.engine().head()).isZero();
    }

    // ---------------------------------------------------------------- review-decision fold

    /**
     * When a reviewer picks a value for a disputed critical field, a REVIEW_DECISION log entry
     * must clear all candidates and set the field's value and hlc from the operation.
     * Requirements 7.4.
     */
    @Test
    void reviewDecision_clearsDisputeAndAppliesChosenValue() {
        // Create a critical-field dispute via two offline edits
        RecordKey patient = sharedPatient();
        a.set(patient, "medicineDose", "10 mg");
        b.set(patient, "medicineDose", "2.5 mg");
        sim.syncAll();

        // Confirm dispute exists
        FieldState doseBefore = sim.engine().snapshot().get(patient).field("medicineDose");
        assertThat(doseBefore.disputed()).isTrue();
        assertThat(doseBefore.candidates()).hasSize(1);

        // Build a REVIEW_DECISION log entry as the server would write it
        String reviewHlc = "2026-11-20T12:00:00.000Z-0001-server";
        Operation reviewOp = new Operation(
                "review-op-1", SyncEngine.SERVER, patient.recordType(), patient.recordId(),
                "medicineDose", OpKind.REVIEW_DECISION, "2.5 mg", sim.engine().head(), reviewHlc);
        LogEntry reviewEntry = new LogEntry(sim.engine().head() + 1, reviewOp,
                Disposition.REVIEW_DECISION, "Reviewer chose 2.5 mg.");

        // Restore the engine from the existing log plus the review-decision entry
        List<LogEntry> log = new ArrayList<>(sim.engine().pull(0));
        log.add(reviewEntry);
        SyncEngine restored = SyncEngine.restore(spec, log);

        FieldState doseAfter = restored.snapshot().get(patient).field("medicineDose");
        assertThat(doseAfter.disputed()).isFalse();
        assertThat(doseAfter.candidates()).isEmpty();
        assertThat(doseAfter.value()).isEqualTo("2.5 mg");
        assertThat(doseAfter.hlc()).isEqualTo(reviewHlc);
        assertThat(doseAfter.deviceId()).isEqualTo(SyncEngine.SERVER);

        // The record should no longer appear in openReviews
        assertThat(restored.openReviews()).isEmpty();
    }

    /**
     * A plain SET on a field that is already disputed must ADD to candidates, not clear them.
     * Requirements 7.1.
     */
    @Test
    void set_onDisputedField_addsCandidate_doesNotClearDispute() {
        // Establish a 3-device simulation for this test
        SimDevice c = sim.addDevice("device-c");
        RecordKey patient = sharedPatient();   // syncs a and b (and c via syncAll below)
        sim.sync(c);                           // c now knows about the record

        // Create the initial dispute between a and b
        a.set(patient, "medicineDose", "10 mg");
        b.set(patient, "medicineDose", "2.5 mg");
        sim.sync(a);
        sim.sync(b);

        FieldState doseBefore = sim.engine().snapshot().get(patient).field("medicineDose");
        assertThat(doseBefore.disputed()).isTrue();
        int candidatesBefore = doseBefore.candidates().size();

        // device-c now also edits the same disputed field — after pulling, c knows it's disputed
        sim.sync(c);                           // c pulls the dispute state
        c.set(patient, "medicineDose", "7.5 mg");
        sim.sync(c);

        FieldState doseAfter = sim.engine().snapshot().get(patient).field("medicineDose");
        assertThat(doseAfter.disputed()).isTrue();
        assertThat(doseAfter.candidates()).hasSize(candidatesBefore + 1);
        assertThat(doseAfter.candidates())
                .extracting(FieldState.Candidate::value)
                .contains("7.5 mg");
        // Server value (first arrival) must remain unchanged
        assertThat(doseAfter.value()).isEqualTo("10 mg");
    }

    /**
     * A DELETE_DISPUTE_DECISION with value "delete" must tombstone the record and clear deleteDispute.
     * Requirements 3.8.
     */
    @Test
    void deleteDisputeDecision_delete_tombstonesRecord() {
        RecordKey patient = sharedPatient();
        a.delete(patient);
        b.append(patient, "visitNotes", "Last visit.");
        sim.sync(a);
        sim.syncAll();

        RecordState stateBefore = sim.engine().snapshot().get(patient);
        assertThat(stateBefore.deleted()).isFalse();
        assertThat(stateBefore.deleteDispute()).isNotNull();

        // Build a DELETE_DISPUTE_DECISION entry with value "delete"
        String decisionHlc = "2026-11-20T12:00:00.000Z-0001-server";
        Operation decisionOp = new Operation(
                "ddd-op-delete", SyncEngine.SERVER, patient.recordType(), patient.recordId(),
                null, OpKind.DELETE_DISPUTE_DECISION, "delete", sim.engine().head(), decisionHlc);
        LogEntry decisionEntry = new LogEntry(sim.engine().head() + 1, decisionOp,
                Disposition.REVIEW_DECISION, "Reviewer confirmed deletion.");

        List<LogEntry> log = new ArrayList<>(sim.engine().pull(0));
        log.add(decisionEntry);
        SyncEngine restored = SyncEngine.restore(spec, log);

        RecordState stateAfter = restored.snapshot().get(patient);
        assertThat(stateAfter.deleted()).isTrue();
        assertThat(stateAfter.deleteDispute()).isNull();
        assertThat(restored.openReviews()).isEmpty();
    }

    /**
     * A DELETE_DISPUTE_DECISION with value "keep" must clear deleteDispute but leave the record
     * intact (not tombstoned). Requirements 3.8.
     */
    @Test
    void deleteDisputeDecision_keep_clearsDisputeButKeepsRecord() {
        RecordKey patient = sharedPatient();
        a.delete(patient);
        b.append(patient, "visitNotes", "Last visit.");
        sim.sync(a);
        sim.syncAll();

        RecordState stateBefore = sim.engine().snapshot().get(patient);
        assertThat(stateBefore.deleted()).isFalse();
        assertThat(stateBefore.deleteDispute()).isNotNull();

        // Build a DELETE_DISPUTE_DECISION entry with value "keep"
        String decisionHlc = "2026-11-20T12:00:00.000Z-0001-server";
        Operation decisionOp = new Operation(
                "ddd-op-keep", SyncEngine.SERVER, patient.recordType(), patient.recordId(),
                null, OpKind.DELETE_DISPUTE_DECISION, "keep", sim.engine().head(), decisionHlc);
        LogEntry decisionEntry = new LogEntry(sim.engine().head() + 1, decisionOp,
                Disposition.REVIEW_DECISION, "Reviewer decided to keep the record.");

        List<LogEntry> log = new ArrayList<>(sim.engine().pull(0));
        log.add(decisionEntry);
        SyncEngine restored = SyncEngine.restore(spec, log);

        RecordState stateAfter = restored.snapshot().get(patient);
        assertThat(stateAfter.deleted()).isFalse();
        assertThat(stateAfter.deleteDispute()).isNull();
        assertThat(restored.openReviews()).isEmpty();
    }

    /**
     * A REVIEW_DECISION in the log survives a restore: the dispute is gone after restore too.
     * Verifies the fold is idempotent via restore.  Requirements 7.4, 9.3.
     */
    @Test
    void reviewDecision_persistsThroughRestore() {
        RecordKey patient = sharedPatient();
        a.set(patient, "medicineDose", "10 mg");
        b.set(patient, "medicineDose", "2.5 mg");
        sim.syncAll();

        String reviewHlc = "2026-11-20T12:00:00.000Z-0001-server";
        Operation reviewOp = new Operation(
                "review-op-persist", SyncEngine.SERVER, patient.recordType(), patient.recordId(),
                "medicineDose", OpKind.REVIEW_DECISION, "10 mg", sim.engine().head(), reviewHlc);
        LogEntry reviewEntry = new LogEntry(sim.engine().head() + 1, reviewOp,
                Disposition.REVIEW_DECISION, "Reviewer chose 10 mg.");

        List<LogEntry> log = new ArrayList<>(sim.engine().pull(0));
        log.add(reviewEntry);

        // Restore twice from the same log
        SyncEngine r1 = SyncEngine.restore(spec, log);
        SyncEngine r2 = SyncEngine.restore(spec, log);

        assertThat(r1.snapshot()).isEqualTo(r2.snapshot());
        FieldState dose = r1.snapshot().get(patient).field("medicineDose");
        assertThat(dose.disputed()).isFalse();
        assertThat(dose.value()).isEqualTo("10 mg");
    }
}
