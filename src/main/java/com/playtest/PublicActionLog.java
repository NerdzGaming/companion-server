package com.playtest;

import com.google.common.eventbus.Subscribe;
import forge.game.Game;
import forge.game.card.CardView;
import forge.game.event.*;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Audited structured events only. Never export GameLog strings, prompts or hidden zones. */
public final class PublicActionLog {
    private final Game game;
    private final List<Map<String, Object>> entries = new ArrayList<>();
    private long sequence;
    PublicActionLog(Game game) { this.game = game; }
    static String name(CardView c) { return c == null || c.isFaceDown() ? "Face-down card" : c.getName(); }
    private static boolean visible(ZoneType z) { return z == ZoneType.Battlefield || z == ZoneType.Graveyard || z == ZoneType.Exile || z == ZoneType.Command || z == ZoneType.Stack; }
    private synchronized void add(String category, String text) {
        var ph = game.getPhaseHandler();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", "action-" + (++sequence)); e.put("category", category); e.put("text", text);
        e.put("turn", ph.getTurn()); e.put("phase", ph.getPhase() == null ? "" : ph.getPhase().name());
        entries.add(e); if (entries.size() > 200) entries.remove(0);
        MatchWorker.emit(MatchWorker.snapshot(game));
    }
    void manaAction(String source) { add("mana", "Activates " + source + " for mana."); }
    synchronized List<Map<String, Object>> snapshot() { return new ArrayList<>(entries); }
    @Subscribe public void turn(GameEventTurnBegan e) { add("state", "Turn " + e.turnNumber() + " begins — " + e.turnOwner().getName() + "."); }
    @Subscribe public void phase(GameEventTurnPhase e) { add("state", e.playerTurn().getName() + " enters " + e.phase().name() + "."); }
    @Subscribe public void life(GameEventPlayerLivesChanged e) { add("state", e.player().getName() + " life " + e.oldLives() + " → " + e.newLives() + "."); }
    // Counter costs and damage must refresh loyalty even before the next priority prompt.
    @Subscribe public void counters(GameEventCardCounters e) { MatchWorker.emit(MatchWorker.snapshot(game)); }
    @Subscribe public void land(GameEventLandPlayed e) { add("state", e.player().getName() + " plays " + name(e.land()) + "."); }
    @Subscribe public void cast(GameEventSpellAbilityCast e) {
        // Do not include targetDescription or arbitrary stack text: either may contain hidden identities.
        add("spell", e.si().getActivatingPlayer().getName() + (e.sa().isSpell() ? " casts " : e.si().isTrigger() ? " triggers " : " activates ") + name(e.sa().getHostCard()) + ".");
    }
    @Subscribe public void resolve(GameEventSpellResolved e) { add("spell", name(e.spell().getHostCard()) + (e.hasFizzled() ? " fizzles." : " resolves.")); }
    @Subscribe public void tap(GameEventCardTapped e) { if (e.card().getZone() == ZoneType.Battlefield) add("state", (e.tapped() ? "Taps " : "Untaps ") + name(e.card()) + "."); }
    // ManaPool events also fire during AI affordability probes; never label those as real actions.
    @Subscribe public void attack(GameEventAttackersDeclared e) {
        for (var item : e.attackersMap().entries()) add("combat", e.player().getName() + " attacks with " + name(item.getValue()) + ".");
    }
    @Subscribe public void block(GameEventBlockersDeclared e) {
        for (var group : e.blockers().values()) for (var item : group.entries()) add("combat", name(item.getValue()) + " blocks " + name(item.getKey()) + ".");
    }
    @Subscribe public void damage(GameEventCardDamaged e) { add("combat", name(e.card()) + " takes " + e.amount() + " damage."); }
    @Subscribe public void move(GameEventCardChangeZone e) {
        ZoneType from = e.from() == null ? null : e.from().zoneType();
        ZoneType to = e.to() == null ? null : e.to().zoneType();
        // Include only the public drawing player's name, never the hidden card identity.
        // The website bundles consecutive draws by player, engine turn and phase.
        if (from == ZoneType.Library && to == ZoneType.Hand) { add("draw", e.to().player().getName() + " draws a card."); return; }
        if (!visible(from) && !visible(to)) return;
        // Mask a card entering a hidden zone even if its previous identity was public.
        String card = visible(to) ? name(e.card()) : "A card";
        add("state", card + " → " + (to == null ? "outside the game" : to.name()) + ".");
    }
}
