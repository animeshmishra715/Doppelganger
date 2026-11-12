package dev.doppelganger.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** One row of the append-only {@code operations} table. Rows are inserted, never updated or deleted. */
@Entity
@Table(name = "operations")
public class OperationEntity implements Persistable<Long> {

    @Id
    @Column(name = "log_position", updatable = false)
    Long position;

    @Column(name = "op_id", nullable = false, unique = true, updatable = false)
    String opId;

    @Column(name = "device_id", nullable = false, updatable = false)
    String deviceId;

    @Column(name = "record_type", nullable = false, updatable = false)
    String recordType;

    @Column(name = "record_id", nullable = false, updatable = false)
    String recordId;

    @Column(name = "field", updatable = false)
    String field;

    @Column(name = "kind", nullable = false, updatable = false)
    String kind;

    /** The value as JSON text. */
    @Column(name = "value_json", columnDefinition = "text", updatable = false)
    String valueJson;

    @Column(name = "base_version", nullable = false, updatable = false)
    long baseVersion;

    @Column(name = "hlc", nullable = false, updatable = false)
    String hlc;

    @Column(name = "disposition", nullable = false, updatable = false)
    String disposition;

    @Column(name = "note", length = 1000, updatable = false)
    String note;

    @Transient
    private boolean isNew = true;

    @Override
    public Long getId() {
        return position;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }
}
