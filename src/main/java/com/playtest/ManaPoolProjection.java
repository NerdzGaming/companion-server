package com.playtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/** Display-only projection; never recreate or mutate the engine's restricted mana objects. */
public final class ManaPoolProjection {
    private ManaPoolProjection() { }
    static List<Map<String, Object>> project(byte[] types, IntUnaryOperator amount) {
        String[] labels = { "W", "U", "B", "R", "G", "C" };
        if (types.length != labels.length) throw new IllegalArgumentException("Expected six mana types");
        List<Map<String, Object>> pool = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            int n = amount.applyAsInt(types[i]);
            if (n > 0) pool.add(Map.of("color", labels[i], "amount", n));
        }
        return pool;
    }
}