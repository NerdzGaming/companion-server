package com.playtest;

/** Runs the dependency-free regression checks during Maven's test phase as well as standalone validation. */
public final class PolicyTestSuite {
    public static void main(String[] args) {
        ActionSequencerTest.main(args);
        DiscardChoicesTest.main(args);
        HumanChoicesTest.main(args);
        LibraryVisibilityTest.main(args);
        ManaPaymentChoicesTest.main(args);
        ManaPoolProjectionTest.main(args);
        MatchFormatTest.main(args);
        MatchNamesTest.main(args);
        OpeningHandPolicyTest.main(args);
        RemovalPolicyTest.main(args);
        RevealChoicesTest.main(args);
        StrategyWeightsTest.main(args);
        ThreatAssessmentTest.main(args);
        TransportAccessTest.main(args);
    }
}