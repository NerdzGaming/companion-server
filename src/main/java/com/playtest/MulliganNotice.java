package com.playtest;

/** Public London mulligan counts only; never includes hand identities or private scoring. */
public final class MulliganNotice {
    public static int keptCount(int handSize, int redraws, int freeMulligans) {
        return Math.max(0, handSize - Math.max(0, redraws - Math.max(0, freeMulligans)));
    }
    public static String text(String player, int handSize, int redraws, int freeMulligans) {
        int used = Math.min(Math.max(0, redraws), Math.max(0, freeMulligans));
        return player + " takes a mulligan to " + keptCount(handSize, redraws, freeMulligans)
            + " cards" + (used > 0 ? " (" + used + " free used)" : "") + ".";
    }
}