package com.playtest;

import java.util.Map;
import java.util.Collection;
import java.util.HashSet;

/** Protocol validation independent of Forge state; the engine offers the actual bounds. */
public final class ManaPaymentChoices {
    private ManaPaymentChoices() { }
    /** Validate the whole batch before activating anything; one source pays {T} once. */
    public static boolean validSourceSelection(Map<String, Integer> offered, Map<String, Integer> tapSources, Collection<String> selected) {
        var keys = new HashSet<String>();
        var tapped = new HashSet<Integer>();
        for (String key : selected) {
            if (!offered.containsKey(key) || !keys.add(key)) return false;
            Integer source = tapSources.get(key);
            if (source != null && !tapped.add(source)) return false;
        }
        return true;
    }
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