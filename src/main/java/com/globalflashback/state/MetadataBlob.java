package com.globalflashback.state;

import java.util.Arrays;
import java.util.Objects;

/**
 * Opaque entity metadata blob produced by Capture (e.g. non-default synched data).
 * Phase 2 stores bytes only; interpretation belongs to NMS adapter / Encoder.
 */
public record MetadataBlob(byte[] payload) {
    public static final MetadataBlob EMPTY = new MetadataBlob(new byte[0]);

    public MetadataBlob {
        payload = payload == null ? new byte[0] : payload.clone();
    }

    public boolean isEmpty() {
        return payload.length == 0;
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MetadataBlob that)) return false;
        return Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "MetadataBlob{bytes=" + payload.length + "}";
    }
}
