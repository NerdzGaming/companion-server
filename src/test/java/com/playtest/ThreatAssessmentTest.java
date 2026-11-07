package com.playtest;

import com.playtest.ThreatAssessment.Kind;
import com.playtest.ThreatAssessment.PlayerState;

public final class ThreatAssessmentTest {
    static int n = 0;
    static void eq(Object want, Object got, String what) { n++; if (!want.equals(got)) throw new AssertionError(what + ": want " + want + " got " + got); }
    public static void main(String[] args) {
        PlayerState quiet = new PlayerState(4, 40, 4, 0, 0, false, 0, "R", false, 8);
        eq(0, ThreatAssessment.playerThreat(quiet, 4), "quiet player");
        eq(20, ThreatAssessment.playerThreat(new PlayerState(4, 40, 4, 0, 1, false, 0, "R", false, 8), 4), "+20 per draw engine");
        eq(6, ThreatAssessment.playerThreat(new PlayerState(6, 40, 4, 0, 0, false, 0, "R", false, 8), 4), "+3 per card above 4");
        eq(8, ThreatAssessment.playerThreat(new PlayerState(4, 40, 6, 0, 0, false, 0, "R", false, 8), 4), "+4 per mana above average");
        eq(30, ThreatAssessment.playerThreat(new PlayerState(4, 40, 4, 0, 0, false, 2, "R", false, 8), 4), "+15 per tutor");
        eq(15, ThreatAssessment.playerThreat(new PlayerState(4, 40, 4, 0, 0, true, 0, "R", false, 8), 4), "+15 synergy");
        eq(7, ThreatAssessment.playerThreat(new PlayerState(4, 40, 4, 7, 0, false, 0, "R", false, 8), 4), "+1 per power");
        eq(50, ThreatAssessment.playerThreat(new PlayerState(4, 40, 4, 0, 0, false, 0, "R", true, 8), 4), "+50 lethal");
        eq(10, ThreatAssessment.playerThreat(new PlayerState(4, 40, 4, 0, 0, false, 0, "{1}{G}{U}", false, 5), 4), "+10 U/G early");
        eq(0, ThreatAssessment.playerThreat(new PlayerState(4, 40, 4, 0, 0, false, 0, "{1}{G}{U}", false, 7), 4), "no U/G tax after turn 6");
        eq(100, ThreatAssessment.permanentThreat(Kind.COMBO, false, false, false), "combo bucket 1");
        eq(true, ThreatAssessment.permanentThreat(Kind.ENGINE, false, false, false) >= 65, "engine bucket 2");
        eq(90, ThreatAssessment.permanentThreat(Kind.STAX, false, true, false), "stax locking our win");
        eq(-20, ThreatAssessment.permanentThreat(Kind.STAX, false, false, true), "stax policing leader");
        eq(60, ThreatAssessment.deferForCombo(80, Kind.ENGINE, true), "engines wait for combo threat");
        eq(false, ThreatAssessment.removalAllowed(40, false, false, false), "patience threshold");
        eq(true, ThreatAssessment.removalAllowed(40, true, false, false), "end of turn allows removal");
        eq(0, ThreatAssessment.evaluatorBonus(40), "no boost below 65");
        eq(true, ThreatAssessment.evaluatorBonus(-20) < 0, "protect policing stax");
        eq(25, ThreatAssessment.attackPriority(15, "aggro", 30, "control"), "+10 chip at high-life control");
        eq(15, ThreatAssessment.attackPriority(15, "combo", 30, "control"), "combo bot gets no chip bonus");
        eq(true, ThreatAssessment.sandbag(40, 50, 30, 20, false, false), "sandbag when becoming archenemy");
        eq(false, ThreatAssessment.sandbag(40, 50, 30, 20, false, true), "deploy with protection up");
        eq(true, ThreatAssessment.archenemyDefense(80, 55), "defense mode at +25");
        eq(true, ThreatAssessment.stax("Blood Moon") && ThreatAssessment.drawEngine("Rhystic Study") && ThreatAssessment.apex("Tergrid, God of Fright"), "card lists");
        System.out.println(n + " threat assessment checks passed");
    }
}
