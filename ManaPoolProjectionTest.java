package com.playtest;

import java.util.List;
import java.util.Map;

public final class ManaPoolProjectionTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        // Pinned ManaAtom.COLORLESS is 32, distinct from MagicColor.COLORLESS (0).
        byte[] types = { 1, 2, 4, 8, 16, 32 };
        check(ManaPoolProjection.project(types, key -> key == 32 ? 2 : 0)
            .equals(List.of(Map.of("color", "C", "amount", 2))));
        check(ManaPoolProjection.project(types, key -> key == 32 ? 1 : 0)
            .equals(List.of(Map.of("color", "C", "amount", 1))));
        check(ManaPoolProjection.project(types, key -> 0).isEmpty());
        check(ManaPoolProjection.project(types, key -> key == 16 ? 1 : key == 32 ? 2 : 0)
            .equals(List.of(Map.of("color", "G", "amount", 1), Map.of("color", "C", "amount", 2))));
        System.out.println("4 mana projection checks passed");
    }
}