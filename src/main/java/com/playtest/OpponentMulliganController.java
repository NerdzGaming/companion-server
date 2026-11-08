package com.playtest;

import forge.LobbyPlayer;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Opponent seat controller: opening-hand choices plus v0.45 active strategy vetoes
 * (phase discipline, defender choice, crackback veto, chump-block protection, mana preservation).
 * All other engine decisions are inherited from PlayerControllerAi.
 */
public final class OpponentMulliganController extends PlayerControllerAi {
    private final OpeningHandPolicy.Profile profile;
    private final Set<String> engines;
    private final OpponentStrategy strategy;
    private int redraws;
    private boolean play = true;

    public OpponentMulliganController(Game game, Player player, LobbyPlayer lobby, Deck deck, Set<String> engines, boolean combo, OpponentStrategy strategy) {
        super(game, player, lobby);
        this.engines = Set.copyOf(engines);
        this.strategy = strategy;
        List<OpeningHandPolicy.CardInfo> submitted = new ArrayList<>();
        Map<Integer, Integer> sources = new HashMap<>();
        int id = 0;
        for (var entry : deck.getMain()) {
            for (int n = 0; n < entry.getValue(); n++) {
                var info = describe(id++, entry.getKey().getRules(), engines);
                submitted.add(info);
                if (info.land()) sources.merge(info.sources(), 1, Integer::sum);
            }
        }
        profile = new OpeningHandPolicy.Profile(submitted.size(), sources, OpeningHandPolicy.infer(submitted, combo));
        setupAutoProfile(deck);
    }

