package com.playtest;

/** Presentation only: engine seat identifiers remain player/opponent. */
public final class MatchNames {
    private static final String[] AI_NAMES = { "Spike", "Timmy", "Johnny" };
    private MatchNames() { }
    public static String aiName(int index) { return AI_NAMES[Math.floorMod(index, AI_NAMES.length)]; }
}