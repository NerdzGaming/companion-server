package com.playtest;

import java.util.Map;

/** Protocol validation independent of Forge state; the engine offers the actual bounds. */
public final class ManaPaymentChoices {
    private ManaPaymentChoices() { }
    public static boolean shouldAnnounceX(boolean copied, boolean wrapper, boolean hasXCost, String definition) {
        return !copied && !wrapper && hasXCost && (definition.isEmpty() || "Count$xPaid".equals(definition));
    }
    public static boolean validLifeSelection(Map<String, Integer> offered, Map<String, Integer> selected, int maxLifePips) {
        long total = 0;
        for (var item : selected.entrySet()) {
            Integer count = item.getValue(), limit = offered.get(item.getKey());
            if (count == null || limit == null || count < 0 || count > limit) return false;
            total += count;
        }
        return total <= maxLifePips;
    }
    public static int lifeCost(Map<String, Integer> selected) {
        return Math.multiplyExact(selected.values().stream().mapToInt(Integer::intValue).sum(), 2);
    }
}