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
    /** Library→hand moves are classified once Forge marks draws (it sets the flag just after the move). */
    private final List<Object[]> pendingLibraryHand = new ArrayList<>();
    private void flushPending() {
        if (pendingLibraryHand.isEmpty()) return;
        List<Object[]> items = new ArrayList<>(pendingLibraryHand); pendingLibraryHand.clear();
        for (Object[] it : items) {
            forge.game.card.Card c = game.findById((Integer) it[0]);
            boolean draw = c == null || c.getDrawnThisTurn() || game.getPhaseHandler().getPhase() == null;
            // Never name the card: tutored cards in hand stay hidden.
            put(draw ? "draw" : "move", it[1] + (draw ? " draws a card." : " puts a card from their library into their hand."));
        }
    }
    private synchronized void add(String category, String text) {
        flushPending();
        put(category, text);
        MatchWorker.emit(MatchWorker.snapshot(game));
    }
    private void put(String category, String text) {
        var ph = game.getPhaseHandler();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", "action-" + (++sequence)); e.put("category", category); e.put("text", text);
        e.put("turn", ph.getTurn()); e.put("phase", ph.getPhase() == null ? "" : ph.getPhase().name());
        entries.add(e); if (entries.size() > 300) entries.remove(0);
    }
    /** Public picks from revealed cards (e.g. Atraxa), logged one line per card. */
    void revealedPick(String player, String card, String group) { add("choice", player + " picks " + card + " (" + group + ") from the revealed cards."); }
    /** v0.35: cards an opponent reveals to the table (Atraxa, Dark Confidant, reveal-from-hand). */
    void revealed(String owner, String zone, List<String> cards) {
        if (cards.isEmpty()) return;
        add("reveal", owner + " reveals " + String.join(", ", cards) + (zone == null ? "" : " from their " + zone) + ".");
    }
    void manaAction(String source) { add("mana", "Activates " + source + " for mana."); }
    void mulligan(String text) { add("mulligan", text); }
    synchronized List<Map<String, Object>> snapshot() { flushPending(); return new ArrayList<>(entries); }
    @Subscribe public void turn(GameEventTurnBegan e) { add("state", "Turn " + e.turnNumber() + " begins — " + e.turnOwner().getName() + "."); }
    @Subscribe public void phase(GameEventTurnPhase e) { add("state", e.playerTurn().getName() + " enters " + e.phase().name() + "."); }
    @Subscribe public void life(GameEventPlayerLivesChanged e) { add("state", e.player().getName() + " life " + e.oldLives() + " → " + e.newLives() + "."); }
    // Counter costs and damage must refresh loyalty even before the next priority prompt.
    @Subscribe public void counters(GameEventCardCounters e) { MatchWorker.emit(MatchWorker.snapshot(game)); }
    @Subscribe public void playerCounters(GameEventPlayerCounters e) {
        if (e.type() == null || e.receiver() == null) { MatchWorker.emit(MatchWorker.snapshot(game)); return; }
        int delta = e.amount() - e.oldValue();
        if (delta == 0) { MatchWorker.emit(MatchWorker.snapshot(game)); return; }
        String kind = e.type().getName();
        add("state", e.receiver().getName() + (delta > 0 ? " gets " + delta : " loses " + (-delta)) + " " + kind + " counter" + (Math.abs(delta) == 1 ? "" : "s") + " (now " + e.amount() + ").");
    }
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
    @Subscribe public void shuffle(GameEventShuffle e) { add("state", e.player().getName() + " shuffles their library."); }
    @Subscribe public void damage(GameEventCardDamaged e) { add("combat", name(e.card()) + " takes " + e.amount() + " damage."); }
    @Subscribe public void move(GameEventCardChangeZone e) {
        ZoneType from = e.from() == null ? null : e.from().zoneType();
        ZoneType to = e.to() == null ? null : e.to().zoneType();
        // Include only the public drawing player's name, never the hidden card identity.
        // The website bundles consecutive draws by player, engine turn and phase.
        if (from == ZoneType.Library && to == ZoneType.Hand) {
            synchronized (this) { flushPending(); pendingLibraryHand.add(new Object[] { e.card().getId(), e.to().player().getName() }); }
            MatchWorker.emit(MatchWorker.snapshot(game));
            return;
        }
        String owner = e.to() != null && e.to().player() != null ? e.to().player().getName() : e.from() != null && e.from().player() != null ? e.from().player().getName() : "A player";
        add("move", ZoneMovement.describe(owner, name(e.card()), from == null ? null : from.name(), to == null ? null : to.name()));
    }
}
