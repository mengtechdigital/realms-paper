package com.realms.data;

/**
 * Resident's role within a realm. Mayor is unique per realm.
 */
public enum Role {
    MAYOR,
    ASSISTANT,
    RESIDENT;

    /** True iff this role can claim, invite, kick, declare relations. */
    public boolean canManage() {
        return this == MAYOR || this == ASSISTANT;
    }

    /** True iff this role can promote/demote, transfer mayorship, set home, disband. */
    public boolean isMayor() {
        return this == MAYOR;
    }
}
