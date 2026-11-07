package com.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable match-local catalog; evaluates simulated state, never the human's hidden zones. */
public final class OpponentStrategy {
    record Metadata(boolean changer, Double price) { }
    final Map<String, Metadata> metadata = new HashMap<>();
    final Map<String, List<Set<String>>> catalog = new HashMap<>();
    final Set<Set<String>> ownCombos = new HashSet<>();

    static String key(String name) { return name.trim().toLowerCase(Locale.ROOT); }
    public OpponentStrategy(JsonNode root) {
        if (root.path("version").asInt() != 1) return;
        for (JsonNode card : root.path("cards")) {
            String name = card.path("name").asText("");
            if (!name.isBlank()) metadata.put(key(name), new Metadata(card.path("gameChanger").asBoolean(), card.path("priceUsd").isNumber() ? card.path("priceUsd").asDouble() : null));
        }
        for (JsonNode combo : root.path("combos")) {
            Set<String> names = new HashSet<>();
            combo.forEach(name -> names.add(key(name.asText())));
            if (names.size() < 2) continue;
            for (String name : names) catalog.computeIfAbsent(name, unused -> new ArrayList<>()).add(Set.copyOf(names));
        }
        for (JsonNode combo : root.path("ownCombos")) {
            Set<String> names = new HashSet<>(); combo.forEach(name -> names.add(key(name.asText())));
            if (names.size() >= 2) ownCombos.add(Set.copyOf(names));
        }
    }

    public int bonus(Card card) {
        if (card == null || card.isFaceDown() || card.getGame() == null || card.getController() == null) return 0;
        Player ai = null;
        for (Player player : card.getGame().getPlayers()) if (player.getName().equals("opponent")) ai = player;
        if (ai == null || !card.getView().canBeShownTo(ai.getView())) return 0;
        boolean own = card.getController().equals(ai);
        // Costs/target selection in the human bridge must keep the original evaluator.
        Player priority = card.getGame().getPhaseHandler().getPriorityPlayer();
        if (priority != null && !priority.equals(ai)) return 0;
        String name = key(card.getName());
        Metadata data = metadata.get(name);
        Set<String> available = new HashSet<>();
        Player owner = card.getController();
        for (Card permanent : owner.getCardsIn(ZoneType.Battlefield)) if (!permanent.isFaceDown()) available.add(key(permanent.getName()));
        if (own) {
            for (Card hand : ai.getCardsIn(ZoneType.Hand)) if (!hand.isFaceDown()) available.add(key(hand.getName()));
            for (Card commander : ai.getCardsIn(ZoneType.Command)) if (!commander.isFaceDown()) available.add(key(commander.getName()));
        }
        // The evaluated candidate itself is an offered/visible card, including tutor candidates.
        available.add(name);
        int size = 0, count = 0, best = 0;
        for (Set<String> combo : catalog.getOrDefault(name, List.of())) {
            if (own && !ownCombos.contains(combo)) continue;
            int present = 0; for (String piece : combo) if (available.contains(piece)) present++;
            // Opponent combo threat requires at least two public pieces, not deck-list knowledge.
            if (!own && present < 2) continue;
            int score = StrategyWeights.bonus(false, null, combo.size(), present);
            if (score > best) { best = score; size = combo.size(); count = present; }
        }
        int base = StrategyWeights.bonus(data != null && data.changer(), data == null ? null : data.price(), size, count);
        if (own || !card.isInZone(ZoneType.Battlefield)) return base;
        try { return base + threatBonus(card, ai, size >= 2 && count >= size); } catch (RuntimeException ignored) { return base; }
    }

    /** Commander threat rubric applied to opposing face-up permanents only (removal/target valuation). */
    private int threatBonus(Card card, Player ai, boolean completeCombo) {
        var game = card.getGame();
        int turn = game.getPhaseHandler().getTurn();
        List<Player> opponents = new ArrayList<>();
        for (Player p : game.getPlayers()) if (!p.equals(ai) && !p.hasLost()) opponents.add(p);
        Map<Player, ThreatAssessment.PlayerState> states = new HashMap<>();
        for (Player p : opponents) states.put(p, publicState(p, ai, turn));
        double avg = ThreatAssessment.averageMana(states.values());
        Player archenemy = null; int best = Integer.MIN_VALUE;
        for (Player p : opponents) { int s = ThreatAssessment.playerThreat(states.get(p), avg); if (s > best) { best = s; archenemy = p; } }
        Player owner = card.getController();
        boolean ownerLeads = owner.equals(archenemy);
        String name = card.getName();
        ThreatAssessment.Kind kind;
        var combat = game.getCombat();
        if (completeCombo) kind = ThreatAssessment.Kind.COMBO;
        else if (combat != null && combat.isAttacking(card) && attackingPower(combat, game) >= ai.getLife()) kind = ThreatAssessment.Kind.LETHAL_ATTACKER;
        else if (card.isPlaneswalker() && card.getCounters(forge.game.card.CounterEnumType.LOYALTY) >= 7) kind = ThreatAssessment.Kind.ULTIMATE_WALKER;
        else if (card.isCommander() && ThreatAssessment.apex(name)) kind = ThreatAssessment.Kind.APEX_COMMANDER;
        else if (ThreatAssessment.drawEngine(name) || ThreatAssessment.manaEngine(name)) kind = ThreatAssessment.Kind.ENGINE;
        else if (ThreatAssessment.stax(name)) kind = ThreatAssessment.Kind.STAX;
        else kind = ThreatAssessment.Kind.OTHER;
        // Stax that sits with a non-leader polices the archenemy; our own assembled combo pieces mean it may lock our win.
        boolean locksBotWin = kind == ThreatAssessment.Kind.STAX && !ownCombos.isEmpty();
        boolean policesLeader = kind == ThreatAssessment.Kind.STAX && archenemy != null && !ownerLeads && opponents.size() > 1;
        int score = ThreatAssessment.permanentThreat(kind, ownerLeads, locksBotWin, policesLeader);
        boolean otherBucketOne = false;
        for (Player p : opponents) if (!p.equals(owner) && states.get(p).lethalOnBoard()) otherBucketOne = true;
        score = ThreatAssessment.deferForCombo(score, kind, otherBucketOne);
        return ThreatAssessment.evaluatorBonus(score);
    }

    private static int attackingPower(forge.game.combat.Combat combat, forge.game.Game game) {
        int total = 0;
        for (Card c : combat.getAttackers()) total += Math.max(0, c.getNetCombatDamage());
        return total;
    }

    /** Public information only: battlefield, hand count, life. */
    private static ThreatAssessment.PlayerState publicState(Player p, Player ai, int turn) {
        int mana = 0, power = 0, draw = 0, potential = 0; String colors = "";
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isFaceDown()) continue;
            if (c.isLand() || !c.getManaAbilities().isEmpty()) mana++;
            if (c.isCreature()) { power += Math.max(0, c.getNetPower()); if (!c.isTapped()) potential += Math.max(0, c.getNetCombatDamage()); }
            if (ThreatAssessment.drawEngine(c.getName())) draw++;
            if (c.isCommander()) colors += c.getManaCost().toString();
        }
        for (Card c : p.getCardsIn(ZoneType.Command)) if (c.isCommander()) colors += c.getManaCost().toString();
        return new ThreatAssessment.PlayerState(p.getCardsIn(ZoneType.Hand).size(), p.getLife(), mana, power, draw,
            false, 0, colors, potential >= ai.getLife(), turn);
    }
}