package com.realms.data;

import java.util.UUID;

/**
 * Immutable snapshot of a residents row. A player has at most one Resident
 * record at a time (UUID is the table primary key).
 */
public record Resident(
        UUID uuid,
        long realmId,
        Role role,
        long joinedMillis
) {
    public Resident withRole(Role newRole) {
        return new Resident(uuid, realmId, newRole, joinedMillis);
    }
}
