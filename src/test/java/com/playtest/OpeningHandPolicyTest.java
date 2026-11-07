package com.playtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.playtest.OpeningHandPolicy.*;

public final class OpeningHandPolicyTest {
    static int checks;
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static CardInfo land(int id, int mask) { return new CardInfo(id, "Land" + id, true, 0, mask, List.of(), false, false, false, false, false, false, false, false, false, false); }
    static CardInfo spell(int id, int cmc, List<Integer> pips, boolean creature, boolean ramp, boolean removal, boolean counter, boolean pregame, boolean engine, boolean legendary) {
        return new CardInfo(id, "Spell" + id, false, cmc, 0, pips, creature, ramp, removal || counter, removal, false, false, counter, pregame, engine, legendary);
    }
    static CardInfo vanilla(int id, int cmc) { return spell(id, cmc, List.of(), false, false, false, false, false, false, false); }
    static Profile profile(Archetype archetype) { return new Profile(60, Map.of(1, 24), archetype); }
    static int score(List<CardInfo> h, boolean play) { return OpeningHandPolicy.score(h, h, profile(Archetype.MIDRANGE), play, Archetype.UNKNOWN, new ArrayList<>()); }
    public static void main(String[] args) {
        check(threshold(0) == 65, "seven-card threshold 65");
        check(threshold(1) == 55, "six-card threshold 55");
        check(threshold(2) == 40, "five-card threshold 40");
        check(threshold(3) == 25, "four-card threshold 25");
        check(threshold(4) == 0, "three-card forced keep");
        var noLands = List.of(vanilla(1, 1), vanilla(2, 1));
        check(score(noLands, true) == 0, "zero lands auto zero");
        check(score(List.of(land(1,1), land(2,1)), true) == 0, "all lands auto zero");
        check(decide(noLands, profile(Archetype.MIDRANGE), true, 4, 0, Archetype.UNKNOWN).keep(), "always keep after four redraws");
        var one = List.of(land(1,1), vanilla(2,1), vanilla(3,1));
        check(score(one, false) == 60, "one land draw 20+25+15");
        check(score(one, true) == 35, "one land on play no +25");
        check(score(List.of(land(1,1),land(2,1),vanilla(3,3)),true) == 60, "two-land play base");
        check(score(List.of(land(1,1),land(2,1),vanilla(3,3)),false) == 70, "two-land draw base");
        check(score(List.of(land(1,1),land(2,1),land(3,1),vanilla(4,3)),true) == 85, "three-land base");
        check(score(List.of(land(1,1),land(2,1),vanilla(3,4)),true) == 40, "clunky -20");
        var proactive = List.of(land(1,1),land(2,1),spell(3,2,List.of(1),true,false,false,false,false,false,false));
        check(score(proactive,true) == 85, "early +15 proactive +10");
        var reactive = List.of(land(1,1),land(2,1),spell(3,2,List.of(1),false,false,true,false,false,false,false));
        check(score(reactive,false) == 95, "draw cheap interaction +10");
        check(OpeningHandPolicy.score(reactive,reactive,profile(Archetype.MIDRANGE),true,Archetype.AGGRO,new ArrayList<>()) == 90, "anti-aggro +15");
        var countermagic = List.of(land(1,1),land(2,1),spell(3,2,List.of(1),false,false,false,true,false,false,false));
        check(OpeningHandPolicy.score(countermagic,countermagic,profile(Archetype.MIDRANGE),true,Archetype.COMBO,new ArrayList<>()) == 90, "anti-combo +15");
        check(score(List.of(land(1,1),land(2,1),spell(3,2,List.of(),false,false,false,false,false,true,false)),true) == 90, "engine +15");
        check(score(List.of(land(1,1),land(2,1),spell(3,3,List.of(),false,false,false,false,true,false,false)),true) == 75, "pregame +15");
        check(Math.abs(probability(53,20,2) - (1 - 33.0*32/(53*52))) < 1e-10, "hypergeometric no replacement");
        check(probability(53,0,3) == 0, "zero matching sources");
        check(probability(53,53,0) == 0, "no draw on turn one on play");
        check(probability(53,20,1,2) == 0, "two missing pips need two sources");
        check(Math.abs(probability(53,20,2,2) - 20.0*19/(53*52)) < 1e-10, "double-pip probability");
        var uu = spell(8,2,List.of(2,2),false,false,false,false,false,false,false);
        check(!coloursAvailable(uu,List.of(land(1,3),land(2,1),uu)), "dual source not double counted for UU");
        check(coloursAvailable(uu,List.of(land(1,3),land(2,2),uu)), "two blue sources support UU");
        var wrongColour = List.of(land(1,1),land(2,1),spell(3,2,List.of(2),false,false,false,false,false,false,false));
        check(score(wrongColour,true) == 45, "missing colour probability <50% subtracts 15");
        var seven = List.of(land(1,1),land(2,1),land(3,1),land(4,1),vanilla(5,1),vanilla(6,5),vanilla(7,3));
        var kept = decide(seven,profile(Archetype.MIDRANGE),true,1,1,Archetype.UNKNOWN);
        check(kept.kept().size() == 6 && kept.bottom().size() == 1 && kept.bottom().get(0).land(), "virtual six bottoms excess land first");
        var two = decide(seven,profile(Archetype.MIDRANGE),true,2,2,Archetype.UNKNOWN);
        check(two.bottom().get(1).cmc() == 5, "late drop after excess land");
        var free = decide(seven,profile(Archetype.MIDRANGE),true,1,0,Archetype.UNKNOWN);
        check(free.kept().size() == 7 && free.bottom().isEmpty() && free.threshold() == 55, "free Commander redraw retains seven but decays threshold");
        var aggro = decide(seven,profile(Archetype.AGGRO),true,2,2,Archetype.UNKNOWN);
        check(aggro.kept().stream().filter(CardInfo::land).count() == 2, "aggro retains two lands");
        var colours = List.of(land(1,1),land(2,1),land(3,1),land(4,2),spell(5,1,List.of(2),false,false,false,false,false,false,false));
        check(decide(colours,profile(Archetype.MIDRANGE),true,1,1,Archetype.UNKNOWN).kept().stream().anyMatch(c -> c.id() == 4), "preserve sole required colour");
        check(infer(proactive,false) == Archetype.AGGRO, "infer low-curve creatures");
        check(infer(reactive,false) == Archetype.CONTROL, "infer interaction control");
        check(infer(proactive,true) == Archetype.COMBO, "combo relationships win inference");
        var off = List.of(land(1,1),land(2,1),land(3,1),spell(4,2,List.of(2),false,false,false,false,false,false,false),vanilla(5,5),vanilla(6,1),vanilla(7,3));
        check(decide(off,profile(Archetype.MIDRANGE),true,1,1,Archetype.UNKNOWN).bottom().get(0).id() == 4, "off-colour before five-mana drop");
        var legend = spell(4,3,List.of(),true,false,false,false,false,false,true);
        var legend2 = new CardInfo(5,legend.name(),false,3,0,List.of(),true,false,false,false,false,false,false,false,false,true);
        var duplicates = List.of(land(1,1),land(2,1),land(3,1),legend,legend2,vanilla(6,1),vanilla(7,2));
        check(decide(duplicates,profile(Archetype.MIDRANGE),true,1,1,Archetype.UNKNOWN).bottom().get(0).legendary(), "redundant legends before low impact");
        for (int n = 0; n <= 7; n++) {
            var d = decide(seven,profile(Archetype.MIDRANGE),true,n,n,Archetype.UNKNOWN);
            check(d.kept().size() == 7-n && d.bottom().size() == n, "exact return count " + n);
            check(d.bottom().stream().map(CardInfo::id).distinct().count() == n, "unique bottom ids " + n);
        }
        check(seven.size() == 7, "virtual scoring never mutates the drawn hand");
        System.out.println(checks + " opening-hand policy checks passed");
    }
}