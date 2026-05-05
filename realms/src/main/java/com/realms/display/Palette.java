package com.realms.display;

import com.realms.RealmsConfig;
import com.realms.data.Realm;
import com.realms.data.Resident;
import com.realms.data.RealmsStore;
import com.realms.manager.DiplomacyManager;
import com.realms.manager.PowerCalc;
import com.realms.manager.Text;
import org.bukkit.Color;
import org.bukkit.entity.Player;

/**
 * Resolves the relation-color palette used by titles, action bar, boss bar,
 * and see-claims particles. One source of truth — feeds all display
 * subsystems.
 */
public final class Palette {

    public enum Relation { OWN, ALLY, ENEMY, NEUTRAL, PEACEFUL, SAFEZONE, WARZONE, WILDERNESS }

    private final RealmsConfig config;
    private final RealmsStore store;
    private final DiplomacyManager diplomacy;
    private final PowerCalc power;

    public Palette(RealmsConfig config, RealmsStore store,
                   DiplomacyManager diplomacy, PowerCalc power) {
        this.config = config;
        this.store = store;
        this.diplomacy = diplomacy;
        this.power = power;
    }

    public Relation relationFor(Player viewer, Long ownerRealmId) {
        if (ownerRealmId == null) return Relation.WILDERNESS;
        Realm realm = store.getRealm(ownerRealmId);
        if (realm == null) return Relation.WILDERNESS;
        // Admin zones are their own categories and override player relations.
        if (realm.zoneType() == com.realms.data.ZoneType.SAFEZONE) return Relation.SAFEZONE;
        if (realm.zoneType() == com.realms.data.ZoneType.WARZONE)  return Relation.WARZONE;
        if (realm.peaceful()) return Relation.PEACEFUL;
        Resident me = store.getResident(viewer.getUniqueId());
        if (me == null) return Relation.NEUTRAL;
        // Long == long auto-boxes the primitive then does identity compare —
        // works for IDs ≤ 127 (cached) and silently fails above that. Use the
        // primitive value of the boxed Long so any realm ID compares right.
        long ownerPrimitive = ownerRealmId.longValue();
        if (me.realmId() == ownerPrimitive) return Relation.OWN;
        if (diplomacy.areAllies(me.realmId(), ownerPrimitive)) return Relation.ALLY;
        if (diplomacy.areEnemies(me.realmId(), ownerPrimitive)) return Relation.ENEMY;
        return Relation.NEUTRAL;
    }

    /** Legacy color code (e.g. "&a") for chat / titles / action bars. */
    public String code(Relation r) {
        return switch (r) {
            case OWN        -> config.colorOwn();
            case ALLY       -> config.colorAlly();
            case ENEMY      -> config.colorEnemy();
            case NEUTRAL    -> config.colorNeutral();
            case PEACEFUL   -> config.colorPeaceful();
            case SAFEZONE   -> config.colorSafezone();
            case WARZONE    -> config.colorWarzone();
            case WILDERNESS -> config.colorWilderness();
        };
    }

    /** RGB Color for particles (DUST particle data). */
    public Color rgb(Relation r) {
        return switch (r) {
            case OWN        -> Color.fromRGB(0x4ade80);  // green-400
            case ALLY       -> Color.fromRGB(0x38bdf8);  // sky-400
            case ENEMY      -> Color.fromRGB(0xef4444);  // red-500
            case NEUTRAL    -> Color.fromRGB(0xfacc15);  // yellow-400
            case PEACEFUL   -> Color.fromRGB(0xf59e0b);  // amber-500
            case SAFEZONE   -> Color.fromRGB(0x22c55e);  // emerald-500
            case WARZONE    -> Color.fromRGB(0xf97316);  // orange-500
            case WILDERNESS -> Color.fromRGB(0x9ca3af);  // gray-400
        };
    }

    /** True iff the viewer's realm is currently weakened (claim_cost > capacity). */
    public boolean isWeakened(Realm realm) {
        if (realm == null) return false;
        return power.currentClaimCost(realm) > power.compute(realm);
    }

    public String render(String template, java.util.Map<String, String> vars) {
        return Text.render(template, vars);
    }
}
