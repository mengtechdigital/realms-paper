package com.realms.data;

/**
 * Immutable directed relation row: realmA → realmB has the given kind.
 * NEUTRAL is the absence of a row, not a stored value.
 */
public record Relation(
        long realmA,
        long realmB,
        RelationKind kind,
        long establishedMillis
) {
    public Relation reversed() {
        return new Relation(realmB, realmA, kind, establishedMillis);
    }
}
