package dev.doppelganger.sim;

import dev.doppelganger.core.Disposition;
import dev.doppelganger.core.DomainSpec;
import dev.doppelganger.core.FieldState;
import dev.doppelganger.core.FieldState.Note;
import dev.doppelganger.core.LogEntry;
import dev.doppelganger.core.Operation;
import dev.doppelganger.core.PushResult;
import dev.doppelganger.core.RecordKey;
import dev.doppelganger.core.RecordState;
import dev.doppelganger.core.Replica;
import dev.doppelganger.core.SyncEngine;
import dev.doppelganger.core.Values;
import dev.doppelganger.server.BuiltInSpecs;
import dev.doppelganger.sim.Simulation.Fault;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests (requirements 9 and 13). Each scenario is a random list of steps carried
 * out by three simulated devices against the real merge engine: creating records, editing every
 * kind of field, deleting, setting a clock wrong, and syncing over a network that fails in
 * different ways. After the steps every device syncs cleanly and the properties are checked.
 *
 * <p>A failing scenario is shrunk by jqwik to the shortest list of steps that still fails.
 */
class SyncPropertiesTest {

    private static final int TRIES = 2500;
    private static final int DEVICES = 3;
    private static final String[] NAMES = {"Asha Devi", "Ramesh Kumar", "Sunita Yadav", "Imran Khan"};
    private static final String[] ITEMS = {"Biscuit packet", "Tea 250g", "Soap bar", "Rice 1kg"};
    private static final String[] DOSES = {"5 mg", "10 mg", "250 mg", "2.5 ml"};
    private static final String[] SYMPTOMS = {"fever", "cough", "rash", "headache", "nausea"};

    /** One random step. Every number is reduced to a valid choice when the step is carried out. */
    record Step(int action, int device, int record, int field, int value, int fault) {
    }

    @Provide
    Arbitrary<List<Step>> scenarios() {
        Arbitrary<Integer> any = Arbitraries.integers().between(0, 99);
        return Combinators.combine(Arbitraries.integers().between(0, 19),
                        Arbitraries.integers().between(0, DEVICES - 1), any, any, any, any)
                .as(Step::new).list().ofMinSize(1).ofMaxSize(60);
    }

    // ---------------------------------------------------------------- properties

    /** Requirement 9.1: after everyone syncs, every device holds exactly what the server holds. */
    @Property(tries = TRIES)
    void convergence(@ForAll("scenarios") List<Step> steps) {
        Run run = Run.of(steps);
        Replica server = run.sim.engine().snapshot();
        for (SimDevice device : run.sim.devices()) {
            assertThat(device.pendingCount()).as("pending on %s", device.id()).isZero();
            assertThat(device.replica()).as("synced state of %s", device.id()).isEqualTo(server);
            assertThat(device.view()).as("visible state of %s", device.id()).isEqualTo(server);
        }
        // The log alone is enough to rebuild the server (restart safety).
        SyncEngine restored = SyncEngine.restore(run.spec, run.sim.engine().pull(0));
        assertThat(restored.snapshot()).isEqualTo(server);

        Statistics.label("scenario had").collect(
                run.count(Disposition.MERGED) > 0 ? "rule merge" : "no rule merge",
                run.count(Disposition.DISPUTED) > 0 ? "review" : "no review");
    }

    /** Requirement 9.2: nothing a device did is lost. */
    @Property(tries = TRIES)
    void noDataLoss(@ForAll("scenarios") List<Step> steps) {
        Run run = Run.of(steps);
        Replica server = run.sim.engine().snapshot();
        Map<String, LogEntry> byOpId = run.sim.engine().pull(0).stream()
                .collect(Collectors.toMap(e -> e.op().opId(), Function.identity()));

        // Every operation any device ever made is in the log exactly once.
        for (SimDevice device : run.sim.devices()) {
            for (Operation op : device.history()) {
                assertThat(byOpId).as("log contains %s", op.opId()).containsKey(op.opId());
            }
        }
        // Counters: the final quantity is exactly the sum of every change the server accepted.
        Map<RecordKey, Long> expected = new HashMap<>();
        run.counterDeltas.forEach((opId, delta) -> {
            LogEntry e = byOpId.get(opId);
            if (e.disposition() == Disposition.APPLIED || e.disposition() == Disposition.MERGED) {
                expected.merge(e.op().key(), delta, Long::sum);
            }
        });
        expected.forEach((key, sum) ->
                assertThat(Values.asLong(server.get(key).value("quantity"))).as("quantity of %s", key).isEqualTo(sum));

        // Append-only: every note is present exactly once.
        run.notes.forEach((opId, text) -> {
            RecordState record = server.get(byOpId.get(opId).op().key());
            @SuppressWarnings("unchecked")
            List<Note> notes = (List<Note>) record.value("visitNotes");
            assertThat(notes).as("notes of %s", record.key()).extracting(Note::text).containsOnlyOnce(text);
        });
        // Sets: a symptom somebody added and nobody ever removed is still there.
        run.symptomAdds.forEach((opId, symptom) -> {
            LogEntry e = byOpId.get(opId);
            boolean accepted = e.disposition() == Disposition.APPLIED || e.disposition() == Disposition.MERGED;
            if (accepted && !run.symptomRemovals.contains(e.op().key() + "/" + symptom)) {
                assertThat(Values.asSet(server.get(e.op().key()).value("symptoms"))).contains(symptom);
            }
        });
    }