    static OpeningHandPolicy.CardInfo describe(int id, CardRules rules, Set<String> engines) {
        var face = rules.getMainPart();
        String name = face.getName();
        String text = face.getOracleText().toLowerCase(java.util.Locale.ROOT);
        var type = face.getType();
        boolean land = type.isLand();
        int sources = 0;
        String[] basics = {"plains", "island", "swamp", "mountain", "forest"};
        String[] colours = {"w", "u", "b", "r", "g", "c"};
        // Only mana-production clauses, not coloured activation costs or unrelated symbols.
        if (land) {
            for (int i = 0; i < basics.length; i++) if (type.hasSubtype(capitalize(basics[i]))) sources |= 1 << i;
            var clauses = Pattern.compile("add ([^.]+)").matcher(text);
            while (clauses.find()) {
                String clause = clauses.group(1);
                if (clause.contains("any color") || clause.contains("any colour")) sources |= 31;
                for (int i = 0; i < colours.length; i++) if (clause.contains("{" + colours[i] + "}")) sources |= 1 << i;
            }
        }
        List<Integer> pips = new ArrayList<>();
        for (var shard : face.getManaCost()) {
            if (shard.isPhyrexian()) continue; // payable with life; this is a heuristic, not a payment override
            int mask = 0;
            for (int i = 0; i < colours.length; i++) if (shard.canBePaidWithManaOfColor((byte) (1 << i))) mask |= 1 << i;
            if (mask != 0) pips.add(mask);
        }
        boolean removal = text.contains("destroy target") || text.contains("exile target") || text.contains("damage to any target");
        boolean counter = text.contains("counter target spell");
        boolean discard = text.contains("discards") || text.contains("discard a card");
        boolean wipe = text.contains("destroy all") || text.contains("exile all");
        boolean ramp = text.contains("add {") || text.contains("add one mana") || (text.contains("search your library") && text.contains("land") && text.contains("battlefield"));
        boolean pregame = text.contains("opening hand") && text.contains("begin the game");
        boolean instantOrSorcery = type.isInstant() || type.isSorcery();
        return new OpeningHandPolicy.CardInfo(id, name, land, face.getManaCost().getCMC(), sources, pips,
            type.isCreature(), !land && ramp, instantOrSorcery && (removal || counter), removal, wipe,
            discard, counter, pregame, engines.contains(OpponentStrategy.key(name)), type.isLegendary());
    }
    private static String capitalize(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private List<OpeningHandPolicy.CardInfo> describeHand(CardCollectionView hand) {
        List<OpeningHandPolicy.CardInfo> result = new ArrayList<>();
        for (Card card : hand) result.add(describe(card.getId(), card.getPaperCard().getRules(), engines));
        return result;
    }
    @Override public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        play = firstPlayer == null || firstPlayer.equals(getPlayer());
        var hand = getPlayer().getCardsIn(ZoneType.Hand);
        var decision = OpeningHandPolicy.decide(describeHand(hand), profile, play, redraws,
            Math.max(0, redraws - MatchWorker.freeMulligans), OpeningHandPolicy.Archetype.UNKNOWN);
        // Private debug output stays on worker stderr; never publish bottomed names or hand analysis.
        System.err.println("AI mulligan " + redraws + " " + profile.archetype() + ": " + decision.score()
            + "/" + decision.threshold() + " " + (decision.keep() ? "keep" : "redraw") + " " + decision.reasons());
        if (decision.keep()) {
            // Forge supplies the authoritative, format-adjusted return count at this callback.
            for (Card card : chooseBottom(hand, Math.max(0, redraws - MatchWorker.freeMulligans))) getPlayer().getGame().getAction().moveToLibrary(card, -1, null);
        } else { redraws++; }
        return decision.keep();
    }
    @Override public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (cardsToReturn > getPlayer().getMaxHandSize()) return chooseBottom(hand, cardsToReturn);
        // London calls this after redraw, before Keep. Leave the seven intact for evaluation.
        return new CardCollection();
    }
    private CardCollectionView chooseBottom(CardCollectionView hand, int count) {
        var decision = OpeningHandPolicy.decide(describeHand(hand), profile, play, redraws, count, OpeningHandPolicy.Archetype.UNKNOWN);
        CardCollection result = new CardCollection();
        for (var bottom : decision.bottom()) for (Card card : hand) if (card.getId() == bottom.id()) result.add(card);
        return result;
    }

    /** v0.45: hard phase/removal vetoes — returning null passes priority instead of casting. */
    @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
        if (chosen == null || chosen.isEmpty() || strategy == null) return chosen;
        try {
            if (strategy.shouldVeto(chosen.get(0))) return null;
        } catch (RuntimeException ignored) { }
        return chosen;
    }

    /** v0.45: send attackers at the highest-priority defender and pull back from lethal crackbacks. */
    @Override public void declareAttackers(Player attacker, Combat combat) {
        super.declareAttackers(attacker, combat);
        if (strategy == null) return;
        try { reassignDefenders(combat); } catch (RuntimeException ignored) { }
        try { vetoCrackback(combat); } catch (RuntimeException ignored) { }
    }

    private void reassignDefenders(Combat combat) {
        Player ai = getPlayer();
        List<Player> playerDefenders = new ArrayList<>();
        for (var d : combat.getDefenders()) if (d instanceof Player p && !p.equals(ai)) playerDefenders.add(p);
        if (playerDefenders.size() < 2) return;
        Player best = null; int bestScore = Integer.MIN_VALUE;
        for (Player p : playerDefenders) {
            int score = strategy.playerPriority(p, ai);
            if (score > bestScore) { bestScore = score; best = p; }
        }
        if (best == null) return;
        List<Card> attackers = new ArrayList<>();
        for (Card c : combat.getAttackers()) attackers.add(c);
        for (Card c : attackers) {
            var current = combat.getDefenderByAttacker(c);
            if (current instanceof Player && !current.equals(best) && CombatUtil.canAttack(c, best)) {
                combat.removeFromCombat(c);
                combat.addAttacker(c, best);
            }
        }
    }

    private void vetoCrackback(Combat combat) {
        Player ai = getPlayer();
        int outgoing = 0;
        for (Card c : combat.getAttackers()) outgoing += Math.max(0, c.getNetCombatDamage());
        if (outgoing == 0) return;
        int defenderLife = Integer.MAX_VALUE;
        for (var d : combat.getDefenders()) if (d instanceof Player p && !p.equals(ai)) defenderLife = Math.min(defenderLife, p.getLife());
        if (defenderLife == Integer.MAX_VALUE) return; // only planeswalker/battle defenders
        int incoming = 0;
        for (Player p : ai.getGame().getPlayers()) {
            if (p.equals(ai) || p.hasLost()) continue;
            for (Card c : p.getCardsIn(ZoneType.Battlefield))
                if (!c.isFaceDown() && c.isCreature()) incoming += Math.max(0, c.getNetCombatDamage());
        }
        int absorbed = 0;
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isCreature()) continue;
            boolean staysHome = !combat.isAttacking(c) || c.hasKeyword(Keyword.VIGILANCE);
            if (staysHome && !c.isTapped()) absorbed += Math.max(1, c.getNetToughness());
        }
        incoming = Math.max(0, incoming - absorbed);
        if (!ActionSequencer.vetoCrackback(ai.getLife(), outgoing, defenderLife, incoming)) return;
        // Pull non-vigilance attackers home, lowest power first, until the crackback is survivable.
        List<Card> attackers = new ArrayList<>();
        for (Card c : combat.getAttackers()) if (!c.hasKeyword(Keyword.VIGILANCE)) attackers.add(c);
        attackers.sort((a, b) -> Integer.compare(Math.max(0, a.getNetPower()), Math.max(0, b.getNetPower())));
        for (Card c : attackers) {
            combat.removeFromCombat(c);
            outgoing -= Math.max(0, c.getNetCombatDamage());
            incoming = Math.max(0, incoming - Math.max(1, c.getNetToughness())); // it stays home as a blocker
            if (!ActionSequencer.vetoCrackback(ai.getLife(), outgoing, defenderLife, incoming)) break;
        }
    }

    /** v0.45: protected creatures (dorks, engines, commanders, combo pieces) do not chump into surviving attackers. */
    @Override public void declareBlockers(Player defender, Combat combat) {
        super.declareBlockers(defender, combat);
        if (strategy == null) return;
        try { protectBlockers(defender, combat); } catch (RuntimeException ignored) { }
    }

    private void protectBlockers(Player defender, Combat combat) {
        Player ai = getPlayer();
        List<Card> attackers = new ArrayList<>();
        for (Card c : combat.getAttackers()) attackers.add(c);
        for (Card attacker : attackers) {
            List<Card> blockers = new ArrayList<>();
            for (Card b : combat.getBlockers(attacker)) if (ai.equals(b.getController())) blockers.add(b);
            for (Card b : blockers) {
                if (!protectedPiece(b)) continue;
                boolean dies = attacker.getNetCombatDamage() >= b.getNetToughness() || attacker.hasKeyword(Keyword.DEATHTOUCH);
                if (!dies) continue;
                int damageToAttacker = 0;
                for (Card other : combat.getBlockers(attacker)) if (!other.equals(b)) damageToAttacker += Math.max(0, other.getNetCombatDamage());
                boolean attackerSurvives = damageToAttacker < attacker.getNetToughness() && !b.hasKeyword(Keyword.DEATHTOUCH);
                if (!attackerSurvives) continue; // an even trade is fine
                int unblocked = Math.max(0, attacker.getNetCombatDamage());
                if (ActionSequencer.mayChumpStrict(true, defender.getLife(), unblocked)) continue;
                combat.removeBlockAssignment(attacker, b);
                String error = CombatUtil.validateBlocks(combat, defender);
                if (error != null) combat.addBlocker(attacker, b); // never leave an illegal block state
            }
        }
    }

    private boolean protectedPiece(Card b) {
        if (strategy.preservedPiece(b)) return true;
        try { return strategy.bonus(b) >= 55; } catch (RuntimeException ignored) { return false; }
    }

    /**
     * v0.45: tap colorless and off-color single-color sources first, preserving multicolor lands and
     * the colors of held instant-speed interaction. Uses the temporary tap-masking pattern proven in
     * HumanBridgeController.payAvoidingTargets; masks are always restored in finally.
     */
    @Override public boolean playChosenSpellAbility(SpellAbility sa) {
        if (strategy == null || sa == null || !sa.isSpell()) return super.playChosenSpellAbility(sa);
        Player ai = getPlayer();
        List<Card> masked = new ArrayList<>();
        try {
            int heldMask = heldInteractionMask(ai);
            if (heldMask != 0) {
                List<Card> tierA = new ArrayList<>(), tierB = new ArrayList<>();
                for (Card c : ai.getCardsIn(ZoneType.Battlefield)) {
                    if (c.isTapped() || c.getManaAbilities().isEmpty()) continue;
                    int mask = producibleMask(c);
                    int colors = Integer.bitCount(mask);
                    int tier = ActionSequencer.manaSourceTapOrder(colors == 0, colors, (mask & heldMask) != 0);
                    if (tier == 2) tierA.add(c); else if (tier == 1) tierB.add(c);
                }
                for (Card c : tierA) { c.setTapped(true); masked.add(c); }
                for (Card c : tierB) { c.setTapped(true); masked.add(c); }
                if (!masked.isEmpty() && !canPay(sa, ai)) {
                    // Too strict: keep only the multicolor lands masked.
                    for (Card c : tierB) { c.setTapped(false); masked.remove(c); }
                    if (!masked.isEmpty() && !canPay(sa, ai)) {
                        for (Card c : masked) c.setTapped(false);
                        masked.clear();
                    }
                }
            }
            return super.playChosenSpellAbility(sa);
        } finally {
            for (Card c : masked) c.setTapped(false);
        }
    }

    private static boolean canPay(SpellAbility sa, Player ai) {
        try { return ComputerUtilMana.canPayManaCost(sa, ai, 0, false); }
        catch (RuntimeException e) { return true; } // unsure: do not interfere with payment
    }

    /** Color mask (WUBRG bits) needed to cast held instant-speed interaction. */
    private static int heldInteractionMask(Player ai) {
        int mask = 0;
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (!c.isInstant() && !c.hasKeyword(Keyword.FLASH)) continue;
            boolean interaction = false;
            for (SpellAbility ability : c.getSpellAbilities()) {
                var api = ability.getApi();
                if (api == ApiType.Destroy || api == ApiType.Counter || api == ApiType.DealDamage
                    || api == ApiType.Pump || api == ApiType.Protection || api == ApiType.ChangeZone) { interaction = true; break; }
            }
            if (!interaction) continue;
            for (var shard : c.getManaCost()) {
                for (int i = 0; i < 5; i++) if (shard.canBePaidWithManaOfColor((byte) (1 << i))) mask |= 1 << i;
            }
        }
        return mask;
    }

    /** Color mask (WUBRG bits) this permanent's mana abilities can produce. */
    private static int producibleMask(Card c) {
        int mask = 0;
        String[] colors = {"W", "U", "B", "R", "G"};
        for (var ma : c.getManaAbilities()) {
            var part = ma.getManaPart();
            if (part == null) continue;
            for (int i = 0; i < colors.length; i++) {
                try { if (part.canProduce(colors[i], ma)) mask |= 1 << i; } catch (RuntimeException ignored) { }
            }
        }
        return mask;
    }
}
