package com.playtest;

public final class StrategyWeightsTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        check(StrategyWeights.bonus(false, null, 2, 2) == 220, "complete combo cue");
        check(StrategyWeights.bonus(false, null, 3, 2) == 140, "one piece away");
        check(StrategyWeights.bonus(false, null, 0, 0) == 0, "no phantom combo");
        check(StrategyWeights.bonus(true, null, 0, 0) == 85, "Game Changer cue");
        check(StrategyWeights.bonus(false, 100000.0, 0, 0) == 8, "price bounded");
        check(StrategyWeights.bonus(false, Double.NaN, 0, 0) == 0, "unknown price neutral");
        check(180 + StrategyWeights.bonus(false, null, 2, 2) > 300, "protect combo creature over vanilla 6/6");
        check(140 + StrategyWeights.bonus(true, null, 0, 0) > 200, "Game Changer threat over vanilla permanent");
        System.out.println("8 strategy checks passed");
    }
}