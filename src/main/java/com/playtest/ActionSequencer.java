package com.playtest;

/** Pure sequencing heuristics (Gemini rubric). Adjusts only the ordering of engine-legal plays; never legality. */
public final class ActionSequencer {
    private ActionSequencer() { }

    /** Facts about one castable option, built from the AI's own hand and public battlefield only. */
    public record Play(boolean mainOne, boolean mainTwo, boolean ownTurn, boolean endStepBeforeMine, boolean stackEmpty,
                       boolean creature, boolean haste, boolean combatRelevant, boolean instantSpeed,
                       boolean selection, boolean landPlayAvailable, boolean interactionRisk, boolean tierOne,
                       boolean otherPlaysAvailable) { }

    /** Defender flags interaction with >=2 untapped U/W/B/R sources and at least one card in hand. */
    public static boolean interactionRisk(int untappedInteractiveSources, int handSize) {
        return untappedInteractiveSources >= 2 && handSize >= 1;
    }

    /** Blue-open counter risk: >=2 untapped blue sources and a card in hand. */
    public static boolean counterRisk(int untappedBlueSources, int handSize) {
        return untappedBlueSources >= 2 && handSize >= 1;
    }

    /** Priority delta in Forge comparator units (higher = earlier). */
    public static int priority(Play p) {
        int delta = 0;
        // Draw/scry before the land drop so new cards inform land choice.
        if (p.selection() && p.ownTurn() && p.mainOne() && p.landPlayAvailable() && p.stackEmpty()) delta += 6;
        // Main 1 only for plays that affect this combat; hold the rest until Main 2.
        if (p.mainOne() && p.ownTurn() && !p.combatRelevant() && !(p.creature() && p.haste()) && !p.selection()) delta -= 4;
        if (p.mainOne() && p.ownTurn() && (p.combatRelevant() || (p.creature() && p.haste()))) delta += 3;
        // Bait counters with lower-tier threats first.
        if (p.interactionRisk() && p.otherPlaysAvailable()) delta += p.tierOne() ? -5 : 3;
        // Instant-speed draw/tutor/flash waits for the end step before our turn.
        if (p.instantSpeed() && p.selection() && !p.endStepBeforeMine()) delta -= 3;
        if (p.instantSpeed() && p.endStepBeforeMine()) delta += 4;
        return delta;
    }

    /** Utility creatures and mana dorks do not chump unless facing lethal or at <=10 life. */
    public static boolean mayChump(boolean utilityOrDork, int life, int incoming) {
        return !utilityOrDork || incoming >= life || life <= 10;
    }
}
