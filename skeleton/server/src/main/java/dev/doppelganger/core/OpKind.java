package dev.doppelganger.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OpKind {
    CREATE, SET, DELETE;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static OpKind fromJson(String s) {
        return valueOf(s.trim().toUpperCase());
    }
}
