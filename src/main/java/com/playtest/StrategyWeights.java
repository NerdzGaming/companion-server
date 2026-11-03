package com.playtest;

/** Bounded strategic cues added to Forge's state-dependent score, not rules. */
public final class StrategyWeights {
    private StrategyWeights() { }
    public static int bonus(boolean gameChanger, Double priceUsd, int comboSize, int availablePieces) {
        int value = gameChanger ? 85 : 0;
        if (priceUsd != null && Double.isFinite(priceUsd) && priceUsd > 0)
            value += (int) Math.min(8, Math.log1p(priceUsd) * 1.5);
        if (comboSize >= 2 && availablePieces > 0) {
            value += 55;
            value += availablePieces >= comboSize ? 165 : availablePieces == comboSize - 1 ? 85 : 20;
        }
        return value;
    }
}