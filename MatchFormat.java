package com.playtest;

/** Explicit format wins; missing format is inferred from submitted deck sections, not engine libraries. */
public final class MatchFormat {
    private MatchFormat() { }

    public static String resolve(String explicit, String playerDeck, String opponentDeck) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        return commanderSection(playerDeck) || commanderSection(opponentDeck) ? "Commander" : "Constructed";
    }

    private static boolean commanderSection(String deck) {
        return deck != null && deck.lines().anyMatch(line -> line.trim().equalsIgnoreCase("[Commander]"));
    }
}