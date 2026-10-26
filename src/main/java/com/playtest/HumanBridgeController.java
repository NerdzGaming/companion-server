package com.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
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
 * mulligan, attack and block decisions. Everything else (targets, payment, modal choices)
 * still falls back to Forge AI so the game never stalls. No answer within the timeout = AI decides.
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
                try { getAi().canPlaySa(sa); } catch (RuntimeException ignored) { }
                if (!sa.isTargetNumberValid()) {
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
}
