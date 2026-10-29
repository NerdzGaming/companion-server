package com.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.cost.CostPayment;
import forge.ai.AiCostDecision;
import forge.game.zone.Zone;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.ai.ComputerUtil;
import forge.game.player.DelayedReveal;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerController;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.card.CounterType;
import forge.game.cost.CostPartWithList;
import java.util.Collection;
import forge.game.spellability.AbilitySub;
import forge.util.collect.FCollectionView;
import java.util.HashSet;
import java.util.Set;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.LobbyPlayer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Website seat. Asks the browser (via stdout PROMPT lines / stdin ACT lines) for priority,
 * mulligan, attack, block, targets, modes, yes/no and card/player choices and which mana sources to tap. Other
 * rarely used choices still fall back to Forge AI. No answer within the timeout = AI decides.
 */
public final class HumanBridgeController extends PlayerControllerAi {
    static final long TIMEOUT_MIN = Long.parseLong(System.getenv().getOrDefault("PROMPT_TIMEOUT_MIN", "5"));
    static final LinkedBlockingQueue<JsonNode> INBOX = new LinkedBlockingQueue<>();
    static final AtomicInteger SEQ = new AtomicInteger();
    static final ZoneType[] ZONES = { ZoneType.Hand, ZoneType.Battlefield, ZoneType.Command, ZoneType.Graveyard, ZoneType.Exile };

