package com.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
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
        // v0.45: preserve our own utility creatures (dorks, engines, commanders) in AI evaluations.
        if (own && card.isInZone(ZoneType.Battlefield) && card.isCreature() && preservedPiece(card)) return base + 90;
        if (own || !card.isInZone(ZoneType.Battlefield)) return base;
        try { return base + threatBonus(card, ai, size >= 2 && count >= size); } catch (RuntimeException ignored) { return base; }
    }

    /** v0.45: utility creatures the AI should not trade or throw away as chump blockers. */
    boolean preservedPiece(Card card) {
        if (!card.getManaAbilities().isEmpty()) return true;
        String name = card.getName();
        return card.isCommander() || ThreatAssessment.drawEngine(name) || ThreatAssessment.manaEngine(name)
            || ThreatAssessment.apex(name);
    }

    /** Sequencing delta for one AI-castable ability; ordering only, Forge still decides legality. */
    public int sequence(SpellAbility sa) {
        try {
            Card card = sa == null ? null : sa.getHostCard();
            if (card == null || card.getGame() == null) return 0;
            var game = card.getGame(); var ph = game.getPhaseHandler();
            Player ai = sa.getActivatingPlayer();
            if (ai == null || !ai.getName().equals("opponent") || !ai.equals(ph.getPriorityPlayer())) return 0;
            // v0.45: vetoed plays always sort behind everything else.
            if (shouldVeto(sa)) return -10000;
            var api = sa.getApi();
            boolean selection = api == ApiType.Draw || api == ApiType.Scry
                || api == ApiType.Surveil || api == ApiType.Dig;
            boolean combat = combatRelevant(sa, card, ai);
            var phase = ph.getPhase();
            boolean ownTurn = ph.isPlayerTurn(ai);
            boolean endBeforeMine = !ownTurn && phase == PhaseType.END_OF_TURN && ai.equals(ph.getNextTurn());
            boolean instant = card.isInstant() || card.hasKeyword(Keyword.FLASH) || !sa.isSpell();
            int risk = 0, hand = 0;
            for (Player p : game.getPlayers()) {
                if (p.equals(ai) || p.hasLost()) continue;
                int open = 0;
                for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                    if (c.isFaceDown() || c.isTapped()) continue;
                    for (var ma : c.getManaAbilities()) {
                        var part = ma.getManaPart();
                        if (part != null && (part.canProduce("U", ma) || part.canProduce("W", ma) || part.canProduce("B", ma) || part.canProduce("R", ma))) { open++; break; }
                    }
                }
                if (ActionSequencer.interactionRisk(open, p.getCardsIn(ZoneType.Hand).size())) risk++;
            }
            for (Card c : ai.getCardsIn(ZoneType.Hand)) if (!c.isLand()) hand++;
            boolean tierOne = card.isCommander() || bonus(card) >= 60;
            int delta = ActionSequencer.priority(new ActionSequencer.Play(
                phase == PhaseType.MAIN1, phase == PhaseType.MAIN2, ownTurn, endBeforeMine,
                game.getStack().isEmpty(), card.isCreature(), card.hasKeyword(Keyword.HASTE), combat,
                instant, selection, ai.getLandsPlayedThisTurn() == 0, risk > 0, tierOne, hand > 1));
            // v0.45: answer an opponent's aura/equipment/pump on the stack instead of letting it resolve.
            if (instant && isSingleTargetRemoval(sa, card) && isTargetedByOpponentBuffOnStack(null, ai)) delta += 40;
            return delta;
        } catch (RuntimeException ignored) { return 0; }
    }

    /**
     * v0.45: hard vetoes. True means the AI passes priority instead of making this play;
     * OpponentMulliganController.chooseSpellAbilityToPlay enforces it, and sequence() demotes it.
     */
    public boolean shouldVeto(SpellAbility sa) {
        try {
            Card card = sa == null ? null : sa.getHostCard();
            if (card == null || card.getGame() == null) return false;
            var game = card.getGame(); var ph = game.getPhaseHandler();
            Player ai = sa.getActivatingPlayer();
            if (ai == null || !ai.getName().equals("opponent")) return false;
            var phase = ph.getPhase();
            boolean ownTurn = ph.isPlayerTurn(ai);
            boolean mainOne = phase == PhaseType.MAIN1;
            boolean mainTwo = phase == PhaseType.MAIN2;
            boolean stackEmpty = game.getStack().isEmpty();
            boolean endBeforeMine = !ownTurn && phase == PhaseType.END_OF_TURN && ai.equals(ph.getNextTurn());
            boolean noLandYet = ai.getLandsPlayedThisTurn() == 0;
            var api = sa.getApi();
            boolean selection = api == ApiType.Draw || api == ApiType.Scry || api == ApiType.Surveil || api == ApiType.Dig;
            boolean preLandCantrip = ownTurn && mainOne && noLandYet && selection;

            // Land drops are never vetoed, except to let a pre-land card-selection play resolve first.
            if (sa.isLandAbility()) return ownTurn && mainOne && stackEmpty && noLandYet && hasPreLandSelection(ai);

            boolean instant = card.isInstant() || card.hasKeyword(Keyword.FLASH) || !sa.isSpell();

            // Removal patience: hold single-target removal for real threats (score 65+).
            if (isSingleTargetRemoval(sa, card)) {
                int bestScore = bestRemovalTargetScore(sa, ai);
                if (bestScore < 0) return true; // own permanent or stax that polices the leader
                boolean discarding = ownTurn && mainTwo && ai.getCardsIn(ZoneType.Hand).size() > ai.getMaxHandSize();
                if (!ThreatAssessment.removalAllowed(bestScore, endBeforeMine, discarding, tempoWin(game, ai),
                    !instant, ownTurn && mainTwo)) return true;
            }

            boolean combat = combatRelevant(sa, card, ai);
            boolean flashNonCombat = card.hasKeyword(Keyword.FLASH) && !combat;
            // Instant-speed selection and non-combat flash threats wait for the end step before our turn.
            if (instant && (selection || flashNonCombat) && !endBeforeMine) {
                boolean allowed = !stackEmpty || preLandCantrip
                    || (ownTurn && mainTwo && ai.getCardsIn(ZoneType.Hand).size() > ai.getMaxHandSize());
                if (!allowed) return true;
            }

            // Main 1 hard filter: only pre-land cantrips, haste creatures and combat-relevant plays.
            if (ownTurn && mainOne && stackEmpty) {
                if (preLandCantrip) return false;
                if (card.isCreature() && card.hasKeyword(Keyword.HASTE)) return false;
                if (combat) return false;
                return true;
            }

            // Main 2 sandbag: do not overcommit non-lethal creatures into open wipe mana while ahead.
            if (ownTurn && mainTwo && card.isCreature() && sa.isSpell() && shouldSandbag(game, ai)) return true;
            return false;
        } catch (RuntimeException ignored) { return false; }
    }

    /** v0.45: true when an opponent's aura, equipment attach or pump on the stack targets this card (or anything, when target is null). */
    public boolean isTargetedByOpponentBuffOnStack(Card target, Player ai) {
        try {
            for (var si : ai.getGame().getStack()) {
                var sa = si.getSpellAbility();
                if (sa == null) continue;
                Player controller = si.getActivatingPlayer();
                if (controller == null || controller.equals(ai)) continue;
                Card host = sa.getHostCard();
                var api = sa.getApi();
                boolean buff = (host != null && host.isAura()) || api == ApiType.Attach || api == ApiType.Pump
                    || api == ApiType.PumpAll || api == ApiType.PutCounter;
                if (!buff) continue;
                if (target == null) return true;
                for (var choices : sa.getAllTargetChoices()) for (var o : choices) if (o == target) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    /** v0.45: public-information attack priority for one opposing player (defender choice). */
    public int playerPriority(Player target, Player ai) {
        var game = ai.getGame();
        int turn = game.getPhaseHandler().getTurn();
        List<Player> opponents = new ArrayList<>();
        for (Player p : game.getPlayers()) if (!p.equals(ai) && !p.hasLost()) opponents.add(p);
        Map<Player, ThreatAssessment.PlayerState> states = new HashMap<>();
        for (Player p : opponents) states.put(p, publicState(p, ai, turn));
        double avg = ThreatAssessment.averageMana(states.values());
        int threat = ThreatAssessment.playerThreat(states.get(target), avg);
        String targetArchetype = inferArchetype(target);
        int aiPower = 0;
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) if (c.isCreature()) aiPower += Math.max(0, c.getNetPower());
        String botArchetype = aiPower >= 8 ? "aggro" : "midrange";
        return ThreatAssessment.attackPriority(threat, botArchetype, target.getLife(), targetArchetype);
    }

    /** Archetype inferred from public state only: board, open mana and public engines. */
    private String inferArchetype(Player p) {
        int creatures = 0, power = 0, interactive = 0, engines = 0;
        Set<String> publicPieces = new HashSet<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isFaceDown()) continue;
            publicPieces.add(key(c.getName()));
            if (c.isCreature()) { creatures++; power += Math.max(0, c.getNetPower()); }
            if (ThreatAssessment.drawEngine(c.getName()) || ThreatAssessment.stax(c.getName())) engines++;
            if (!c.isTapped()) for (var ma : c.getManaAbilities()) {
                var part = ma.getManaPart();
                try {
                    if (part != null && (part.canProduce("U", ma) || part.canProduce("W", ma))) { interactive++; break; }
                } catch (RuntimeException ignored) { }
            }
        }
        if (power >= 8) return "aggro";
        if ((interactive >= 2 || engines >= 1) && creatures <= 2) return "control";
        for (List<Set<String>> combos : catalog.values()) for (Set<String> combo : combos) {
            int present = 0; for (String piece : combo) if (publicPieces.contains(piece)) present++;
            if (present >= 2) return "combo";
        }
        return "midrange";
    }

    /** A castable, affordable draw/scry/surveil/dig spell in the AI's hand (pre-land sequencing). */
    private static boolean hasPreLandSelection(Player ai) {
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (c.isLand()) continue;
            for (SpellAbility ability : c.getSpellAbilities()) {
                if (!ability.isSpell()) continue;
                var api = ability.getApi();
                if (api != ApiType.Draw && api != ApiType.Scry && api != ApiType.Surveil && api != ApiType.Dig) continue;
                try {
                    if (forge.ai.ComputerUtilMana.canPayManaCost(ability, ai, 0, false)) return true;
                } catch (RuntimeException ignored) { }
            }
        }
        return false;
    }

    /** Plays that materially affect this turn's combat. */
    private static boolean combatRelevant(SpellAbility sa, Card card, Player ai) {
        if (ActionSequencer.precombatText(card.getOracleText())) return true;
        var api = sa.getApi();
        if (api == ApiType.Pump || api == ApiType.PumpAll || api == ApiType.Destroy || api == ApiType.DealDamage
            || api == ApiType.Tap || api == ApiType.TapAll || api == ApiType.PutCounter) return true;
        if (api == ApiType.ChangeZone) {
            String dest = sa.getParam("Destination");
            if ("Battlefield".equals(sa.getParam("Origin")) && ("Exile".equals(dest) || "Hand".equals(dest))) return true;
        }
        if (api == ApiType.Attach) {
            for (Card c : ai.getCardsIn(ZoneType.Battlefield)) if (c.isCreature() && !c.isTapped()) return true;
        }
        return false;
    }

    /** Single-target Destroy / damage / exile-or-bounce spells; mass removal uses separate API types. */
    private static boolean isSingleTargetRemoval(SpellAbility sa, Card card) {
        boolean playerTarget = false, nonBattlefieldTarget = false;
        for (var choices : sa.getAllTargetChoices()) for (var target : choices) {
            if (target instanceof Player) playerTarget = true;
            if (target instanceof Card c && !c.isInZone(ZoneType.Battlefield)) nonBattlefieldTarget = true;
        }
        var api = sa.getApi();
        return RemovalPolicy.applies(sa.isSpell(), sa.usesTargeting() ? sa.getMaxTargets() : 0,
            api == null ? "" : api.name(), sa.getParam("Origin"), sa.getParam("Destination"),
            playerTarget, nonBattlefieldTarget);
    }

    /** Threat score of the removal target, or the best opposing permanent when untargeted; -1 vetoes. */
    private int bestRemovalTargetScore(SpellAbility sa, Player ai) {
        Card targeted = null;
        try {
            for (Card c : sa.getTargets().getTargetCards()) { targeted = c; break; }
        } catch (RuntimeException ignored) { }
        if (targeted != null) {
            if (targeted.getController() != null && targeted.getController().equals(ai)) return -1;
            return rawThreatScore(targeted, ai, false);
        }
        int best = 0;
        for (Player p : ai.getGame().getPlayers()) {
            if (p.equals(ai) || p.hasLost()) continue;
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                if (c.isFaceDown()) continue;
                best = Math.max(best, rawThreatScore(c, ai, false));
            }
        }
        return best;
    }

    /** Removing one blocker wins this turn: our board is lethal and a blocker stands in the way. */
    private static boolean tempoWin(forge.game.Game game, Player ai) {
        if (!game.getPhaseHandler().isPlayerTurn(ai)) return false;
        int power = 0;
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) if (c.isCreature() && !c.isTapped()) power += Math.max(0, c.getNetCombatDamage());
        if (power == 0) return false;
        for (Player p : game.getPlayers()) {
            if (p.equals(ai) || p.hasLost()) continue;
            if (power < p.getLife()) continue;
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) if (c.isCreature() && !c.isTapped()) return true;
        }
        return false;
    }

    /** Main 2 overcommit check against each opponent's public board and open mana. */
    private boolean shouldSandbag(forge.game.Game game, Player ai) {
        int aiCreatures = 0, aiPower = 0;
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) if (c.isCreature()) { aiCreatures++; aiPower += Math.max(0, c.getNetPower()); }
        boolean protectionUp = false;
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility ability : c.getSpellAbilities()) {
                var api = ability.getApi();
                if (api == ApiType.Counter || api == ApiType.Protection) { protectionUp = true; break; }
            }
            if (protectionUp) break;
        }
        for (Player p : game.getPlayers()) {
            if (p.equals(ai) || p.hasLost()) continue;
            int oppPower = 0, open = 0;
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                if (c.isFaceDown()) continue;
                if (c.isCreature()) oppPower += Math.max(0, c.getNetPower());
                if (!c.isTapped() && (c.isLand() || !c.getManaAbilities().isEmpty())) open++;
            }
            boolean lethal = aiPower >= p.getLife();
            if (ActionSequencer.shouldSandbagOvercommit(aiCreatures, aiPower, oppPower, open,
                p.getCardsIn(ZoneType.Hand).size(), lethal, protectionUp)) return true;
        }
        return false;
    }

    /** Commander threat rubric applied to opposing face-up permanents only (removal/target valuation). */
    private int threatBonus(Card card, Player ai, boolean completeCombo) {
        return ThreatAssessment.evaluatorBonus(rawThreatScore(card, ai, completeCombo));
    }

    /** Raw 0–100+ threat score for one public permanent, including the +40 stack-buff response. */
    private int rawThreatScore(Card card, Player ai, boolean completeCombo) {
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
        if (kind == ThreatAssessment.Kind.OTHER && card.isCreature()) {
            score = Math.max(score, ThreatAssessment.creatureThreat(card.getNetPower(), card.getNetToughness(), card.getCMC()));
        }
        // v0.45: a buff on the stack aimed at this permanent makes answering it urgent.
        if (isTargetedByOpponentBuffOnStack(card, ai)) score += 40;
        boolean otherBucketOne = false;
        for (Player p : opponents) if (!p.equals(owner) && states.get(p).lethalOnBoard()) otherBucketOne = true;
        return ThreatAssessment.deferForCombo(score, kind, otherBucketOne);
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
