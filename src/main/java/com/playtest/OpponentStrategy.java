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
        return StrategyWeights.bonus(data != null && data.changer(), data == null ? null : data.price(), size, count);
    }
}