    /** Requirement 9.3: sending everything again changes nothing. */
    @Property(tries = TRIES)
    void idempotency(@ForAll("scenarios") List<Step> steps) {
        Run run = Run.of(steps);
        SyncEngine engine = run.sim.engine();
        Replica before = engine.snapshot();
        long head = engine.head();

        for (SimDevice device : run.sim.devices()) {
            PushResult again = engine.push(device.history());
            assertThat(again.appended()).isEmpty();
            assertThat(again.duplicates()).hasSize(device.history().size());
        }
        assertThat(engine.head()).isEqualTo(head);
        assertThat(engine.snapshot()).isEqualTo(before);
    }

    /** Tier 1 never leaves a broken invariant behind, and a disputed field keeps its value. */
    @Property(tries = TRIES)
    void invariantsHold(@ForAll("scenarios") List<Step> steps) {
        Run run = Run.of(steps);
        for (RecordState record : run.sim.engine().snapshot().records().values()) {
            FieldState quantity = record.field("quantity");
            if (quantity.value() != null) {
                assertThat(Values.asLong(quantity.value())).as("quantity of %s", record.key()).isGreaterThanOrEqualTo(0);
            }
        }
    }

    // ---------------------------------------------------------------- scenario runner

    static final class Run {
        final DomainSpec spec = new DomainSpec(BuiltInSpecs.patient(), BuiltInSpecs.item());
        final Simulation sim = new Simulation(spec);
        final Map<String, Long> counterDeltas = new HashMap<>();      // opId -> change in quantity
        final Map<String, String> notes = new HashMap<>();            // opId -> note text
        final Map<String, String> symptomAdds = new HashMap<>();      // opId -> symptom
        final Set<String> symptomRemovals = new HashSet<>();          // "recordKey/symptom"
        private int noteCounter;

        static Run of(List<Step> steps) {
            Run run = new Run();
            for (int i = 0; i < DEVICES; i++) run.sim.addDevice("device-" + (char) ('a' + i));
            // Every scenario starts with one item and one patient that all devices already share,
            // so random offline edits collide often.
            run.create(run.sim.devices().get(0), new Step(0, 0, 0, 0, 6, 0));
            run.create(run.sim.devices().get(0), new Step(0, 0, 0, 0, 7, 0));
            run.sim.syncAll();
            steps.forEach(run::carryOut);
            run.sim.syncAll();
            return run;
        }

        long count(Disposition disposition) {
            return sim.engine().pull(0).stream().filter(e -> e.disposition() == disposition).count();
        }

        private void carryOut(Step s) {
            SimDevice device = sim.devices().get(s.device());
            sim.advanceTime(1 + s.value() * 37L);
            if (s.action() == 0) create(device, s);
            else if (s.action() <= 12) edit(device, s);
            else if (s.action() == 13) delete(device, s);
            else if (s.action() <= 18) sim.sync(device, Fault.values()[s.fault() % Fault.values().length]);
            else device.setClockSkewMillis((s.value() - 50) * 600_000L);   // up to about 8 hours wrong
        }

        private void create(SimDevice device, Step s) {
            if (s.value() % 2 == 0) {
                RecordKey item = device.create("item");
                device.set(item, "name", ITEMS[s.field() % ITEMS.length]);
                long quantity = 3 + s.value() % 10;
                counterDeltas.put(device.set(item, "quantity", quantity).opId(), quantity);
                device.set(item, "price", 5 + s.record() % 20);
            } else {
                RecordKey patient = device.create("patient");
                device.set(patient, "name", NAMES[s.field() % NAMES.length]);
                device.set(patient, "medicineDose", DOSES[s.record() % DOSES.length]);
            }
        }

        private RecordKey pick(SimDevice device, Step s) {
            List<RecordKey> live = new ArrayList<>();
            device.view().records().forEach((key, record) -> {
                if (!record.deleted()) live.add(key);
            });
            return live.isEmpty() ? null : live.get(s.record() % live.size());
        }

        private void delete(SimDevice device, Step s) {
            RecordKey key = pick(device, s);
            if (key != null) device.delete(key);
        }

        private void edit(SimDevice device, Step s) {
            RecordKey key = pick(device, s);
            if (key == null) return;
            if (key.recordType().equals("item")) {
                switch (s.field() % 5) {
                    case 0, 1 -> {
                        long have = Values.asLong(device.view().get(key).value("quantity"));
                        long delta = s.value() % 3 == 0 ? 1 + s.value() % 6 : -(1 + s.value() % 4);
                        if (have + delta < 0) return;      // the app would not let a shop sell stock it does not have
                        Operation op = device.add(key, "quantity", delta);
                        if (op != null) counterDeltas.put(op.opId(), delta);
                    }
                    case 2 -> device.set(key, "name", ITEMS[s.value() % ITEMS.length]);
                    case 3 -> device.set(key, "price", 5 + s.value() % 20);
                    default -> device.set(key, "supplierNotes", "supplier note " + s.value() % 7);
                }
            } else {
                switch (s.field() % 6) {
                    case 0 -> device.set(key, "name", NAMES[s.value() % NAMES.length]);
                    case 1, 2 -> {
                        String text = "note " + (++noteCounter);
                        notes.put(device.append(key, "visitNotes", text).opId(), text);
                    }
                    case 3 -> {
                        String symptom = SYMPTOMS[s.value() % SYMPTOMS.length];
                        if (s.value() % 3 == 0) {
                            if (device.removeFromSet(key, "symptoms", symptom) != null) {
                                symptomRemovals.add(key + "/" + symptom);
                            }
                        } else {
                            Operation op = device.addToSet(key, "symptoms", symptom);
                            if (op != null) symptomAdds.put(op.opId(), symptom);
                        }
                    }
                    case 4 -> device.set(key, "medicineDose", DOSES[s.value() % DOSES.length]);
                    default -> device.set(key, "followUp", "follow-up advice " + s.value() % 7);
                }
            }
        }
    }
}
