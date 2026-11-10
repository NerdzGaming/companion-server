package com.playtest;

/** Public movement descriptions never identify a card moving only between hidden zones. */
public final class ZoneMovement {
    private ZoneMovement() { }
    public static boolean publicZone(String zone) {
        return "Battlefield".equals(zone) || "Graveyard".equals(zone) || "Exile".equals(zone) || "Command".equals(zone) || "Stack".equals(zone);
    }
    public static String describe(String owner, String card, String from, String to) {
        String identity = publicZone(from) || publicZone(to) ? card : "a card";
        String origin = from == null ? "outside the game" : label(from);
        String destination = to == null ? "outside the game" : label(to);
        return owner + " moves " + identity + " from " + origin + " to " + destination + ".";
    }
    private static String label(String zone) {
        return "Command".equals(zone) ? "the command zone" : "the " + zone.toLowerCase(java.util.Locale.ROOT);
    }
}