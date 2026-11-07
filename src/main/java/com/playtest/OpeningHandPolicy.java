package com.playtest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Pure opening-hand policy. Receives aggregate deck counts, never a library or hidden opponent cards. */
public final class OpeningHandPolicy {
    public enum Archetype { AGGRO, MIDRANGE, CONTROL, COMBO, UNKNOWN }
    public record CardInfo(int id, String name, boolean land, int cmc, int sources,
                           List<Integer> pips, boolean creature, boolean ramp, boolean interaction,
                           boolean removal, boolean wipe, boolean discard, boolean counter,
                           boolean pregame, boolean engine, boolean legendary) {
        public CardInfo { pips = List.copyOf(pips); }
    }
    public record Profile(int size, Map<Integer, Integer> sources, Archetype archetype) {
        public Profile { sources = Map.copyOf(sources); }
    }
    public record Result(int score, int threshold, boolean keep, List<CardInfo> kept,
                         List<CardInfo> bottom, List<String> reasons) { }
    private OpeningHandPolicy() { }

    public static int threshold(int mulligans) {
        return switch (Math.max(0, mulligans)) { case 0 -> 65; case 1 -> 55; case 2 -> 40; case 3 -> 25; default -> 0; };
    }
    public static double probability(int population, int successes, int draws) {
        return probability(population, successes, draws, 1);
    }
    public static double probability(int population, int successes, int draws, int needed) {
        if (needed <= 0) return 1;
        if (population <= 0 || successes <= 0 || draws <= 0) return 0;
        successes = Math.min(successes, population);
        draws = Math.min(draws, population);
        double hit = 0;
        for (int k = needed; k <= Math.min(draws, successes); k++)
            hit += combinations(successes, k) * combinations(population - successes, draws - k) / combinations(population, draws);
        return Math.min(1, hit);
    }
    private static double combinations(int n, int k) {
        if (k < 0 || k > n) return 0;
        double value = 1;
        for (int i = 1; i <= Math.min(k, n - k); i++) value *= (n - i + 1) / (double) i;
        return value;
    }
    /** Bipartite source assignment: a dual land cannot pay two pips simultaneously. */
    public static boolean coloursAvailable(CardInfo spell, List<CardInfo> hand) {
        List<Integer> sources = hand.stream().filter(CardInfo::land).map(CardInfo::sources).toList();
        return assign(spell.pips(), 0, sources, 0);
    }
    private static boolean assign(List<Integer> pips, int at, List<Integer> sources, int used) {
        if (at == pips.size()) return true;
        for (int i = 0; i < sources.size(); i++) {
            if ((used & (1 << i)) == 0 && (pips.get(at) & sources.get(i)) != 0
                && assign(pips, at + 1, sources, used | (1 << i))) return true;
        }
        return false;
    }
    private static boolean early(CardInfo c, List<CardInfo> hand, int max) {
        return !c.land() && c.cmc() <= max && c.cmc() <= hand.stream().filter(CardInfo::land).count()
            && coloursAvailable(c, hand);
    }
    public static Result decide(List<CardInfo> fullHand, Profile profile, boolean play,
                                int mulligans, int bottomCount, Archetype opponent) {
        List<CardInfo> kept = new ArrayList<>(fullHand), bottom = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (int n = 0; n < Math.min(Math.max(0, bottomCount), fullHand.size()); n++) {
            List<CardInfo> current = List.copyOf(kept);
            int rank = current.stream().mapToInt(c -> bottomRank(c, current, profile.archetype(), play)).min().orElse(4);
            CardInfo chosen = current.stream().filter(c -> bottomRank(c, current, profile.archetype(), play) == rank)
                .max(Comparator.comparingInt((CardInfo c) -> {
                    List<CardInfo> candidate = new ArrayList<>(current); candidate.remove(c);
                    return score(candidate, fullHand, profile, play, opponent, new ArrayList<>());
                }).thenComparingInt(c -> offRole(c, play)).thenComparingInt(CardInfo::cmc).thenComparingInt(CardInfo::id)).orElseThrow();
            kept.remove(chosen); bottom.add(chosen);
            reasons.add("Bottom " + chosen.name() + ": " + List.of("excess land", "off-colour", "late drop", "redundant", "low impact/off-role").get(rank));
        }
        int score = score(kept, fullHand, profile, play, opponent, reasons);
        int threshold = threshold(mulligans);
        return new Result(score, threshold, mulligans >= 4 || score >= threshold,
            List.copyOf(kept), List.copyOf(bottom), List.copyOf(reasons));
    }
    private static int bottomRank(CardInfo c, List<CardInfo> hand, Archetype archetype, boolean play) {
        long lands = hand.stream().filter(CardInfo::land).count();
        if (c.land()) {
            int desired = archetype == Archetype.AGGRO ? 2 : 3;
            if (lands > desired) {
                List<CardInfo> rest = new ArrayList<>(hand); rest.remove(c);
                boolean losesColour = hand.stream().filter(s -> !s.land()).anyMatch(s -> coloursAvailable(s, hand) && !coloursAvailable(s, rest));
                if (!losesColour) return 0;
            }
            return 4;
        }
        if (!coloursAvailable(c, hand)) return 1;
        if (c.cmc() >= 5) return 2;
        if ((c.legendary() || c.cmc() >= 4) && hand.stream().filter(s -> s.name().equals(c.name())).count() > 1) return 3;
        return 4;
    }
    private static int offRole(CardInfo c, boolean play) {
        if (c.land()) return -10;
        int lowImpact = c.cmc();
        if (!play && c.creature() && c.cmc() >= 3) lowImpact += 5;
        if (play && c.interaction() && !c.engine()) lowImpact += 3;
        if (c.engine() || c.pregame() || c.ramp()) lowImpact -= 5;
        return lowImpact;
    }
    static int score(List<CardInfo> hand, List<CardInfo> drawn, Profile profile, boolean play,
                     Archetype opponent, List<String> reasons) {
        int lands = (int) hand.stream().filter(CardInfo::land).count();
        if (lands == 0 || lands == hand.size()) { reasons.add("No balanced land/spell mix"); return 0; }
        int score = switch (lands) { case 1 -> 20; case 2 -> play ? 60 : 70; case 3, 4 -> 85; case 5 -> 45; default -> 15; };
        if (lands == 1 && !play && hand.stream().filter(c -> early(c, hand, 1)).count() >= 2) { score += 25; reasons.add("+25 one-land draw support"); }
        if (hand.stream().anyMatch(c -> early(c, hand, 2))) { score += 15; reasons.add("+15 early action"); }
        if (hand.stream().filter(c -> !c.land()).allMatch(c -> c.cmc() >= 4) && hand.stream().noneMatch(CardInfo::ramp)) { score -= 20; reasons.add("-20 clunky curve"); }
        if (play && hand.stream().anyMatch(c -> early(c, hand, 2) && (c.creature() || c.ramp()))) { score += 10; reasons.add("+10 proactive on play"); }
        if (!play && hand.stream().anyMatch(c -> early(c, hand, 2) && c.interaction())) { score += 10; reasons.add("+10 interaction on draw"); }
        for (CardInfo c : hand) {
            if (c.land() || c.cmc() > 3 || coloursAvailable(c, hand)) continue;
            int draws = Math.max(0, c.cmc() - (play ? 1 : 0));
            // Known submitted-deck statistics minus the current drawn seven; no game-library access.
            boolean risky = false;
            for (int pip : c.pips()) {
                int have = (int) hand.stream().filter(s -> s.land() && (s.sources() & pip) != 0).count();
                long need = c.pips().stream().filter(p -> p == pip).count();
                if (have >= need) continue;
                int total = profile.sources().entrySet().stream().filter(e -> (e.getKey() & pip) != 0).mapToInt(Map.Entry::getValue).sum();
                int known = (int) drawn.stream().filter(s -> s.land() && (s.sources() & pip) != 0).count();
                if (probability(profile.size() - drawn.size(), Math.max(0, total - known), draws, (int) need - have) < .5) risky = true;
            }
            if (risky) { score -= 15; reasons.add("-15 colour risk: " + c.name()); }
        }
        if (opponent == Archetype.AGGRO && hand.stream().anyMatch(c -> (c.removal() && c.cmc() <= 2) || c.wipe())) { score += 15; reasons.add("+15 anti-aggro"); }
        if (opponent == Archetype.COMBO && hand.stream().anyMatch(c -> c.cmc() <= 2 && (c.discard() || c.counter()))) { score += 15; reasons.add("+15 anti-combo"); }
        if (hand.stream().anyMatch(c -> c.pregame() || (c.engine() && early(c, hand, 2)))) { score += 15; reasons.add("+15 opening engine/pregame"); }
        return Math.max(0, Math.min(100, score));
    }
    public static Archetype infer(List<CardInfo> deck, boolean combo) {
        if (combo) return Archetype.COMBO;
        List<CardInfo> spells = deck.stream().filter(c -> !c.land()).toList();
        if (spells.isEmpty()) return Archetype.MIDRANGE;
        double cmc = spells.stream().mapToInt(CardInfo::cmc).average().orElse(3);
        long creatures = spells.stream().filter(CardInfo::creature).count();
        if (cmc <= 2.5 && creatures >= spells.size() * .5) return Archetype.AGGRO;
        if (spells.stream().filter(CardInfo::interaction).count() >= spells.size() * .3 && creatures < spells.size() * .35) return Archetype.CONTROL;
        return Archetype.MIDRANGE;
    }
}