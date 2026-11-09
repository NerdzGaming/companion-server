package com.playtest;

/** Extra opponent patience applies only to single-target battlefield removal, never recursion or burn to face. */
public final class RemovalPolicy {
    private RemovalPolicy() { }

    public static boolean applies(boolean spell, int maxTargets, String api, String origin, String destination,
                                  boolean playerTarget, boolean nonBattlefieldTarget) {
        if (!spell || maxTargets != 1 || playerTarget || nonBattlefieldTarget) return false;
        if ("Destroy".equals(api) || "DealDamage".equals(api)) return true;
        return "ChangeZone".equals(api) && "Battlefield".equals(origin)
            && ("Exile".equals(destination) || "Hand".equals(destination) || "Library".equals(destination));
    }
}