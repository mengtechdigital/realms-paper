package com.realms.manager;

import com.realms.data.RealmsStore;
import com.realms.data.Role;

/**
 * Role title rendering. A realm can override the user-visible label for
 * each {@link Role} (e.g. Mayor → "Lord", Resident → "Citizen"). When an
 * override is unset, {@link #defaultLabel} returns the plugin default.
 *
 * Centralised so chat prefix, tab prefix, /realm who, /rc, and PAPI all
 * render the same custom title for a given (realm, role).
 */
public final class RealmTitles {

    private RealmTitles() {}

    /** Plain ASCII default for a role — used when the realm has no override. */
    public static String defaultLabel(Role role) {
        return switch (role) {
            case MAYOR -> "Mayor";
            case ASSISTANT -> "Assistant";
            case RESIDENT -> "Resident";
        };
    }

    /**
     * Render the visible title for {@code (realmId, role)}: the realm's
     * custom override if present, otherwise {@link #defaultLabel}. Safe
     * against null store / unknown realm — falls back to the default.
     */
    public static String label(RealmsStore store, long realmId, Role role) {
        if (store == null || role == null) return defaultLabel(role);
        String override = store.titleFor(realmId, role);
        return override == null || override.isBlank() ? defaultLabel(role) : override;
    }
}
