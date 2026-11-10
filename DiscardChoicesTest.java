package com.playtest;

import java.util.List;
import java.util.Set;

public final class DiscardChoicesTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        List<String> hand = List.of("Forest", "Ponder", "Grief");
        var thoughtseize = DiscardChoices.regular(List.of("Ponder", "Grief"), hand, 1, 1);
        check(thoughtseize.visible().equals(hand), "Thoughtseize must show the whole permitted hand");
        check(!RevealChoices.valid(Set.copyOf(thoughtseize.valid()), Set.of("Forest"), thoughtseize.min(), thoughtseize.max()), "Thoughtseize cannot discard a land");
        check(RevealChoices.valid(Set.copyOf(thoughtseize.valid()), Set.of("Grief"), thoughtseize.min(), thoughtseize.max()), "Human may choose Grief instead of Ponder");
        var duress = DiscardChoices.regular(List.of("Ponder"), hand, 1, 1);
        check(duress.visible().size() == 3 && duress.min() == 1 && duress.max() == 1, "One legal Duress card must retain full reveal context");
        var noLegal = DiscardChoices.regular(List.<String>of(), List.of("Forest"), 0, 0);
        check(noLegal.visible().equals(List.of("Forest")) && noLegal.max() == 0, "No legal discard still preserves visible hand");
        var optional = DiscardChoices.regular(List.of("Ponder"), hand, 0, 1);
        check(RevealChoices.valid(Set.copyOf(optional.valid()), Set.of(), optional.min(), optional.max()), "Optional discard may choose none");
        List<String> thirstHand = List.of("Sol Ring", "Forest", "Ponder");
        var artifact = DiscardChoices.unlessType(thirstHand, List.of("Sol Ring"), 2, true);
        check(artifact.valid().equals(List.of("Sol Ring")) && artifact.min() == 1 && artifact.max() == 1, "Thirst may discard exactly one artifact");
        check(artifact.visible().equals(thirstHand), "Alternative discard keeps other cards visible but ineligible");
        check(!RevealChoices.valid(Set.copyOf(artifact.valid()), Set.of("Forest"), artifact.min(), artifact.max()), "Nonartifact cannot pay the one-artifact alternative");
        var two = DiscardChoices.unlessType(thirstHand, List.of("Sol Ring"), 2, false);
        check(RevealChoices.valid(Set.copyOf(two.valid()), Set.of("Forest", "Ponder"), two.min(), two.max()), "Human may choose two nonartifacts despite holding an artifact");
        check(!RevealChoices.valid(Set.copyOf(two.valid()), Set.of("Forest"), two.min(), two.max()), "Two-card route cannot discard only one nonartifact");
        var noArtifact = DiscardChoices.unlessType(hand, List.of(), 2, false);
        check(noArtifact.min() == 2 && noArtifact.max() == 2, "Wrench Mind without an artifact requires two discards");
        var shortHand = DiscardChoices.unlessType(List.of("Forest"), List.of(), 2, false);
        check(shortHand.min() == 1 && shortHand.max() == 1, "Discard as many as possible with a short hand");
        System.out.println("13 discard choice checks passed");
    }
}