    static void startInbox() {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try { INBOX.put(MatchWorker.JSON.readTree(line)); } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
        });
        t.setDaemon(true); t.start();
    }

    public HumanBridgeController(Game game, Player p, LobbyPlayer lp) { super(game, p, lp); }

    /** Emits a prompt and waits for the matching answer; null on timeout. */
    private JsonNode ask(String kind, Map<String, Object> body) {
        String id = kind + "-" + SEQ.incrementAndGet();
        try { MatchWorker.out.println(MatchWorker.JSON.writeValueAsString(MatchWorker.snapshot(getPlayer().getGame()))); } catch (Exception ignored) { }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "PROMPT"); p.put("kind", kind); p.put("promptId", id);
        p.putAll(body);
        MatchWorker.emit(p);
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(TIMEOUT_MIN);
        try {
            while (System.currentTimeMillis() < deadline) {
                JsonNode m = INBOX.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (m == null) break;
                if (id.equals(m.path("promptId").asText())) return m;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        MatchWorker.emit(Map.of("type", "NOTICE", "message", "No answer in time — Forge AI decided for you."));
        return null;
    }


    private static Map<String, Object> card(Card c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId()); m.put("name", c.getName());
        if (c.isCreature()) { m.put("power", c.getNetPower()); m.put("toughness", c.getNetToughness()); }
        return m;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        Player me = getPlayer();
        while (true) {
            List<SpellAbility> playable = new ArrayList<>();
            for (ZoneType z : ZONES) {
                for (Card c : new ArrayList<>(me.getCardsIn(z))) {
                    try {
                        for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                            sa.setActivatingPlayer(me);
                            if (!sa.canPlay()) continue;
                            if (!sa.isLandAbility() && !ComputerUtilMana.canPayManaCost(sa, me, 0, false)) continue;
                            playable.add(sa);
                        }
                    } catch (RuntimeException ignored) { }
                }
            }
            // Always offer an explicit pass: do not advance the phase and drain floating mana silently.
            List<Map<String, Object>> options = new ArrayList<>();
            for (int i = 0; i < playable.size(); i++) {
                SpellAbility sa = playable.get(i);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("index", i);
                o.put("cardId", sa.getHostCard().getId());
                o.put("cardName", sa.getHostCard().getName());
                o.put("zone", sa.getHostCard().getZone() == null ? null : sa.getHostCard().getZone().getZoneType().name());
                o.put("land", sa.isLandAbility());
                o.put("mana", sa.isManaAbility());
                String d = sa.isLandAbility() ? "Play land" : sa.toUnsuppressedString();
                o.put("label", d == null || d.isBlank() ? sa.getHostCard().getName() : d);
                options.add(o);
            }
            var ph = getPlayer().getGame().getPhaseHandler();
            JsonNode a = ask("PRIORITY", Map.of("options", options,
                "phase", ph.getPhase() == null ? "" : ph.getPhase().name(),
                "stackSize", getPlayer().getGame().getStack().size()));
            if (a == null) return super.chooseSpellAbilityToPlay();
            if (!a.has("index") || a.path("index").isNull()) return null; // pass
            int idx = a.path("index").asInt(-1);
            if (idx < 0 || idx >= playable.size()) return null;
            SpellAbility sa = playable.get(idx);
            sa.setActivatingPlayer(me);
            if (!sa.canPlay()) continue;
            if (sa.isManaAbility()) {
                if (ComputerUtil.playNoStack(me, sa, me.getGame(), false)) MatchWorker.recordManaAction(sa);
                else MatchWorker.emit(Map.of("type", "NOTICE", "message", "Mana ability could not be activated."));
                continue; // mana abilities do not use the stack; keep priority
            }
            // Do not call getAi().canPlaySa here: it is a strategic decision, not a legality check.
            List<SpellAbility> out = new ArrayList<>();
            out.add(sa);
            return out;
        }
    }

    /** Cards Forge asked us to tuck after the latest London redraw; settled once, when the player keeps. */
    private int deferredTuck = 0;

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        List<Map<String, Object>> hand = new ArrayList<>();
        for (Card c : getPlayer().getCardsIn(ZoneType.Hand)) hand.add(card(c));
        JsonNode a = ask("MULLIGAN", Map.of("hand", hand, "cardsToReturn", cardsToReturn));
        boolean keep = a == null ? super.mulliganKeepHand(firstPlayer, cardsToReturn) : a.path("keep").asBoolean(true);
        if (keep) settleDeferredTuck();
        else deferredTuck = 0; // the whole hand is shuffled away; nothing to bottom from it
        return keep;
    }

    /** CR 103.5: bottom cards once, after the final keep, never between mulligans. */
    private void settleDeferredTuck() {
        int n = deferredTuck;
        deferredTuck = 0;
        if (n <= 0) return;
        CardCollection hand = new CardCollection(getPlayer().getCardsIn(ZoneType.Hand));
        for (Card c : chooseBottom(hand, Math.min(n, hand.size()))) {
            getPlayer().getGame().getAction().moveToLibrary(c, -1, null);
        }
    }

    /**
     * Forge's LondonMulligan calls this straight after each redraw, before the next keep decision.
     * Defer it so the player sees a full seven and bottoms only once they keep. If Forge will not
     * offer another keep prompt (canMulligan false), tuck now so the count is never skipped.
     */
    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (cardsToReturn <= 0) { deferredTuck = 0; return new CardCollection(); }
        if (cardsToReturn > getPlayer().getMaxHandSize()) { deferredTuck = 0; return chooseBottom(hand, cardsToReturn); }
        deferredTuck = cardsToReturn;
        return new CardCollection();
    }

    private CardCollectionView chooseBottom(CardCollectionView hand, int count) {
        if (count <= 0) return new CardCollection();
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Card c : hand) cards.add(card(c));
        JsonNode a = ask("BOTTOM", Map.of("hand", cards, "count", count));
        if (a == null) return super.tuckCardsViaMulligan(hand, count);
        CardCollection out = new CardCollection();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode id : a.path("cards")) {
            if (out.size() >= count || !seen.add(id.asInt())) continue;
            for (Card c : hand) if (c.getId() == id.asInt()) { out.add(c); break; }
        }
        if (out.size() != count) return super.tuckCardsViaMulligan(hand, count);
        return out;
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        GameEntity defender = null;
        for (GameEntity e : combat.getDefenders()) { if (e instanceof Player) { defender = e; break; } }
        if (defender == null) { super.declareAttackers(attacker, combat); return; }
        List<Card> able = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) if (CombatUtil.canAttack(c, defender)) able.add(c);
        if (able.isEmpty()) return;
        List<Map<String, Object>> opts = new ArrayList<>();
        for (Card c : able) opts.add(card(c));
        JsonNode a = ask("ATTACK", Map.of("attackers", opts, "defender", ((Player) defender).getName()));
        if (a == null) { super.declareAttackers(attacker, combat); return; }
        for (JsonNode id : a.path("attackers")) {
            for (Card c : able) if (c.getId() == id.asInt()) combat.addAttacker(c, defender);
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        List<Card> blockers = new ArrayList<>();
        for (Card c : defender.getCreaturesInPlay()) if (CombatUtil.canBlock(c, combat)) blockers.add(c);
        List<Card> attackers = new ArrayList<>(combat.getAttackers());
        if (blockers.isEmpty() || attackers.isEmpty()) return;
        List<Map<String, Object>> at = new ArrayList<>(), bl = new ArrayList<>();
        for (Card c : attackers) at.add(card(c));
        for (Card c : blockers) bl.add(card(c));
        JsonNode a = ask("BLOCK", Map.of("attackers", at, "blockers", bl));
        if (a == null) { super.declareBlockers(defender, combat); return; }
        for (JsonNode b : a.path("blocks")) {
            Card blk = null, atk = null;
            for (Card c : blockers) if (c.getId() == b.path("blocker").asInt()) blk = c;
            for (Card c : attackers) if (c.getId() == b.path("attacker").asInt()) atk = c;
            if (blk != null && atk != null && CombatUtil.canBlock(atk, blk, combat)) combat.addBlocker(atk, blk);
        }
    }

    // ---------- shared choice helpers ----------

    private String key(Object o) {
        if (o instanceof SpellAbility) return "s" + System.identityHashCode(o);
        if (o instanceof Card c) return "c" + c.getId();
        if (o instanceof Player p) return "p" + p.getId();
        return "x" + System.identityHashCode(o);
    }

    private Map<String, Object> entity(Object o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key(o));
        if (o instanceof Card c) {
            m.put("kind", "card"); m.put("cardId", c.getId()); m.put("label", c.getName());
            m.put("controller", c.getController() == getPlayer() ? "you" : "opponent");
            m.put("zone", c.getZone() == null ? null : c.getZone().getZoneType().name());
            if (c.isCreature()) { m.put("power", c.getNetPower()); m.put("toughness", c.getNetToughness()); }
        } else if (o instanceof SpellAbility ability) {
            m.put("kind", "other"); m.put("zone", "Stack");
            m.put("label", MatchWorker.visibleName(ability.getHostCard()) + " — " + (ability.getHostCard().isFaceDown() ? "Face-down spell" : ability.toUnsuppressedString()));
        } else if (o instanceof Player p) {
            m.put("kind", "player"); m.put("label", p == getPlayer() ? "You" : p.getName());
        } else {
            m.put("kind", "other"); m.put("label", String.valueOf(o));
        }
        return m;
    }

    private static String src(SpellAbility sa) {
        return sa == null || sa.getHostCard() == null ? "" : sa.getHostCard().getName();
    }

    private static Set<String> keys(JsonNode a) {
        Set<String> out = new HashSet<>();
        for (JsonNode k : a.path("keys")) out.add(k.asText());
        return out;
    }

    /** Asks the player to pick between min and max of the options; null = no valid answer (caller falls back to AI). */
    private <T> List<T> pick(String kind, String title, SpellAbility sa, List<T> options, int min, int max) {
        List<Map<String, Object>> opts = new ArrayList<>();
        for (T o : options) opts.add(entity(o));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title == null ? "" : title); body.put("source", src(sa));
        body.put("min", min); body.put("max", max); body.put("options", opts);
        while (true) {
            JsonNode a = ask(kind, body);
            if (a == null) return null; // explicit timeout is the only fallback
            Set<String> chosen = keys(a);
            List<T> out = new ArrayList<>();
            for (T o : options) if (chosen.contains(key(o))) out.add(o);
            if (out.size() >= min && out.size() <= max && out.size() == chosen.size()) return out;
            MatchWorker.emit(Map.of("type", "NOTICE", "message", "That selection is not valid. Please choose again."));
        }
    }

    /** Includes stack abilities, which TargetRestrictions.getAllCandidates does not return. */
    private List<GameObject> targetCandidates(SpellAbility sa) {
        List<GameObject> result = new ArrayList<>(sa.getTargetRestrictions().getAllCandidates(sa));
        for (SpellAbilityStackInstance item : getPlayer().getGame().getStack()) {
            SpellAbility target = item.getSpellAbility();
            if (target != sa.getRootAbility() && sa.canTarget(target)) result.add(target);
        }
        return result;
    }

    /** Recompute legality after each target, respecting dependent/unique-target restrictions. */
    private boolean humanTargets(SpellAbility root) {
        root.setActivatingPlayer(getPlayer());
        for (SpellAbility cur = root; cur != null; cur = cur.getSubAbility()) {
            if (!cur.usesTargeting()) continue;
            if (cur.isDividedAsYouChoose()) {
                MatchWorker.emit(Map.of("type", "NOTICE", "message", "Divided target allocation is not supported by this bridge yet; this action was not cast."));
                return false;
            }
            Player targeting = cur.getTargetingPlayer();
            if (targeting != null && targeting != getPlayer()) {
                if (!targeting.getController().chooseTargetsFor(cur)) return false;
                continue;
            }
            cur.resetTargets();
            int min = cur.getMinTargets(), max = cur.getMaxTargets();
            int count = 0;
            while (count < max) {
                List<GameObject> cand;
                try { cand = targetCandidates(cur); }
                catch (RuntimeException e) {
                    MatchWorker.emit(Map.of("type", "NOTICE", "message", "Could not list targets for " + src(cur) + ". The action was not auto-targeted."));
                    return false;
                }
                if (cand.isEmpty()) break;
                int required = count < min ? 1 : 0;
                String what = cur.getTargetRestrictions().getVTSelection();
                List<GameObject> chosen = pick("TARGET",
                    (what == null || what.isBlank() ? "Choose target" : what) + (max > 1 ? " (" + count + "/" + max + ")" : ""),
                    cur, cand, required, 1);
                if (chosen == null) { // only the explicit timeout path permits the AI fallback
                    cur.resetTargets();
                    if (!super.chooseTargetsFor(cur)) return false;
                    count = cur.getTargets().size();
                    break;
                }
                if (chosen.isEmpty()) break;
                GameObject target = chosen.get(0);
                if (!cur.canTarget(target) || !cur.getTargets().add(target)) continue;
                count++;
            }
            if (!cur.isTargetNumberValid()) {
                MatchWorker.emit(Map.of("type", "NOTICE", "message", "Not enough legal targets for " + src(cur) + "."));
                return false;
            }
        }
        return root.isTargetNumberValid();
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        sa.setActivatingPlayer(getPlayer());
        if (sa.isLandAbility()) { if (!sa.canPlay()) return false; sa.resolve(); return true; }
        return castWithHumanChoices(sa);
    }

    /** Adapted from pinned ComputerUtil.handlePlayingSpellAbility with a boolean choice boundary.
     * Rollback BEFORE payment on invalid targets; never fake a successful cast. */
    private boolean castWithHumanChoices(SpellAbility sa) {
        Player me = getPlayer();
        Game game = me.getGame();
        Card source = sa.getHostCard();
        Zone original = source.isCopiedSpell() ? null : source.getZone();
        int position = original == null ? 0 : original.getCards().indexOf(source);
        source.setSplitStateToPlayAbility(sa);
        if (sa.isSpell() && !source.isCopiedSpell()) {
            sa = AbilityUtils.addSpliceEffects(sa);
            sa.setHostCard(game.getAction().moveToStack(source, sa));
        }
        sa.setActivatingPlayer(me);
        if (!sa.isCopied()) { sa.resetPaidHash(); sa.setPaidLife(0); }
        sa = GameActionUtil.addExtraKeywordCost(sa);
        CostPayment payment = new CostPayment(sa.getPayCosts(), sa);
        if ((sa.getApi() == ApiType.Charm && !CharmEffect.makeChoices(sa)) || !humanTargets(sa)) {
            if (sa.isSpell()) GameActionUtil.rollbackAbility(sa, original, position, payment, source);
            return false;
        }
        humanMana(sa);
        boolean refreeze = game.getStack().isFrozen();
        game.getStack().freezeStack(sa);
        if (payment.payComputerCosts(new AiCostDecision(me, sa, false))) {
            game.getStack().addAndUnfreeze(sa);
            if (sa.getSplicedCards() != null && !sa.getSplicedCards().isEmpty())
                game.getAction().reveal(sa.getSplicedCards(), me, true, "Reveals spliced cards from ");
            return true;
        }
        if (!refreeze) game.getStack().unfreezeStack();
        if (sa.isSpell()) GameActionUtil.rollbackAbility(sa, original, position, payment, source);
        MatchWorker.emit(Map.of("type", "NOTICE", "message", "The action could not be paid. Previously activated mana sources remain activated."));
        return false;
    }

    /** Lets the player pick which mana sources to tap; Forge only pays whatever is still missing. */
    private void humanMana(SpellAbility sa) {
        if (!sa.isTargetNumberValid()) return;
        forge.card.mana.ManaCost cost;
        try { cost = sa.getPayCosts() == null ? null : sa.getPayCosts().getTotalMana(); } catch (RuntimeException e) { cost = null; }
        if (cost == null || cost.isZero()) return;
        Player me = getPlayer();
        List<SpellAbility> sources = new ArrayList<>();
        for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Battlefield))) {
            if (c == sa.getHostCard()) continue;
            for (SpellAbility ma : c.getManaAbilities()) {
                try { ma.setActivatingPlayer(me); if (ma.canPlay()) sources.add(ma); } catch (RuntimeException ignored) { }
            }
        }
        if (sources.isEmpty()) return;
        List<Map<String, Object>> opts = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            SpellAbility ma = sources.get(i);
            Map<String, Object> m = new LinkedHashMap<>(entity(ma.getHostCard()));
            m.put("key", "m" + i);
            String d = ma.getDescription();
            boolean multi = ma.getHostCard().getManaAbilities().size() > 1;
            m.put("label", ma.getHostCard().getName() + (multi && d != null && !d.isBlank() ? " — " + d : ""));
            opts.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", src(sa)); body.put("cost", cost.toString()); body.put("options", opts);
        body.put("pool", MatchWorker.manaPool(me));
        JsonNode a = ask("MANA", body);
        if (a == null || a.path("auto").asBoolean(false)) return; // Forge auto-taps
        for (JsonNode selected : a.path("keys")) {
            String value = selected.asText();
            for (int i = 0; i < sources.size(); i++) {
                if (!value.equals("m" + i)) continue;
                SpellAbility ma = sources.get(i);
                try {
                    if (ma.canPlay() && ComputerUtil.playNoStack(me, ma, me.getGame(), false)) MatchWorker.recordManaAction(ma);
                } catch (RuntimeException ignored) {
                    MatchWorker.emit(Map.of("type", "NOTICE", "message", "That mana source could not be activated."));
                }
            }
        }
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        if (!currentAbility.usesTargeting()) return super.chooseTargetsFor(currentAbility);
        return humanTargets(currentAbility);
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        if (possible.size() <= min && !allowRepeat) return new ArrayList<>(possible);
        List<Map<String, Object>> opts = new ArrayList<>();
        for (int i = 0; i < possible.size(); i++) {
            String d = possible.get(i).getDescription();
            opts.add(Map.of("index", i, "label", d == null || d.isBlank() ? "Mode " + (i + 1) : d));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", src(sa)); body.put("min", min); body.put("max", num); body.put("options", opts);
        JsonNode a = ask("MODE", body);
        if (a != null) {
            List<AbilitySub> out = new ArrayList<>();
            for (JsonNode i : a.path("indices")) {
                int ix = i.asInt(-1);
                if (ix >= 0 && ix < possible.size() && (allowRepeat || !out.contains(possible.get(ix)))) out.add(possible.get(ix));
            }
            if (out.size() >= min && out.size() <= num) return out;
        }
        return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", src(sa)); body.put("message", message == null || message.isBlank() ? "Do you want to do this?" : message);
        JsonNode a = ask("CONFIRM", body);
        if (a == null) return super.confirmAction(sa, mode, message, options, cardToShow, params);
        return a.path("yes").asBoolean(false);
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        List<T> list = new ArrayList<>(optionList);
        if (list.isEmpty()) return null;
        if (list.size() == 1 && !isOptional) return list.get(0);
        List<T> got = pick("CHOOSE", title, sa, list, isOptional ? 0 : 1, 1);
        if (got == null) return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        return got.isEmpty() ? null : got.get(0);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player targetedPlayer, Map<String, Object> params) {
        List<T> list = new ArrayList<>(optionList);
        if (list.size() <= min) return list;
        List<T> got = pick("CHOOSE", title, sa, list, min, Math.min(max, list.size()));
        return got != null ? got : super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        List<Card> list = new ArrayList<>();
        for (Card c : sourceList) list.add(c);
        int lo = isOptional ? 0 : Math.min(min, list.size());
        if (list.size() <= lo) return new CardCollection(list);
        List<Card> got = pick("CHOOSE", title, sa, list, lo, Math.min(max, list.size()));
        return got != null ? new CardCollection(got) : super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
    }

    // ---------- v0.11: the player makes every remaining kind of choice ----------

    /** Plain string options (creature types, colors, keywords) round-trip through CHOOSE with the text as the key. */
    private List<String> pickStrings(String title, SpellAbility sa, List<String> options, int min, int max) {
        List<Map<String, Object>> opts = new ArrayList<>();
        for (String o : options) { Map<String, Object> m = new LinkedHashMap<>(); m.put("key", o); m.put("kind", "other"); m.put("label", o); opts.add(m); }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title == null ? "" : title); body.put("source", src(sa));
        body.put("min", min); body.put("max", max); body.put("options", opts);
        while (true) {
            JsonNode a = ask("CHOOSE", body);
            if (a == null) return null;
            Set<String> chosen = keys(a);
            List<String> out = new ArrayList<>();
            for (String o : options) if (chosen.contains(o)) out.add(o);
            if (out.size() >= min && out.size() <= max && out.size() == chosen.size()) return out;
            MatchWorker.emit(Map.of("type", "NOTICE", "message", "That selection is not valid. Please choose again."));
        }
    }

    /** Tutors and library searches: the player picks from the matching cards instead of the AI. */
    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        if (decider != getPlayer()) return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
        if (delayedReveal != null) reveal(delayedReveal);
        List<Card> list = new ArrayList<>(fetchList);
        if (list.isEmpty()) return null;
        if (list.size() == 1 && !isOptional) return list.get(0);
        List<Card> got = pick("CHOOSE", selectPrompt, sa, list, isOptional ? 0 : 1, 1);
        if (got == null) return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, null, selectPrompt, isOptional, decider);
        return got.isEmpty() ? null : got.get(0);
    }

    /** Yes/no questions ("Do you want to...?") go to the player. */
    @Override
    public boolean chooseBinary(SpellAbility sa, String question, PlayerController.BinaryChoiceType kindOfChoice, Boolean defaultVal) {
        JsonNode a = ask("CONFIRM", Map.of("source", src(sa), "message", question == null || question.isBlank() ? "Do you want to do this?" : question));
        if (a == null) return super.chooseBinary(sa, question, kindOfChoice, defaultVal);
        return a.path("yes").asBoolean(defaultVal != null && defaultVal);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", src(sa)); body.put("title", title == null || title.isBlank() ? "Choose a number" : title);
        body.put("min", min); body.put("max", max);
        JsonNode a = ask("NUMBER", body);
        if (a == null) return super.chooseNumber(sa, title, min, max, params);
        return Math.max(min, Math.min(max, a.path("value").asInt(min)));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return chooseNumber(sa, title, min, max, null);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> options, Player relatedPlayer) {
        List<String> names = new ArrayList<>();
        for (Integer o : options) names.add(String.valueOf(o));
        if (names.isEmpty()) return super.chooseNumber(sa, title, options, relatedPlayer);
        List<String> got = pickStrings(title, sa, names, 1, 1);
        if (got == null || got.isEmpty()) return super.chooseNumber(sa, title, options, relatedPlayer);
        return options.get(names.indexOf(got.get(0)));
    }

    /** Creature types and similar named choices (e.g. Cavern of Souls). */
    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        List<String> types = new ArrayList<>(validTypes);
        if (types.isEmpty()) return super.chooseSomeType(kindOfType, sa, validTypes, isOptional);
        List<String> got = pickStrings("Choose a " + kindOfType.toLowerCase(), sa, types, isOptional ? 0 : 1, 1);
        if (got == null) return super.chooseSomeType(kindOfType, sa, validTypes, isOptional);
        return got.isEmpty() ? null : got.get(0);
    }

    private static final byte[] COLOR_MASKS = { MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN };
    private static final String[] COLOR_NAMES = { "White", "Blue", "Black", "Red", "Green" };

    private static List<String> colorNames(ColorSet colors, boolean allowColorless) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < COLOR_MASKS.length; i++) if ((colors.getColor() & COLOR_MASKS[i]) != 0) names.add(COLOR_NAMES[i]);
        if (allowColorless && (colors.getColor() & MagicColor.COLORLESS) != 0) names.add("Colorless");
        return names;
    }

    private static byte colorMask(String name) {
        return switch (name) {
            case "White" -> MagicColor.WHITE;
            case "Blue" -> MagicColor.BLUE;
            case "Black" -> MagicColor.BLACK;
            case "Red" -> MagicColor.RED;
            case "Green" -> MagicColor.GREEN;
            default -> MagicColor.COLORLESS;
        };
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        List<String> names = colorNames(colors, false);
        if (names.size() < 2) return super.chooseColor(message, sa, colors);
        List<String> got = pickStrings(message, sa, names, 1, 1);
        if (got == null || got.isEmpty()) return super.chooseColor(message, sa, colors);
        return colorMask(got.get(0));
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card card, ColorSet colors) {
        List<String> names = colorNames(colors, true);
        if (names.size() < 2) return super.chooseColorAllowColorless(message, card, colors);
        List<String> got = pickStrings(message, null, names, 1, 1);
        if (got == null || got.isEmpty()) return super.chooseColorAllowColorless(message, card, colors);
        return colorMask(got.get(0));
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        List<String> names = colorNames(options, false);
        if (names.size() <= min) return super.chooseColors(message, sa, min, max, options);
        List<String> got = pickStrings(message, sa, names, min, Math.min(max, names.size()));
        if (got == null) return super.chooseColors(message, sa, min, max, options);
        int mask = 0;
        for (String g : got) mask |= colorMask(g);
        return ColorSet.fromMask(mask);
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        List<Card> list = new ArrayList<>(validTargets);
        if (list.size() <= min) return new CardCollection(list);
        List<Card> got = pick("CHOOSE", message, sa, list, min, Math.min(max, list.size()));
        return got != null ? new CardCollection(got) : super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        List<Card> list = new ArrayList<>(validTargets);
        if (list.size() <= min) return new CardCollection(list);
        List<Card> got = pick("CHOOSE", message, sa, list, min, Math.min(max, list.size()));
        return got != null ? new CardCollection(got) : super.choosePermanentsToDestroy(sa, min, max, validTargets, message);
    }

    /** Discard down to maximum hand size at cleanup. */
    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        List<Card> list = new ArrayList<>(getPlayer().getCardsIn(ZoneType.Hand));
        if (list.size() <= numDiscard) return new CardCollection(list);
        List<Card> got = pick("CHOOSE", "Discard down to " + getPlayer().getMaxHandSize() + " cards", null, list, numDiscard, numDiscard);
        return got != null ? new CardCollection(got) : super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        if (p != getPlayer()) return super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
        List<Card> list = new ArrayList<>(validCards);
        if (list.size() <= min) return new CardCollection(list);
        List<Card> got = pick("CHOOSE", "Choose cards to discard", sa, list, min, Math.min(max, list.size()));
        return got != null ? new CardCollection(got) : super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        List<Card> list = new ArrayList<>(valid);
        if (list.size() <= min) return new CardCollection(list);
        List<Card> got = pick("CHOOSE", "Choose cards to reveal", null, list, min, Math.min(max, list.size()));
        return got != null ? new CardCollection(got) : super.chooseCardsToRevealFromHand(min, max, valid);
    }

    /** Cards chosen to pay a cost (e.g. discard as an additional cost). */
    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        List<Card> list = new ArrayList<>(optionList);
        int lo = isOptional ? 0 : Math.min(amount, list.size());
        if (list.size() <= lo) return new CardCollection(list);
        List<Card> got = pick("CHOOSE", prompt, sa, list, lo, Math.min(amount, list.size()));
        return got != null ? new CardCollection(got) : super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt);
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        if (spells.isEmpty()) return null;
        if (spells.size() == 1) return spells.get(0);
        List<SpellAbility> got = pick("CHOOSE", title, sa, spells, 1, 1);
        if (got == null || got.isEmpty()) return super.chooseSingleSpellForEffect(spells, sa, title, params);
        return got.get(0);
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        if (options.size() <= 1) return super.chooseCounterType(options, sa, prompt, params);
        List<String> names = new ArrayList<>();
        for (CounterType t : options) names.add(t.getName());
        List<String> got = pickStrings(prompt, sa, names, 1, 1);
        if (got == null || got.isEmpty()) return super.chooseCounterType(options, sa, prompt, params);
        return options.get(names.indexOf(got.get(0)));
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        if (options.size() <= 1) return super.chooseKeywordForPump(options, sa, prompt, tgtCard);
        List<String> got = pickStrings(prompt, sa, options, 1, 1);
        if (got == null || got.isEmpty()) return super.chooseKeywordForPump(options, sa, prompt, tgtCard);
        return got.get(0);
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        if (choices.size() <= 1) return super.chooseProtectionType(sa, choices);
        List<String> got = pickStrings("Choose a protection type", sa, choices, 1, 1);
        if (got == null || got.isEmpty()) return super.chooseProtectionType(sa, choices);
        return got.get(0);
    }
}
