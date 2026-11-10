package com.playtest;

public final class ZoneMovementTest {
    public static void main(String[] args) {
        String[] zones = {"Hand", "Library", "Battlefield", "Graveyard", "Exile", "Command", "Stack"};
        int checks = 0;
        for (String from : zones) for (String to : zones) {
            if (from.equals(to)) continue;
            String text = ZoneMovement.describe("opponent", "Secret Card", from, to);
            if (text.isBlank() || !text.contains(" from ") || !text.contains(" to ")) throw new AssertionError("Missing transition " + from + " to " + to);
            boolean visible = ZoneMovement.publicZone(from) || ZoneMovement.publicZone(to);
            if (text.contains("Secret Card") != visible) throw new AssertionError("Visibility mismatch " + from + " to " + to);
            checks++;
        }
        if (!ZoneMovement.describe("player", "Forest", "Hand", "Library").equals("player moves a card from the hand to the library.")) throw new AssertionError("Hidden movement leaks identity");
        if (!ZoneMovement.describe("player", "Forest", "Hand", "Stack").equals("player moves Forest from the hand to the stack.")) throw new AssertionError("Cast transition missing");
        if (!ZoneMovement.describe("player", "Ponder", "Stack", "Graveyard").equals("player moves Ponder from the stack to the graveyard.")) throw new AssertionError("Resolution transition missing");
        System.out.println((checks + 3) + " zone movement checks passed");
    }
}