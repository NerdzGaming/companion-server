package com.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.ai.ComputerUtil;
import forge.game.player.DelayedReveal;
import forge.game.player.PlayerActionConfirmMode;
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
 * mulligan, attack, block, targets, modes, yes/no and card/player choices. Mana payment and
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
                            if (sa.isManaAbility()) continue;
                            if (!sa.isLandAbility() && !ComputerUtilMana.canPayManaCost(sa, me, 0, false)) continue;
                            playable.add(sa);
                        }
                    } catch (RuntimeException ignored) { }
                }
            }
            if (playable.isEmpty()) return null; // nothing to do: auto-pass
            List<Map<String, Object>> options = new ArrayList<>();
            for (int i = 0; i < playable.size(); i++) {
                SpellAbility sa = playable.get(i);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("index", i);
                o.put("cardId", sa.getHostCard().getId());
                o.put("cardName", sa.getHostCard().getName());
                o.put("zone", sa.getHostCard().getZone() == null ? null : sa.getHostCard().getZone().getZoneType().name());
                o.put("land", sa.isLandAbility());
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
            if (!sa.isLandAbility() && sa.usesTargeting()) {
                boolean ok;
                try { ok = sa.getTargetRestrictions().getAllCandidates(sa).size() >= Math.max(1, sa.getMinTargets()); } catch (RuntimeException e) { ok = false; }
                if (!ok) { // spell targets (counterspells) are not listed as candidates; let Forge confirm legality
                    try { getAi().canPlaySa(sa); } catch (RuntimeException ignored) { }
                    ok = sa.isTargetNumberValid();
                }
                if (!ok) {
                    MatchWorker.emit(Map.of("type", "NOTICE", "message", "No legal targets found for " + sa.getHostCard().getName() + "."));
                    continue;
                }
            }
            List<SpellAbility> out = new ArrayList<>();
            out.add(sa);
            return out;
        }
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        List<Map<String, Object>> hand = new ArrayList<>();
        for (Card c : getPlayer().getCardsIn(ZoneType.Hand)) hand.add(card(c));
        JsonNode a = ask("MULLIGAN", Map.of("hand", hand, "cardsToReturn", cardsToReturn));
        if (a == null) return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        return a.path("keep").asBoolean(true);
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
        JsonNode a = ask(kind, body);
        if (a == null) return null;
        Set<String> chosen = keys(a);
        List<T> out = new ArrayList<>();
        for (T o : options) if (chosen.contains(key(o)) && out.size() < max) out.add(o);
        return out.size() >= min ? out : null;
    }

    /** Lets the player choose targets for each targeted part of the spell or ability. */
    private void humanTargets(SpellAbility root) {
        for (SpellAbility cur = root; cur != null; cur = cur.getSubAbility()) {
            if (!cur.usesTargeting()) continue;
            List<GameEntity> cand;
            try { cand = new ArrayList<>(cur.getTargetRestrictions().getAllCandidates(cur)); } catch (RuntimeException e) { cand = new ArrayList<>(); }
            int min = cur.getMinTargets(), max = Math.max(cur.getMaxTargets(), 1);
            if (cand.isEmpty()) { cur.resetTargets(); super.chooseTargetsFor(cur); continue; }
            List<GameEntity> chosen;
            if (cand.size() <= min) chosen = cand; // no real choice
            else {
                String what = cur.getTargetRestrictions().getVTSelection();
                chosen = pick("TARGET", what == null || what.isBlank() ? "Choose target" + (max > 1 ? "s" : "") : what, cur, cand, min, Math.min(max, cand.size()));
            }
            cur.resetTargets();
            if (chosen == null) { super.chooseTargetsFor(cur); continue; }
            for (GameEntity e : chosen) cur.getTargets().add(e);
        }
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        if (sa.isLandAbility()) { if (sa.canPlay()) sa.resolve(); return true; }
        ComputerUtil.handlePlayingSpellAbility(getPlayer(), sa, this::humanTargets);
        return true;
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        if (!currentAbility.usesTargeting()) return super.chooseTargetsFor(currentAbility);
        humanTargets(currentAbility);
        return currentAbility.isTargetNumberValid() || super.chooseTargetsFor(currentAbility);
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
}
