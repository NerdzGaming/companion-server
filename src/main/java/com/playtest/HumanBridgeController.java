package com.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.ComputerUtilMana;
import java.util.HashMap;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.cost.CostPayment;
import forge.game.cost.Cost;
import forge.game.cost.CostPayLife;
import forge.game.spellability.OptionalCostValue;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostReturn;
import forge.game.trigger.WrappedAbility;
import forge.game.cost.CostPartMana;
import forge.game.cost.PaymentDecision;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.card.CardLists;
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
import forge.card.mana.ManaCostShard;
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
import org.apache.commons.lang3.tuple.ImmutablePair;

/**
 * Website seat. Asks the browser (via stdout PROMPT lines / stdin ACT lines) for priority,
 * mulligan, attack, block, targets, modes, yes/no and card/player choices and which mana sources to tap. Other
 * rarely used choices still fall back to Forge AI. No answer within the timeout = AI decides.
 */
public final class HumanBridgeController extends PlayerControllerAi {
    static final long TIMEOUT_MIN = Long.parseLong(System.getenv().getOrDefault("PROMPT_TIMEOUT_MIN", "15"));
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

    @Override public boolean isAI() { return false; }

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
                // A started loading arcade must not time out into automatic AI choices.
                if ("LOADING_HOLD".equals(m.path("type").asText()) && m.path("waiting").asBoolean(false)) {
                    deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(TIMEOUT_MIN);
                    continue;
                }
                if (id.equals(m.path("promptId").asText())) return m;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        MatchWorker.emit(Map.of("type", "NOTICE", "message", "SURVEIL".equals(kind) ? "No answer in time — the cards stay on top in their original order." : "No answer in time — Forge AI decided for you."));
        return null;
    }


    private static Map<String, Object> card(Card c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId()); m.put("name", c.getName());
        if (!c.isFaceDown() && c.isPlaneswalker()) m.put("loyalty", c.getCurrentLoyalty());
        if (c.isCreature()) { m.put("power", c.getNetPower()); m.put("toughness", c.getNetToughness()); }
        return m;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        Player me = getPlayer();
        // Same checkpoint Forge's desktop client takes before each priority prompt; barriers (draws, reveals, opponent actions) stay engine-owned.
        forge.game.undo.UndoManager undo = me.getGame().getUndoManager();
        undo.setMode(forge.game.undo.UndoManager.Mode.FREE);
        // v0.33: record one checkpoint per priority prompt so Undo steps back a single action. Forge skips
        // the checkpoint right after a restore, so undoing repeatedly walks back one action at a time.
        undo.checkpoint(me.getGame(), me);
        while (true) {
            List<SpellAbility> playable = new ArrayList<>();
            List<Card> candidates = new ArrayList<>();
            for (ZoneType z : ZONES) candidates.addAll(new ArrayList<>(me.getCardsIn(z)));
            // Never enumerate the hidden library. Visibility is not play permission: canPlay still decides.
            Card top = MatchWorker.visibleLibraryTop(me, me);
            if (top != null) candidates.add(top);
            for (Card c : candidates) {
                    try {
                        for (SpellAbility base : c.getAllPossibleAbilities(me, true)) {
                            base.setActivatingPlayer(me);
                            for (SpellAbility sa : GameActionUtil.getAdditionalCostSpell(base)) {
                                sa.setActivatingPlayer(me);
                                if (!sa.canPlay()) continue;
                                if (!sa.isLandAbility() && !canPayHumanCost(sa.getPayCosts(), sa, false)) continue;
                                playable.add(sa);
                            }
                        }
                    } catch (RuntimeException ignored) { }
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
                "stackSize", getPlayer().getGame().getStack().size(),
                "canUndo", undo.canUndo(me)));
            if (a == null) return super.chooseSpellAbilityToPlay();
            if (a.path("undo").asBoolean(false)) {
                if (undo.canUndo(me) && undo.undo(me.getGame())) return java.util.Collections.emptyList(); // engine re-prompts from the restored state
                String why = undo.whyNot();
                MatchWorker.emit(Map.of("type", "NOTICE", "message", "Nothing to undo" + (why == null ? "." : ": " + why + ".")));
                continue;
            }
            if (!a.has("index") || a.path("index").isNull()) return null; // pass
            int idx = a.path("index").asInt(-1);
            if (idx < 0 || idx >= playable.size()) return null;
            SpellAbility sa = playable.get(idx);
            sa.setActivatingPlayer(me);
            if (sa.isSpell()) {
                List<OptionalCostValue> optional = GameActionUtil.getOptionalCostValues(sa);
                if (!optional.isEmpty()) {
                    List<OptionalCostValue> selected = chooseOptionalCosts(sa, optional);
                    sa = GameActionUtil.addOptionalCosts(sa, selected);
                    sa.setActivatingPlayer(me);
                    if (!canPayHumanCost(sa.getPayCosts(), sa, false)) {
                        MatchWorker.emit(Map.of("type", "NOTICE", "message", "The selected costs cannot be paid. Choose another casting option."));
                        continue;
                    }
                }
            }
            if (!sa.canPlay()) continue;
            if (sa.isManaAbility()) {
                if (activateHumanMana(sa)) {
                    MatchWorker.recordManaAction(sa);
                    // v0.33: mana abilities re-prompt inside this loop, so checkpoint the tapped state; Undo then untaps only this source.
                    undo.checkpoint(me.getGame(), me);
                }
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
    /** v0.46: mulligans taken, so free mulligans are applied once from our own count. */
    private int mulligansTaken = 0;

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        List<Map<String, Object>> hand = new ArrayList<>();
        for (Card c : getPlayer().getCardsIn(ZoneType.Hand)) hand.add(card(c));
        // v0.25: tell the player who goes first before keep/mulligan, for pregame effects and play/draw decisions.
        boolean onPlay = firstPlayer == null || firstPlayer.equals(getPlayer());
        String firstName = firstPlayer == null ? "" : firstPlayer.getName();
        JsonNode a = ask("MULLIGAN", Map.of("hand", hand, "cardsToReturn", Math.max(0, mulligansTaken - MatchWorker.freeMulligans), "freeMulligan", mulligansTaken < MatchWorker.freeMulligans, "onThePlay", onPlay, "firstPlayer", firstName));
        boolean keep = a == null ? super.mulliganKeepHand(firstPlayer, cardsToReturn) : a.path("keep").asBoolean(true);
        if (keep) settleDeferredTuck();
        else { deferredTuck = 0; mulligansTaken++; } // the whole hand is shuffled away; nothing to bottom from it
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
        cardsToReturn = Math.max(0, mulligansTaken - MatchWorker.freeMulligans);
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
        List<GameEntity> defenders = new ArrayList<>(combat.getDefenders());
        if (defenders.isEmpty()) return;
        List<Card> able = new ArrayList<>();
        Map<Integer, List<String>> legal = new LinkedHashMap<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            List<String> targets = new ArrayList<>();
            for (GameEntity d : defenders) if (CombatUtil.canAttack(c, d)) targets.add(key(d));
            if (!targets.isEmpty()) { able.add(c); legal.put(c.getId(), targets); }
        }
        if (able.isEmpty()) return;
        List<Map<String, Object>> opts = new ArrayList<>();
        for (Card c : able) opts.add(card(c));
        List<Map<String, Object>> targets = new ArrayList<>();
        for (GameEntity d : defenders) targets.add(entity(d));
        while (true) {
            JsonNode a = ask("ATTACK", Map.of("attackers", opts, "defender", defenders.get(0).getName(), "defenders", targets, "legalDefenders", legal));
            if (a == null) { super.declareAttackers(attacker, combat); return; }
            Map<Integer, String> chosen = new LinkedHashMap<>();
            boolean unique = a.path("assignments").isArray();
            for (JsonNode assignment : a.path("assignments")) {
                int id = assignment.path("attacker").asInt(-1);
                if (chosen.put(id, assignment.path("defender").asText()) != null) unique = false;
            }
            if (unique && HumanChoices.validAttack(legal, chosen)) {
                combat.clearAttackers();
                for (Card c : able) for (GameEntity d : defenders)
                    if (key(d).equals(chosen.get(c.getId())) && CombatUtil.canAttack(c, d)) combat.addAttacker(c, d);
                if (CombatUtil.validateAttackers(combat)) return;
                combat.clearAttackers();
            }
            MatchWorker.emit(Map.of("type", "NOTICE", "message", "That attack does not meet the combat restrictions. Choose attackers and defenders again."));
        }
    }

    /** Reveal only the human's looked-at cards; Forge performs the moves and triggers. */
    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        if (topN.isEmpty()) return ImmutablePair.of(new CardCollection(), new CardCollection());
        List<Map<String, Object>> options = new ArrayList<>();
        List<Integer> offered = new ArrayList<>();
        for (Card c : topN) { options.add(card(c)); offered.add(c.getId()); }
        while (true) {
            JsonNode a = ask("SURVEIL", Map.of("cards", options));
            // A missed answer must not secretly evaluate or mill the player's cards.
            if (a == null) return ImmutablePair.of(new CardCollection(topN), new CardCollection());
            List<Integer> topIds = new ArrayList<>(), graveIds = new ArrayList<>();
            for (JsonNode id : a.path("top")) topIds.add(id.asInt(-1));
            for (JsonNode id : a.path("graveyard")) graveIds.add(id.asInt(-1));
            if (a.path("top").isArray() && a.path("graveyard").isArray() && HumanChoices.validPartition(offered, topIds, graveIds)) {
                CardCollection top = new CardCollection(), grave = new CardCollection();
                for (Integer id : topIds) for (Card c : topN) if (c.getId() == id) top.add(c);
                for (Integer id : graveIds) for (Card c : topN) if (c.getId() == id) grave.add(c);
                return ImmutablePair.of(top, grave);
            }
            MatchWorker.emit(Map.of("type", "NOTICE", "message", "Each surveilled card must be kept or put in the graveyard exactly once. Please choose again."));
        }
    }

    /** v0.36: Scry (Preordain, scry lands) — the player sees the cards and splits them top/bottom. */
    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        if (topN.isEmpty()) return ImmutablePair.of(new CardCollection(), new CardCollection());
        ImmutablePair<CardCollection, CardCollection> got = askLibraryArrange("scry", topN);
        return got != null ? got : ImmutablePair.of(new CardCollection(topN), new CardCollection());
    }

    /** v0.36: Ponder, Brainstorm, Sensei's Top — the player orders cards going onto the library. */
    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, forge.game.zone.ZoneType destinationZone, SpellAbility source) {
        if (destinationZone != forge.game.zone.ZoneType.Library || cards.size() < 2) return super.orderMoveToZoneList(cards, destinationZone, source);
        ImmutablePair<CardCollection, CardCollection> got = askLibraryArrange("order", new CardCollection(cards));
        if (got == null) return cards;
        // Forge places this list so the last card ends on top; the prompt answers top-first.
        CardCollection reversed = new CardCollection();
        for (int i = got.left.size() - 1; i >= 0; i--) reversed.add(got.left.get(i));
        return reversed;
    }

    private ImmutablePair<CardCollection, CardCollection> askLibraryArrange(String mode, CardCollection topN) {
        List<Map<String, Object>> options = new ArrayList<>();
        List<Integer> offered = new ArrayList<>();
        for (Card c : topN) { options.add(card(c)); offered.add(c.getId()); }
        while (true) {
            JsonNode a = ask("SURVEIL", Map.of("cards", options, "mode", mode));
            if (a == null) return null;
            List<Integer> topIds = new ArrayList<>(), bottomIds = new ArrayList<>();
            for (JsonNode id : a.path("top")) topIds.add(id.asInt(-1));
            for (JsonNode id : a.path("graveyard")) bottomIds.add(id.asInt(-1));
            boolean ok = a.path("top").isArray() && HumanChoices.validPartition(offered, topIds, bottomIds) && ("scry".equals(mode) || bottomIds.isEmpty());
            if (ok) {
                CardCollection top = new CardCollection(), bottom = new CardCollection();
                for (Integer id : topIds) for (Card c : topN) if (c.getId() == id) top.add(c);
                for (Integer id : bottomIds) for (Card c : topN) if (c.getId() == id) bottom.add(c);
                return ImmutablePair.of(top, bottom);
            }
            MatchWorker.emit(Map.of("type", "NOTICE", "message", "Each card must be placed exactly once. Please choose again."));
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
        // v0.30: tell the table exactly which attackers each creature may legally block (flying, reach, menace, etc.).
        Map<String, List<Integer>> legal = new LinkedHashMap<>();
        for (Card b : blockers) {
            List<Integer> ok = new ArrayList<>();
            for (Card atk : attackers) if (legalBlock(atk, b, combat)) ok.add(atk.getId());
            legal.put(String.valueOf(b.getId()), ok);
        }
        JsonNode a = ask("BLOCK", Map.of("attackers", at, "blockers", bl, "legal", legal));
        // v0.39: never let the AI pick blocks for the human; an unanswered prompt means no blocks.
        if (a == null) { MatchWorker.emit(Map.of("type", "NOTICE", "message", "No block chosen — your creatures did not block.")); return; }
        List<String> refused = new ArrayList<>();
        for (JsonNode b : a.path("blocks")) {
            Card blk = null, atk = null;
            for (Card c : blockers) if (c.getId() == b.path("blocker").asInt()) blk = c;
            for (Card c : attackers) if (c.getId() == b.path("attacker").asInt()) atk = c;
            if (blk == null || atk == null) continue;
            if (legalBlock(atk, blk, combat)) combat.addBlocker(atk, blk);
            else refused.add(blk.getName() + " can't block " + atk.getName());
        }
        if (!refused.isEmpty()) MatchWorker.emit(Map.of("type", "NOTICE", "message", String.join("; ", refused) + "."));
    }

    /** Evasion (flying, shadow, landwalk, "can't be blocked except by") plus combat limits; both checks must pass. */
    private static boolean legalBlock(Card attacker, Card blocker, Combat combat) {
        try {
            return CombatUtil.canBlock(blocker, combat) && CombatUtil.canBlock(attacker, blocker) && CombatUtil.canBlock(attacker, blocker, combat);
        } catch (RuntimeException e) { return false; }
    }

    /** Demonic Consultation and other "name a card": the player names any card the effect allows, validated here. */
    @Override
    public String chooseCardName(SpellAbility sa, java.util.function.Predicate<forge.card.ICardFace> cpp, String valid, String message) {
        List<String> allowed = new ArrayList<>();
        for (forge.card.ICardFace face : forge.StaticData.instance().getCommonCards().getAllFaces()) {
            try { if (cpp == null || cpp.test(face)) allowed.add(face.getName()); } catch (RuntimeException ignored) { }
        }
        String got = askCardName(sa, message, allowed);
        return got != null ? got : super.chooseCardName(sa, cpp, valid, message);
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<forge.card.ICardFace> faces, String message) {
        List<String> allowed = new ArrayList<>();
        for (forge.card.ICardFace f : faces) allowed.add(f.getName());
        String got = askCardName(sa, message, allowed);
        return got != null ? got : super.chooseCardName(sa, faces, message);
    }

    private String askCardName(SpellAbility sa, String message, List<String> allowed) {
        if (allowed.isEmpty()) return null;
        Map<String, String> byLower = new java.util.HashMap<>();
        for (String n : allowed) byLower.putIfAbsent(n.toLowerCase(java.util.Locale.ROOT), n);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", src(sa));
        body.put("message", message == null || message.isBlank() ? "Name a card" : message);
        // Small lists (e.g. "name a creature card in your deck") are sent whole; open lists use card search on the website.
        if (allowed.size() <= 400) body.put("names", new ArrayList<>(new java.util.TreeSet<>(allowed)));
        else body.put("total", allowed.size());
        while (true) {
            JsonNode a = ask("NAME", body);
            if (a == null) return null;
            String n = a.path("name").asText("").trim();
            String exact = byLower.get(n.toLowerCase(java.util.Locale.ROOT));
            if (exact == null && n.contains(" // ")) exact = byLower.get(n.split(" // ")[0].toLowerCase(java.util.Locale.ROOT));
            if (exact != null) return exact;
            MatchWorker.emit(Map.of("type", "NOTICE", "message", "\"" + n + "\" isn't a card this effect lets you name. Choose again."));
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
            if (c.isInPlay()) m.put("tapped", c.isTapped());
            List<String> attached = new ArrayList<>();
            try { for (Card a : c.getAttachedCards()) attached.add(a.getName()); } catch (RuntimeException ignored) { }
            if (!attached.isEmpty()) m.put("attached", attached);
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

    /**
     * v0.27: "for each card type, choose a card" (Atraxa, DigMultiple). Shows every revealed card at once with one
     * slot per type; each card fills at most one slot. Validated server-side, then each pick is logged separately.
     */
    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        Map<Integer, Card> all = new LinkedHashMap<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, CardCollection> g : validMap.entrySet()) {
            List<Integer> ids = new ArrayList<>();
            for (Card c : g.getValue()) { all.putIfAbsent(c.getId(), c); ids.add(c.getId()); }
            groups.add(Map.of("key", g.getKey(), "label", g.getKey(), "cardIds", ids));
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Card c : all.values()) { Map<String, Object> m = card(c); m.put("typeLine", c.getType().toString()); cards.add(m); }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title == null ? "" : title); body.put("source", src(sa)); body.put("optional", isOptional);
        body.put("groups", groups); body.put("cards", cards);
        while (true) {
            JsonNode a = ask("MULTIPICK", body);
            if (a == null) return super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
            CardCollection out = new CardCollection();
            Map<String, Card> byGroup = new LinkedHashMap<>();
            boolean ok = true;
            JsonNode picks = a.path("picks");
            for (Map.Entry<String, CardCollection> g : validMap.entrySet()) {
                JsonNode v = picks.path(g.getKey());
                if (v.isMissingNode() || v.isNull()) continue;
                Card c = all.get(v.asInt(-1));
                if (c == null || !g.getValue().contains(c) || out.contains(c)) { ok = false; break; }
                out.add(c); byGroup.put(g.getKey(), c);
            }
            if (ok && (isOptional || !out.isEmpty())) {
                if (MatchWorker.actionLog != null)
                    for (Map.Entry<String, Card> e : byGroup.entrySet()) MatchWorker.actionLog.revealedPick(getPlayer().getName(), e.getValue().getName(), e.getKey());
                return out;
            }
            MatchWorker.emit(Map.of("type", "NOTICE", "message", "That selection is not valid. Each card can fill only one type. Please choose again."));
        }
    }

    /** Asks the player to pick between min and max of the options; null = no valid answer (caller falls back to AI). */
    private <T> List<T> pick(String kind, String title, SpellAbility sa, List<T> options, int min, int max) {
        return pick(kind, title, sa, options, min, max, null);
    }

    /** v0.46: every looked-at card (Dig/Break Out) is shown; ineligible ones are display-only and never accepted. */
    private List<Card> revealContext(DelayedReveal reveal) {
        List<Card> out = new ArrayList<>();
        if (reveal == null) return out;
        Game game = getPlayer().getGame();
        try { for (var cv : reveal.getCards()) { Card c = game.findByView(cv); if (c != null) out.add(c); } } catch (RuntimeException ignored) { }
        return out;
    }

    private <T> List<T> pick(String kind, String title, SpellAbility sa, List<T> options, int min, int max, List<Card> context) {
        List<Map<String, Object>> opts = new ArrayList<>();
        Set<String> valid = new HashSet<>();
        for (T o : options) { opts.add(entity(o)); valid.add(key(o)); }
        if (context != null) for (Card c : context) if (!valid.contains(key(c))) { Map<String, Object> m = entity(c); m.put("unavailable", true); opts.add(m); }
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

    /** Resolve mana outside the stack, but use human costs rather than ComputerUtil's AiCostDecision. */
    private boolean activateHumanMana(SpellAbility sa) {
        Player me = getPlayer();
        sa.setActivatingPlayer(me);
        if (!sa.isManaAbility() || !sa.canPlay() || !canPayHumanCost(sa.getPayCosts(), sa, false)) return false;
        sa.resetPaidHash();
        sa.setPaidLife(0);
        if (!announceHumanValues(sa)) return false;
        CostPayment payment = new CostPayment(sa.getPayCosts(), sa);
        if (!payment.payComputerCosts(new HumanCostDecision(me, sa, false))) return false;
        AbilityUtils.resolve(sa);
        return true;
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
        if (!announceHumanValues(sa) || (sa.getApi() == ApiType.Charm && !CharmEffect.makeChoices(sa)) || !humanTargets(sa)) {
            if (sa.isSpell()) GameActionUtil.rollbackAbility(sa, original, position, payment, source);
            return false;
        }
        boolean refreeze = game.getStack().isFrozen();
        game.getStack().freezeStack(sa);
        if (payment.payComputerCosts(new HumanCostDecision(me, sa, false))) {
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

    /** Mirror PlaySpellAbility's announcement boundary before targets and costs. */
    private boolean announceHumanValues(SpellAbility sa) {
        if (sa.isCopied() || sa.isWrapper()) return true;
        boolean needX = true;
        String announce = sa.getParam("Announce");
        if (announce != null) for (String name : announce.split(",")) {
            String variable = name.trim();
            var range = AbilityUtils.getAnnouncementBounds(sa, variable);
            Integer value = announceRequirements(sa, range.getMinimum(), range.getMaximum(), variable);
            if (value == null) return false;
            if ("X".equalsIgnoreCase(variable)) { sa.setXManaCostPaid(value); needX = false; }
            else { sa.setSVar(variable, value.toString()); sa.getHostCard().setSVar(variable, value.toString()); }
        }
        Cost cost = sa.getPayCosts();
        if (needX && cost != null && cost.hasXInAnyCostPart()) {
            String definition = sa.getParamOrDefault("XAlternative", sa.getSVar("X"));
            if (ManaPaymentChoices.shouldAnnounceX(sa.isCopied(), sa.isWrapper(), cost.hasXInAnyCostPart(), definition)) {
                var range = AbilityUtils.getAnnouncementBounds(sa, "X");
                Integer value = announceRequirements(sa, range.getMinimum(), range.getMaximum(), "X");
                if (value == null) return false;
                sa.setXManaCostPaid(value);
            }
        } else if (needX) sa.setXManaCostPaid(null);
        return true;
    }

    /** Called only inside actual adjusted mana payment, including pool-only/life-only casts. */
    private JsonNode humanMana(SpellAbility sa, ManaCostBeingPaid cost, boolean effect) {
        Player me = getPlayer();
        List<SpellAbility> sources = new ArrayList<>();
        for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Battlefield))) {
            if (c == sa.getHostCard()) continue;
            for (SpellAbility ma : c.getManaAbilities()) {
                try { ma.setActivatingPlayer(me); if (ma.canPlay()) sources.add(ma); } catch (RuntimeException ignored) { }
            }
        }
        List<Map<String, Object>> opts = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            SpellAbility ma = sources.get(i);
            Map<String, Object> m = new LinkedHashMap<>(entity(ma.getHostCard()));
            m.put("key", "m" + i);
            String d = ma.getDescription();
            boolean multi = ma.getHostCard().getManaAbilities().size() > 1;
            m.put("label", ma.getHostCard().getName() + (multi && d != null && !d.isBlank() ? " — " + d : ""));
            try { if (ma.getManaPart() != null) m.put("produces", ma.getManaPart().getOrigProduced()); } catch (RuntimeException ignored) { }
            try {
                Cost pc = ma.getPayCosts();
                if (pc != null && pc.hasManaCost()) m.put("extraCost", pc.toSimpleString()); // e.g. Nykthos {2},{T}
            } catch (RuntimeException ignored) { }
            if (d != null && !d.isBlank()) m.put("text", d);
            if (protectedTargets(sa).contains(ma.getHostCard())) m.put("targeted", true);
            opts.add(m);
        }
        List<Map<String, Object>> shards = new ArrayList<>();
        int count = 0;
        for (ManaCostShard shard : new java.util.LinkedHashSet<>(cost.getUnpaidShards())) if (shard.isPhyrexian()) {
            int n = cost.getUnpaidShards(shard);
            count += n;
            shards.add(Map.of("key", shard.name(), "label", shard.toString(), "count", n));
        }
        int maxLife = 0;
        while (maxLife < count && me.canPayLife((maxLife + 1) * 2, effect, sa)) maxLife++;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", src(sa)); body.put("cost", cost.toManaCost().toString()); body.put("options", opts);
        body.put("pool", MatchWorker.manaPool(me));
        if (sa.getXManaCostPaid() != null) body.put("x", sa.getXManaCostPaid());
        if (count > 0) body.put("phyrexian", Map.of("shards", shards, "maxLifePips", maxLife));
        JsonNode a;
        while (true) {
            a = ask("MANA", body);
            if (a == null) return null;
            Map<String, Integer> offered = new LinkedHashMap<>(), selected = new LinkedHashMap<>();
            for (Map<String, Object> shard : shards) offered.put((String) shard.get("key"), (Integer) shard.get("count"));
            boolean valid = !a.has("phyrexian") || a.path("phyrexian").isObject();
            var fields = a.path("phyrexian").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().isIntegralNumber() || !field.getValue().canConvertToInt()) valid = false;
                selected.put(field.getKey(), field.getValue().asInt(-1));
            }
            if (valid && ManaPaymentChoices.validLifeSelection(offered, selected, maxLife)) break;
            MatchWorker.emit(Map.of("type", "NOTICE", "message", "Choose only offered Phyrexian symbols within your life-payment limit."));
        }
        if (!a.path("auto").asBoolean(false)) for (String selected : keys(a)) {
            for (int i = 0; i < sources.size(); i++) if (selected.equals("m" + i)) {
                try { if (activateHumanMana(sources.get(i))) MatchWorker.recordManaAction(sources.get(i)); }
                catch (RuntimeException ignored) { MatchWorker.emit(Map.of("type", "NOTICE", "message", "That mana source could not be activated.")); }
            }
        }
        return a;
    }

    // ---------- v0.20: triggered abilities (ETB etc.) and X announcements are the player's call ----------

    private static boolean chainTargets(SpellAbility sa) {
        for (SpellAbility c = sa; c != null; c = c.getSubAbility()) if (c.usesTargeting()) return true;
        return false;
    }

    /** True when this bridge should pick for the trigger itself instead of Forge AI's doTrigger. */
    private boolean humanTrigger(SpellAbility sa) {
        return sa.getApi() != ApiType.Charm && !sa.hasParam("TargetingPlayer")
            && (chainTargets(sa) || sa.isOptionalTrigger() || costedTrigger(sa) || reflexive(sa));
    }

    /** v0.46: "you may pay {E}{E}{E}" style trigger costs — the player decides, not Forge AI. */
    private static boolean costedTrigger(SpellAbility sa) {
        Cost c = sa.getPayCosts();
        return c != null && !c.isNoCost();
    }

    /** v0.46: reflexive "when you do" (Guide of Souls) — the player chooses the reflexive target. */
    private static boolean reflexive(SpellAbility sa) {
        for (SpellAbility c = sa; c != null; c = c.getSubAbility()) if (c.getApi() == ApiType.ImmediateTrigger) return true;
        return false;
    }

    /** Optional "you may" confirm, then human targets. False = trigger is not put on the stack. */
    private boolean prepareHumanTrigger(SpellAbility sa) {
        sa.setActivatingPlayer(getPlayer());
        if (sa.isOptionalTrigger()) {
            JsonNode a = ask("CONFIRM", Map.of("source", src(sa), "message", "Use triggered ability: " + sa.getStackDescription() + "?"));
            if (a != null && !a.path("yes").asBoolean(false)) return false;
        } else if (costedTrigger(sa)) {
            Cost c = sa.getPayCosts();
            String what = c != null && !c.isNoCost() ? "Pay " + c.toSimpleString() + " for " : "Use ";
            JsonNode a = ask("CONFIRM", Map.of("source", src(sa), "message", what + sa.getStackDescription() + "?"));
            if (a != null && !a.path("yes").asBoolean(false)) return false;
        }
        if (!chainTargets(sa)) return true;
        return humanTargets(sa);
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        for (SpellAbility sa : orderSimultaneousSa(activePlayerSAs)) {
            if (sa.isTrigger() && !sa.isCopied() && humanTrigger(sa)) {
                if (prepareHumanTrigger(sa)) ComputerUtil.playStack(sa, getPlayer(), getGame());
            } else {
                super.orderAndPlaySimultaneousSa(new ArrayList<>(List.of(sa)));
            }
        }
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (!humanTrigger(wrapperAbility)) return super.playTrigger(host, wrapperAbility, isMandatory);
        if (!prepareHumanTrigger(wrapperAbility)) return false;
        return ComputerUtil.playNoStack(wrapperAbility.getActivatingPlayer(), wrapperAbility, getGame(), true);
    }

    /** Engine bounds apply; estimates must not force X to zero or cap legal large values. */
    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        int cap = max;
        Cost cost = ability.getPayCosts();
        if ("X".equalsIgnoreCase(announce) && cost != null) {
            Integer nonMana = cost.getMaxForNonManaX(ability, getPlayer(), false);
            if (nonMana != null) cap = Math.min(cap, nonMana);
        }
        Map<String, Object> body = new HashMap<>();
        if ("X".equalsIgnoreCase(announce) && cost != null && cost.hasManaCost()) {
            CostPartMana part = cost.getCostMana();
            int per = part == null ? 1 : Math.max(1, part.getAmountOfX());
            int afford = Integer.MAX_VALUE;
            try { afford = Math.max(0, ComputerUtilMana.determineLeftoverMana(ability, getPlayer(), false)) / per; }
            catch (RuntimeException ignored) { }
            if (afford != Integer.MAX_VALUE) cap = Math.min(cap, afford);
            body.put("x", true);
            body.put("perX", per);
            body.put("baseCost", part == null ? "" : part.getMana().getShortString());
            body.put("affordable", cap);
        }
        if (min > cap) {
            MatchWorker.emit(Map.of("type", "NOTICE", "message", "You can't produce enough mana for the minimum X."));
            return null;
        }
        body.put("source", src(ability)); body.put("title", "Choose a value for " + announce); body.put("min", min); body.put("max", cap);
        while (true) {
            JsonNode a = ask("NUMBER", body);
            if (a == null) return super.announceRequirements(ability, min, cap, announce);
            JsonNode value = a.path("value");
            if (value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= min && value.asInt() <= cap) return value.asInt();
            MatchWorker.emit(Map.of("type", "NOTICE", "message", "Choose a number within the offered range."));
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
        String text = message == null || message.isBlank() ? "Do you want to do this?" : message;
        // v0.35: commander moving to graveyard/exile names the card and both destinations.
        if (mode == PlayerActionConfirmMode.ChangeZoneToAltDestination && sa != null && sa.getHostCard() != null && sa.getHostCard().isCommander()) {
            Card cmd = sa.getHostCard();
            text = "Your commander " + cmd.getName() + " is going to the " + zoneLabel(cmd.getZone() == null ? null : cmd.getZone().getZoneType()) + ". Move it to the command zone instead?";
            body.put("commanderMove", true); body.put("card", card(cmd));
            body.put("yesLabel", "Command zone"); body.put("noLabel", "Leave it there");
        }
        body.put("source", src(sa)); body.put("message", text);
        JsonNode a = ask("CONFIRM", body);
        if (a == null) return super.confirmAction(sa, mode, message, options, cardToShow, params);
        return a.path("yes").asBoolean(false);
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        List<T> list = new ArrayList<>(optionList);
        List<Card> seen = revealContext(delayedReveal);
        if (list.isEmpty()) { if (delayedReveal != null) reveal(delayedReveal); return null; }
        if (list.size() == 1 && !isOptional && seen.size() <= 1) return list.get(0);
        List<T> got = pick("CHOOSE", title, sa, list, isOptional ? 0 : 1, 1, seen);
        if (got == null) return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        return got.isEmpty() ? null : got.get(0);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player targetedPlayer, Map<String, Object> params) {
        List<T> list = new ArrayList<>(optionList);
        List<Card> seen = revealContext(delayedReveal);
        if (list.size() <= min && seen.size() <= list.size()) { if (delayedReveal != null) reveal(delayedReveal); return list; }
        List<T> got = pick("CHOOSE", title, sa, list, min, Math.min(max, list.size()), seen);
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

    /** Multi-card searches ("search for up to two basic lands"): the player picks the whole set. */
    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        if (decider != getPlayer()) return super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider);
        List<Card> list = new ArrayList<>(fetchList);
        if (list.isEmpty()) return new ArrayList<>();
        List<Card> got = pick("CHOOSE", selectPrompt, sa, list, Math.min(min, list.size()), Math.min(max, list.size()));
        return got == null ? super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider) : got;
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
    // ---------- v0.12: costs (discard / exile / sacrifice) and Phyrexian life are the player's call ----------

    /** Only affordability and engine restrictions, never AI strategic life/planeswalker thresholds. */
    private boolean canPayHumanCost(Cost cost, SpellAbility sa, boolean effect) {
        if (cost == null) return true;
        // v0.38: affordability scans must never prompt the human (e.g. convoke pickers on every priority pass).
        boolean prev = checkingAffordability;
        checkingAffordability = true;
        try {
            return ComputerUtilMana.canPayManaCost(cost, sa, getPlayer(), 0, effect)
                && CostPayment.canPayAdditionalCosts(cost, sa, effect, getPlayer());
        } finally { checkingAffordability = prev; }
    }
    private boolean checkingAffordability = false;

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility sa, List<OptionalCostValue> optional) {
        List<OptionalCostValue> selected = new ArrayList<>();
        for (OptionalCostValue value : optional) {
            JsonNode a = ask("CONFIRM", Map.of("source", src(sa), "message", "Add optional cost: " + value + "?"));
            if (a != null && a.path("yes").asBoolean(false)) selected.add(value);
        }
        return selected;
    }

    /** Shock-land ETB and other UnlessCost effects must ask the human, not willPayUnlessCost AI. */
    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        if (!canPayHumanCost(cost, sa, true)) return false;
        boolean shock = sa.hasParam("ETB") && sa.getApi() == ApiType.Tap
            && cost.getCostParts().stream().anyMatch(part -> part instanceof CostPayLife life && life.getAbilityAmount(sa) == 2);
        String message = shock ? "Pay 2 life to have " + src(sa) + " enter untapped?"
            : "Pay " + cost.toSimpleString() + " to prevent this effect?";
        JsonNode a = ask("CONFIRM", Map.of("source", src(sa), "message", message));
        if (a == null || !a.path("yes").asBoolean(false)) return false;
        return new CostPayment(cost, sa).payComputerCosts(new HumanCostDecision(getPlayer(), sa, true));
    }

    /** Cost payments route through this decision maker; card-choosing costs ask the player, everything else stays engine-default. */
    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player p, SpellAbility ability, boolean effect, String prompt) {
        if (p != getPlayer()) return super.getCostDecisionMaker(p, ability, effect, prompt);
        return new HumanCostDecision(p, ability, effect);
    }

    private final class HumanCostDecision extends AiCostDecision {
        HumanCostDecision(Player p, SpellAbility sa, boolean effect) { super(p, sa, effect); }

        @Override
        public PaymentDecision visit(CostPayLife cost) {
            int amount = cost.getAbilityAmount(ability);
            return player.canPayLife(amount, isEffect(), ability) ? PaymentDecision.number(amount) : null;
        }

        private PaymentDecision choose(String verb, CardCollectionView valid, int amount, PaymentDecision fallback) {
            List<Card> list = new ArrayList<>();
            for (Card c : valid) list.add(c);
            if (amount <= 0 || list.size() < amount) return fallback;
            if (list.size() == amount) return PaymentDecision.card(new CardCollection(list));
            List<Card> got = pick("CHOOSE", verb + " " + amount + " card" + (amount == 1 ? "" : "s") + " to pay the cost", ability, list, amount, amount);
            return got == null ? fallback : PaymentDecision.card(new CardCollection(got));
        }

        @Override
        public PaymentDecision visit(CostDiscard cost) {
            String type = cost.getType();
            if (cost.payCostFromSource() || type.equals("Hand") || type.equals("LastDrawn") || type.equals("Random") || type.contains("WithSameName") || type.contains("+WithDifferentNames")) return super.visit(cost);
            int c = cost.getAbilityAmount(ability);
            CardCollectionView valid = CardLists.getValidCards(player.getCardsIn(ZoneType.Hand), type.split(";"), player, source, ability);
            return choose("Discard", valid, c, null);
        }

        @Override
        public PaymentDecision visit(CostExile cost) {
            String type = cost.getType();
            if (cost.payCostFromSource() || type.equals("All") || type.contains("FromTopGrave") || type.contains("+withTotalCMCGE")) return super.visit(cost);
            if (cost.getFrom().size() == 1 && cost.getFrom().get(0).equals(ZoneType.Library)) return super.visit(cost);
            int c = cost.getAbilityAmount(ability);
            CardCollectionView pool = cost.zoneRestriction != 1 ? player.getGame().getCardsIn(cost.getFrom()) : player.getCardsIn(cost.getFrom());
            CardCollectionView valid = CardLists.getValidCards(pool, type.split(";"), player, source, ability);
            return choose("Exile", valid, c, null);
        }

        @Override
        public PaymentDecision visit(CostReturn cost) {
            if (cost.payCostFromSource()) return super.visit(cost);
            int c = cost.getAbilityAmount(ability);
            CardCollectionView valid = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability);
            return choose("Return", valid, c, null);
        }

        @Override
        public PaymentDecision visit(CostSacrifice cost) {
            if (cost.payCostFromSource() || cost.getType().equals("OriginalHost") || cost.getAmount().equals("All")) return super.visit(cost);
            int c = cost.getAbilityAmount(ability);
            CardCollectionView valid = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability);
            return choose("Sacrifice", valid, c, null);
        }
    }

    /** Expand announced X and cost adjustments through Forge, then take one human payment decision. */
    @Override
    public boolean payManaCost(forge.card.mana.ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        Player me = getPlayer();
        ManaCostBeingPaid being = ComputerUtilMana.calculateManaCost(new Cost(toPay, effect), sa, me, false, 0, effect);
        if (being.isPaid()) return true;
        JsonNode answer = humanMana(sa, being, effect);
        if (answer == null) return ComputerUtilMana.payManaCost(being, sa, me, effect); // explicit inactivity fallback only
        int life = 0;
        for (ManaCostShard shard : new java.util.LinkedHashSet<>(being.getUnpaidShards())) if (shard.isPhyrexian()) {
            int count = answer.path("phyrexian").path(shard.name()).asInt(0);
            if (count > 0) { being.decreaseShard(shard, count); life += count * 2; }
        }
        if (life > 0 && !me.canPayLife(life, effect, sa)) return false;
        String prior = sa.getParam("AIPhyrexianPayment");
        sa.putParam("AIPhyrexianPayment", "Never");
        try {
            boolean paid = being.isPaid() || payAvoidingTargets(being, sa, me, effect);
            if (!paid) return false;
            if (life > 0) {
                if (!me.payLife(life, sa, effect)) return false;
                sa.setSpendPhyrexianMana(true);
            }
            return true;
        } finally {
            if (prior == null) sa.removeParam("AIPhyrexianPayment"); else sa.putParam("AIPhyrexianPayment", prior);
        }
    }

    /** Cards this spell targets on the battlefield (e.g. Utopia Sprawl's land), which auto-pay should leave untapped. */
    private static List<Card> protectedTargets(SpellAbility sa) {
        List<Card> out = new ArrayList<>();
        try {
            for (SpellAbility c = sa; c != null; c = c.getSubAbility())
                if (c.getTargets() != null) for (Card t : c.getTargets().getTargetCards()) if (t.isInPlay() && !t.isTapped()) out.add(t);
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** v0.23: auto-pay first tries without tapping the spell's own targets, then falls back to any source. */
    private static boolean payAvoidingTargets(ManaCostBeingPaid being, SpellAbility sa, Player me, boolean effect) {
        List<Card> held = protectedTargets(sa);
        if (held.isEmpty()) return ComputerUtilMana.payManaCost(being, sa, me, effect);
        for (Card c : held) c.setTapped(true);
        boolean canWithout;
        try { canWithout = ComputerUtilMana.canPayManaCost(new Cost(being.toManaCost(), effect), sa, me, 0, effect); }
        catch (RuntimeException e) { canWithout = false; }
        if (!canWithout) { for (Card c : held) c.setTapped(false); return ComputerUtilMana.payManaCost(being, sa, me, effect); }
        try { return ComputerUtilMana.payManaCost(being, sa, me, effect); }
        finally { for (Card c : held) c.setTapped(false); }
    }

    private static String zoneLabel(ZoneType z) {
        if (z == null) return "new zone";
        return switch (z) { case Graveyard -> "graveyard"; case Exile -> "exile"; case Hand -> "hand"; case Library -> "library"; default -> z.name().toLowerCase(); };
    }

    /** v0.35: optional replacement effects (commander to hand/library -> command zone) are the player's decision. */
    @Override
    public boolean confirmReplacementEffect(forge.game.replacement.ReplacementEffect re, SpellAbility effectSA, GameEntity affected, String question) {
        Map<String, Object> body = new LinkedHashMap<>();
        String text = question == null || question.isBlank() ? "Apply this replacement effect?" : question;
        if (affected instanceof Card c && c.isCommander() && c.getOwner() == getPlayer()) {
            text = "Your commander " + c.getName() + " is changing zones. Move it to the command zone instead?";
            body.put("commanderMove", true); body.put("card", card(c));
            body.put("yesLabel", "Command zone"); body.put("noLabel", "Let it move");
        }
        body.put("source", re.getHostCard() == null ? "" : re.getHostCard().getName()); body.put("message", text);
        JsonNode a = ask("CONFIRM", body);
        if (a == null) return super.confirmReplacementEffect(re, effectSA, affected, question);
        return a.path("yes").asBoolean(false);
    }

    /** v0.35: cards revealed by the opponent become public — logged by name and announced on the table. */
    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addSuffix) {
        super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        announceReveal(new ArrayList<>(cards), zone, owner);
    }

    @Override
    public void reveal(List<forge.game.card.CardView> cards, ZoneType zone, forge.game.player.PlayerView owner, String messagePrefix, boolean addSuffix) {
        super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        Game game = getPlayer().getGame();
        List<Card> real = new ArrayList<>();
        for (var cv : cards) { Card c = game.findByView(cv); if (c != null) real.add(c); }
        Player p = owner == null ? null : game.getPlayer(owner);
        announceReveal(real, zone, p);
    }

    private void announceReveal(List<Card> cards, ZoneType zone, Player owner) {
        if (cards.isEmpty() || owner == null) return;
        if (owner == getPlayer()) {
            List<Map<String, Object>> mine = new ArrayList<>();
            for (Card c : cards) mine.add(card(c));
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "REVEAL"); ev.put("player", owner.getName()); ev.put("zone", zone == null ? "" : zoneLabel(zone)); ev.put("cards", mine); ev.put("private", true);
            MatchWorker.emit(ev);
            return;
        }
        List<String> names = new ArrayList<>();
        List<Map<String, Object>> shown = new ArrayList<>();
        for (Card c : cards) { if (c.isFaceDown()) continue; names.add(c.getName()); shown.add(card(c)); }
        if (names.isEmpty()) return;
        if (MatchWorker.actionLog != null) MatchWorker.actionLog.revealed(owner.getName(), zone == null ? null : zoneLabel(zone), names);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "REVEAL"); ev.put("player", owner.getName()); ev.put("zone", zone == null ? "" : zoneLabel(zone)); ev.put("cards", shown);
        MatchWorker.emit(ev);
    }

    /** v0.35: Convoke / Improvise — the player picks which untapped permanents help pay. */
    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, forge.card.mana.ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        if (checkingAffordability) return super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction);
        List<Card> list = new ArrayList<>(untappedCards);
        if (list.isEmpty()) return new HashMap<>();
        int max = Math.min(list.size(), maxReduction != null ? maxReduction : manaCost.getCMC());
        if (max <= 0) return new HashMap<>();
        String what = creatures && artifacts ? "creatures or artifacts" : creatures ? "creatures" : "artifacts";
        String title = (creatures ? "Convoke" : "Improvise") + ": tap untapped " + what + " to help pay " + manaCost + " (optional)";
        List<Card> got = pick("CHOOSE", title, sa, list, 0, max);
        if (got == null) return super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction);
        if (got.isEmpty()) return new HashMap<>();
        return ComputerUtilMana.getConvokeOrImproviseFromList(manaCost, got, artifacts, creatures);
    }
}
