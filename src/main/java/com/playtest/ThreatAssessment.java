package com.playtest;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * Pure Commander threat rubric (no Forge types) so it is testable without the engine.
 * Scores are recomputed from current public state only — no vindictive memory.
 */
public final class ThreatAssessment {
    private ThreatAssessment() { }

    /** Public per-opponent state. Hidden zones are represented only by counts. */
    public record PlayerState(int handCount, int life, int mana, int creaturePower, int drawEngines,
                              boolean synergyEngine, int recentTutors, String commanderColors,
                              boolean lethalOnBoard, int turn) { }

    public enum Kind { COMBO, LETHAL_ATTACKER, ULTIMATE_WALKER, ENGINE, APEX_COMMANDER, STAX, OTHER }

    static final Set<String> DRAW_ENGINES = Set.of("rhystic study", "the one ring", "mystic remora", "esper sentinel",
        "tymna the weaver", "aesi, tyrant of gyre strait", "the great henge", "sylvan library", "phyrexian arena",
        "guardian project", "beast whisperer", "consecrated sphinx", "necropotence", "bolas's citadel", "dark confidant",
        "toski, bearer of secrets", "kinnan, bonder prodigy", "smothering tithe", "remora", "skullclamp", "jeska's will");
    static final Set<String> MANA_ENGINES = Set.of("smothering tithe", "kinnan, bonder prodigy", "cryptolith rite",
        "gaea's cradle", "itlimoc, cradle of the sun", "nykthos, shrine to nyx", "mana reflection", "zendikar resurgent",
        "mirari's wake", "seedborn muse", "dockside extortionist", "carpet of flowers", "mana vault", "grim monolith");
    static final Set<String> APEX_COMMANDERS = Set.of("tergrid, god of fright", "kinnan, bonder prodigy",
        "fynn, the fangbearer", "najeela, the blade-blossom", "thrasios, triton hero", "winota, joiner of forces",
        "urza, lord high artificer", "yuriko, the tiger's shadow", "korvold, fae-cursed king", "atraxa, grand unifier",
        "the gitrog monster", "krenko, mob boss", "edgar markov", "meren of clan nel toth", "prossh, skyraider of kher");
    static final Set<String> STAX = Set.of("blood moon", "magus of the moon", "rule of law", "deafening silence",
        "drannith magistrate", "liesa, shroud of dusk", "rest in peace", "grafdigger's cage", "cursed totem",
        "torpor orb", "hushbringer", "collector ouphe", "null rod", "stony silence", "winter orb", "static orb",
        "aven mindcensor", "opposition agent", "notion thief", "archon of emeria", "thalia, guardian of thraben",
        "trinisphere", "sphere of resistance", "humility", "back to basics", "armageddon", "linvala, keeper of silence");

    static String key(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); }
    public static boolean drawEngine(String name) { return DRAW_ENGINES.contains(key(name)); }
    public static boolean manaEngine(String name) { return MANA_ENGINES.contains(key(name)); }
    public static boolean apex(String name) { return APEX_COMMANDERS.contains(key(name)); }
    public static boolean stax(String name) { return STAX.contains(key(name)); }

    /** Section 2: player threat score, 0–100+. */
    public static int playerThreat(PlayerState p, double tableAverageMana) {
        int score = 20 * p.drawEngines();
        score += 3 * Math.max(0, p.handCount() - 4);
        score += (int) Math.round(4 * Math.max(0, p.mana() - tableAverageMana));
        score += 15 * Math.max(0, p.recentTutors());
        if (p.synergyEngine()) score += 15;
        score += Math.max(0, p.creaturePower());
        if (p.lethalOnBoard()) score += 50;
        String colors = p.commanderColors() == null ? "" : p.commanderColors().toUpperCase(Locale.ROOT);
        if (p.turn() <= 6 && (colors.contains("U") || colors.contains("G"))) score += 10;
        return score;
    }

    public static double averageMana(Collection<PlayerState> players) {
        return players.stream().mapToInt(PlayerState::mana).average().orElse(0);
    }

    /**
     * Section 4: permanent threat score.
     * @param ownerIsArchenemy owner holds the highest player-threat score
     * @param locksBotWin the stax piece stops the bot's own win condition
     * @param policesLeader the stax piece mostly constrains the current archenemy
     */
    public static int permanentThreat(Kind kind, boolean ownerIsArchenemy, boolean locksBotWin, boolean policesLeader) {
        return switch (kind) {
            case COMBO, LETHAL_ATTACKER -> 100;
            case ULTIMATE_WALKER -> 90;
            case APEX_COMMANDER -> ownerIsArchenemy ? 84 : 75;
            case ENGINE -> ownerIsArchenemy ? 80 : 68;
            case STAX -> locksBotWin ? 90 : policesLeader ? -20 : 40;
            case OTHER -> ownerIsArchenemy ? 35 : 20;
        };
    }

    /** Bucket 2 engines wait while another opponent represents a Bucket 1 win. */
    public static int deferForCombo(int score, Kind kind, boolean otherOpponentHasBucketOne) {
        boolean bucketTwo = kind == Kind.ENGINE || kind == Kind.APEX_COMMANDER;
        return bucketTwo && otherOpponentHasBucketOne ? Math.min(score, 60) : score;
    }

    /** Patience threshold: spot removal below 65 only at end of turn, discarding, or a tempo-to-win play. */
    public static boolean removalAllowed(int score, boolean endOfTurn, boolean discarding, boolean tempoWin) {
        return score >= 65 || endOfTurn || discarding || tempoWin;
    }

    /** Converts a threat score into an evaluator bonus; below-threshold pieces are not boosted, policing stax is protected. */
    public static int evaluatorBonus(int score) {
        if (score < 0) return -120;
        if (score < 65) return 0;
        return (score - 50) * 4; // 65 → 60, 100 → 200
    }

    /** Section 3.2: aggro/midrange chip damage at high-life control/combo opponents. */
    public static int attackPriority(int playerThreat, String botArchetype, int targetLife, String targetArchetype) {
        int score = playerThreat;
        boolean pressure = "aggro".equals(botArchetype) || "midrange".equals(botArchetype);
        boolean slow = "control".equals(targetArchetype) || "combo".equals(targetArchetype);
        if (pressure && slow && targetLife >= 30) score += 10;
        return score;
    }

    /** Section 5: hold a non-lethal threat when it would make the bot archenemy without protection. */
    public static boolean sandbag(int botScore, int bestOpponent, int secondOpponent, int addedThreat, boolean winsThisCycle, boolean protectionUp) {
        if (winsThisCycle || protectionUp) return false;
        boolean secondHighest = botScore < bestOpponent && botScore >= secondOpponent;
        return secondHighest && botScore + addedThreat > bestOpponent;
    }

    /** Section 5: archenemy defense when leading the table by 25+. */
    public static boolean archenemyDefense(int botScore, int bestOpponent) { return botScore - bestOpponent >= 25; }
}
