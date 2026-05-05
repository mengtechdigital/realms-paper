package com.realms.data;

/**
 * Directional relation between two realms. NEUTRAL is the absence of a row,
 * not a stored kind. ALLY is bilateral (two rows written together after both
 * sides confirm); ENEMY is unilateral (one row per declaring direction).
 */
public enum RelationKind {
    ALLY,
    ENEMY;
}
