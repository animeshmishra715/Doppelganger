package dev.doppelganger.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.doppelganger.core.Disposition;
import dev.doppelganger.core.DomainSpec;
import dev.doppelganger.core.LogEntry;
import dev.doppelganger.core.OpKind;
import dev.doppelganger.core.Operation;
import dev.doppelganger.core.PushResult;
import dev.doppelganger.core.Replica;
import dev.doppelganger.core.SyncEngine;
import dev.doppelganger.core.Values;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Connects the merge engine to the database. The {@code operations} table is the single source
 * of truth: on startup the engine is rebuilt by replaying it, and every push stores the new log
 * entries in one transaction.
 */
@Service
public class SyncService {

    private final OperationRepository repository;
    private final ObjectMapper json;
    private final DomainSpec spec;
    private SyncEngine engine;

    public SyncService(OperationRepository repository, ObjectMapper json, DomainSpec spec) {
        this.repository = repository;
        this.json = json;
        this.spec = spec;
        this.engine = loadEngine();
    }

    public synchronized PushResult push(List<Operation> ops) {
        PushResult result = engine.push(ops);
        try {
            repository.saveAll(result.appended().stream().map(this::toEntity).toList());
        } catch (RuntimeException e) {
            engine = loadEngine();   // the database is the truth: drop what could not be stored
            throw e;
        }
        return result;
    }

    public synchronized List<LogEntry> pull(long since) {
        return engine.pull(since);
    }

    public synchronized long head() {
        return engine.head();
    }

    public synchronized Replica snapshot() {
        return engine.snapshot();
    }

    private SyncEngine loadEngine() {
        return SyncEngine.restore(spec, repository.findAllByOrderByPositionAsc().stream().map(this::toEntry).toList());
    }

    private OperationEntity toEntity(LogEntry entry) {
        Operation op = entry.op();
        OperationEntity e = new OperationEntity();
        e.position = entry.position();
        e.opId = op.opId();
        e.deviceId = op.deviceId();
        e.recordType = op.recordType();
        e.recordId = op.recordId();
        e.field = op.field();
        e.kind = op.kind().json();
        e.baseVersion = op.baseVersion();
        e.hlc = op.hlc();
        e.disposition = entry.disposition().name();
        e.note = entry.note();
        try {
            e.valueJson = json.writeValueAsString(op.value());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Value of " + op.opId() + " cannot be stored as JSON", ex);
        }
        return e;
    }

    private LogEntry toEntry(OperationEntity e) {
        Object value;
        try {
            value = Values.normalize(json.readValue(e.valueJson, Object.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored value at log position " + e.position + " is not valid JSON", ex);
        }
        Operation op = new Operation(e.opId, e.deviceId, e.recordType, e.recordId, e.field,
                OpKind.fromJson(e.kind), value, e.baseVersion, e.hlc);
        return new LogEntry(e.position, op, Disposition.valueOf(e.disposition), e.note);
    }
}
