package com.finora.finance.documents;

import java.util.UUID;

/**
 * Where document bytes live. Callers never supply a path: the key is generated here, and implementations must refuse any key that is not
 * one they generated. A cloud or object-store implementation can replace the local one without touching the service.
 */
public interface DocumentStorage {

    /** Stores the bytes for a workspace and returns the generated key. */
    String store(UUID tenant, byte[] content);

    /** Reads the bytes; the key must belong to the given workspace. */
    byte[] load(UUID tenant, String key);

    /** Removes the bytes (a missing file is not an error). */
    void delete(UUID tenant, String key);
}
