package com.playtest;

import java.util.Map;
import java.util.List;

public final class ManaPaymentChoicesTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        var sources = Map.of("w", 1, "r", 1, "g", 2, "ring", 3, "nonTap", 1);
        var tapSources = Map.of("w", 1, "r", 1, "g", 2, "ring", 3);
        check(!ManaPaymentChoices.validSourceSelection(sources, tapSources, List.of("w", "r")));
        check(ManaPaymentChoices.validSourceSelection(sources, tapSources, List.of("w", "g")));
        check(ManaPaymentChoices.validSourceSelection(sources, tapSources, List.of("ring")));
        check(ManaPaymentChoices.validSourceSelection(sources, tapSources, List.of("w", "nonTap")));
        check(!ManaPaymentChoices.validSourceSelection(sources, tapSources, List.of("w", "w")));
        check(!ManaPaymentChoices.validSourceSelection(sources, tapSources, List.of("fake")));
        var offered = Map.of("G/P", 2, "U/P", 1);
        check(ManaPaymentChoices.validLifeSelection(offered, Map.of("G/P", 2, "U/P", 1), 3));
        check(ManaPaymentChoices.lifeCost(Map.of("G/P", 2, "U/P", 1)) == 6);
        check(ManaPaymentChoices.validLifeSelection(offered, Map.of("U/P", 1), 1));
        check(!ManaPaymentChoices.validLifeSelection(offered, Map.of("U/P", 2), 3));
        check(!ManaPaymentChoices.validLifeSelection(offered, Map.of("fake", 1), 3));
        check(!ManaPaymentChoices.validLifeSelection(offered, Map.of("G/P", -1), 3));
        check(!ManaPaymentChoices.validLifeSelection(offered, Map.of("G/P", 2), 1));
        check(ManaPaymentChoices.validLifeSelection(offered, Map.of(), 0));
        check(ManaPaymentChoices.lifeCost(Map.of("U/P", 1)) == 2);
        check(ManaPaymentChoices.shouldAnnounceX(false, false, true, "Count$xPaid"));
        check(ManaPaymentChoices.shouldAnnounceX(false, false, true, ""));
        check(!ManaPaymentChoices.shouldAnnounceX(true, false, true, "Count$xPaid"));
        check(!ManaPaymentChoices.shouldAnnounceX(false, true, true, "Count$xPaid"));
        check(!ManaPaymentChoices.shouldAnnounceX(false, false, false, "Count$xPaid"));
        check(!ManaPaymentChoices.shouldAnnounceX(false, false, true, "Count$CardsInHand"));
        System.out.println("21 mana-source, Phyrexian selection and X announcement checks passed");
    }
}