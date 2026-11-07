package com.playtest;

import com.playtest.ActionSequencer.Play;

public final class ActionSequencerTest {
    static int n = 0;
    static void eq(Object want, Object got, String what) { n++; if (!want.equals(got)) throw new AssertionError(what + ": want " + want + " got " + got); }
    static Play main1(boolean creature, boolean haste, boolean combat, boolean selection, boolean land) {
        return new Play(true, false, true, false, true, creature, haste, combat, false, selection, land, false, false, false);
    }
    public static void main(String[] args) {
        eq(6, ActionSequencer.priority(main1(false, false, false, true, true)), "cantrip before land drop");
        eq(-4, ActionSequencer.priority(main1(true, false, false, false, false)), "non-haste creature waits for main 2");
        eq(3, ActionSequencer.priority(main1(true, true, false, false, false)), "haste creature goes main 1");
        eq(3, ActionSequencer.priority(main1(false, false, true, false, false)), "anthem goes main 1");
        Play main2 = new Play(false, true, true, false, true, true, false, false, false, false, false, false, false, false);
        eq(0, ActionSequencer.priority(main2), "main 2 creature neutral");
        eq(-5, ActionSequencer.priority(new Play(false, true, true, false, true, true, false, false, false, false, false, true, true, true)), "tier-1 held vs open counter");
        eq(3, ActionSequencer.priority(new Play(false, true, true, false, true, true, false, false, false, false, false, true, false, true)), "bait first");
        eq(-3, ActionSequencer.priority(new Play(false, false, false, false, true, false, false, false, true, true, false, false, false, false)), "instant draw waits");
        eq(4, ActionSequencer.priority(new Play(false, false, false, true, true, false, false, false, true, true, false, false, false, false)), "instant draw at end step");
        eq(true, ActionSequencer.interactionRisk(2, 1), "2 open + card = risk");
        eq(false, ActionSequencer.interactionRisk(2, 0), "empty hand no risk");
        eq(false, ActionSequencer.interactionRisk(1, 5), "1 open no risk");
        eq(false, ActionSequencer.mayChump(true, 30, 5), "dork no chump at 30");
        eq(true, ActionSequencer.mayChump(true, 10, 5), "dork chumps at 10");
        eq(true, ActionSequencer.mayChump(true, 20, 20), "dork chumps vs lethal");
        eq(true, ActionSequencer.mayChump(false, 30, 5), "non-utility may chump");
        System.out.println(n + " action sequencer checks passed");
    }
}
