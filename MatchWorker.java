package com.playtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import forge.GuiDesktop;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardPlayOption;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One JVM per match. Runs a real Forge game and prints a JSON STATE line whenever the
 * visible state changes. Seat 1 ("player") is the website user via HumanBridgeController.
 */
public final class MatchWorker {
    static final ObjectMapper JSON = new ObjectMapper();
    static PrintStream out;
    static PublicActionLog actionLog;
    static final LoopMacro loop = new LoopMacro();
    static final List<Map<String, Object>> publicReveals = new ArrayList<>();
    static final String opponentDisplayName = MatchNames.aiName(java.util.concurrent.ThreadLocalRandom.current().nextInt(3));

    public static void main(String[] args) throws Exception {
        out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err); // Forge logs go to stderr; stdout carries only our JSON lines
        System.setProperty("java.awt.headless", "true");
        String assets = Path.of(System.getenv().getOrDefault("FORGE_ASSETS", ".")).toAbsolutePath() + "/";
        GuiBase.setInterface(new GuiDesktop() { @Override public String getAssetsDir() { return assets; } });
        FModel.initialize(null, null);
        HumanBridgeController.startInbox();
        // Forge's preference may default to Vancouver (draw one fewer); tabletop play uses the London mulligan.
        forge.StaticData.instance().setMulliganRule(forge.MulliganDefs.MulliganRule.London);

        Deck a = DeckSerializer.fromFile(Path.of(args[0]).toFile());
        Deck b = DeckSerializer.fromFile(Path.of(args[1]).toFile());
        if (a == null || b == null) { emit(Map.of("type", "ERROR", "message", "Deck could not be read")); System.exit(1); }
        GameType type = GameType.valueOf(args[2]);
        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));
        OpponentStrategy strategy = args.length > 3
            ? new OpponentStrategy(JSON.readTree(Path.of(args[3]).toFile())) : new OpponentStrategy(JSON.createObjectNode());
        startingLife = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        freeMulligans = args.length > 5 ? Integer.parseInt(args[5]) : (type == GameType.Commander ? 1 : 0);
        RegisteredPlayer pa = (type == GameType.Commander ? RegisteredPlayer.forCommander(a) : new RegisteredPlayer(a))
            .setPlayer(websiteSeat());
        RegisteredPlayer pb = (type == GameType.Commander ? RegisteredPlayer.forCommander(b) : new RegisteredPlayer(b))
            .setPlayer(opponentSeat(b, strategy));
        if (startingLife > 0) { pa.setStartingLife(startingLife); pb.setStartingLife(startingLife); }
        Match match = new Match(rules, List.of(pa, pb), "Nerdz rules match");
        Game game = match.createGame();
        game.setNoGUIUser();
        if (args.length > 3) {
            forge.ai.NerdzStrategy.install(strategy::bonus);
            forge.ai.NerdzStrategy.installSequencer(strategy::sequence);
        }
        actionLog = new PublicActionLog(game);
        game.subscribeToEvents(actionLog);
        forge.game.NerdzReveal.install(HumanBridgeController::publicReveal);
        forge.game.NerdzReveal.installChoiceHook(HumanBridgeController::setChoiceBatch);
        emit(Map.of("type", "STATUS", "message", "Game started"));

        // Snapshots are produced on the game thread by public events and before prompts.
        match.startGame(game);
        Thread.sleep(500);
        try { out.println(JSON.writeValueAsString(snapshot(game))); } catch (Exception ignored) { }
        Map<String, Object> over = new LinkedHashMap<>();
        over.put("type", "GAME_OVER");
        boolean draw = game.getOutcome() == null || game.getOutcome().isDraw();
        over.put("draw", draw);
        over.put("winner", draw ? null : game.getOutcome().getWinningLobbyPlayer().getName());
        emit(over);
        System.exit(0);
    }

    /** Preserve Forge's normal AI profile and decision engine, overriding only opponent mulligans. */
    /** v0.46: per-format starting life (0 = engine default) and free London mulligans. */
    static int startingLife = 0, freeMulligans = 0;

    static LobbyPlayerAi opponentSeat(Deck deck, OpponentStrategy strategy) {
        LobbyPlayerAi baseline = (LobbyPlayerAi) GamePlayerUtil.createAiPlayer("opponent", 1, "");
        java.util.Set<String> engines = new java.util.HashSet<>();
        strategy.metadata.forEach((name, metadata) -> { if (metadata.changer()) engines.add(name); });
        strategy.ownCombos.forEach(engines::addAll);
        LobbyPlayerAi seat = new LobbyPlayerAi("opponent", null) {
            @Override public Player createIngamePlayer(Game game, int id) {
                Player player = new Player(getName(), game, id);
                OpponentMulliganController controller = new OpponentMulliganController(game, player, this, deck, engines, !strategy.ownCombos.isEmpty(), strategy);
                controller.getAi().setUseSimulation(null);
                player.setFirstController(controller);
                return player;
            }
        };
        seat.setAiProfile(baseline.getAiProfile());
        return seat;
    }

    /** Lobby player whose in-game controller asks the website for decisions. */
    static LobbyPlayerAi websiteSeat() {
        return new LobbyPlayerAi("player", null) {
            @Override public Player createIngamePlayer(Game game, final int id) {
                Player p = new Player(getName(), game, id);
                p.setFirstController(new HumanBridgeController(game, p, this));
                return p;
            }
        };
    }

    static Map<String, Object> snapshot(Game game) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "STATE");
        var ph = game.getPhaseHandler();
        s.put("turn", ph.getTurn());
        s.put("phase", ph.getPhase() == null ? null : ph.getPhase().name());
        s.put("activePlayer", ph.getPlayerTurn() == null ? null : ph.getPlayerTurn().getName());
        List<Player> players = new ArrayList<>(game.getPlayers());
        Player viewer = players.stream().filter(p -> p.getController() instanceof HumanBridgeController).findFirst().orElse(null);
        for (Player p : players) {
            Map<String, Object> side = new LinkedHashMap<>();
            side.put("name", p == viewer ? "You" : opponentDisplayName);
            side.put("life", p.getLife());
            side.put("manaPool", manaPool(p));
            side.put("handCount", p.getCardsIn(ZoneType.Hand).size());
            // v0.44: public player counters (energy, poison, experience, rad, tickets).
            try {
                Map<String, Integer> pc = new LinkedHashMap<>();
                for (var e : p.getCounters().entrySet()) if (e.getCount() > 0) pc.put(e.getElement().getName(), e.getCount());
                // v0.46: public commander damage taken from each opposing commander (21 is lethal).
                try {
                    for (Player o : players) if (o != p) for (Card cmd : o.getCommanders()) {
                        int d = p.getCommanderDamage(cmd);
                        if (d > 0) pc.put("Cmdr dmg · " + cmd.getName(), d);
                    }
                } catch (RuntimeException ignored) { }
                side.put("counters", pc);
            } catch (RuntimeException ignored) { }
            side.put("libraryCount", p.getCardsIn(ZoneType.Library).size());
            Card top = visibleLibraryTop(p, viewer);
            if (top != null) side.put("libraryTop", Map.of("id", top.getId(), "name", top.getName(), "typeLine", top.getType().toString()));
            side.put("battlefield", cards(p, ZoneType.Battlefield, true));
            side.put("graveyard", cards(p, ZoneType.Graveyard, false));
            side.put("exile", exileCards(p, viewer));
            side.put("command", cards(p, ZoneType.Command, false));
            // Fog of war: only the website player's own hand is named.
            if (p.getName().equals("player")) side.put("hand", cards(p, ZoneType.Hand, false));
            s.put(p.getName(), side);
        }
        // v0.30: full stack entries (top first in engine order) so the table can show the whole card, its controller and targets.
        List<Map<String, Object>> stack = new ArrayList<>();
        game.getStack().forEach(si -> {
            var sa = si.getSpellAbility();
            Card host = sa.getHostCard();
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", si.getId());
            e.put("cardId", host == null ? -1 : host.getId());
            e.put("name", host == null ? "Ability" : visibleName(host));
            if (host != null && !host.isFaceDown()) {
                e.put("token", host.isToken());
                e.put("typeLine", host.getType().toString());
                if (host.isCreature()) { e.put("power", host.getNetPower()); e.put("toughness", host.getNetToughness()); }
            }
            e.put("controller", si.getActivatingPlayer() == null ? "" : si.getActivatingPlayer().getName());
            e.put("spell", sa.isSpell());
            e.put("copy", sa.isCopied() || (host != null && host.isCopiedSpell()));
            String text = si.getStackDescription();
            e.put("text", text == null ? "" : text);
            List<String> targets = new ArrayList<>();
            try { for (var t : sa.getAllTargetChoices()) for (var o : t) targets.add(o instanceof Card tc ? visibleName(tc) : String.valueOf(o)); } catch (RuntimeException ignored) { }
            e.put("targets", targets);
            stack.add(e);
        });
        s.put("stack", stack);
        try { s.put("stormCount", game.getStack().getSpellsCastThisTurn().size()); } catch (RuntimeException ignored) { }
        s.put("actionLog", actionLog == null ? List.of() : actionLog.snapshot());
        s.put("loop", loop.status());
        s.put("publicReveals", new ArrayList<>(publicReveals));
        return s;
    }

    static void publishReveal(Map<String, Object> event) {
        event.put("id", "reveal-" + java.util.UUID.randomUUID());
        event.put("private", false);
        event.put("audience", List.of("player", "opponent"));
        publicReveals.add(event);
        if (publicReveals.size() > 20) publicReveals.remove(0);
        emit(event);
    }

    static List<Map<String, Object>> cards(Player p, ZoneType zone, boolean withState) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Card c : new ArrayList<>(p.getCardsIn(zone))) {
            if (c.isImmutable()) continue; // engine-only effect cards (e.g. commander effect)
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", visibleName(c));
            if (!c.isFaceDown()) {
                m.put("token", c.isToken());
                m.put("typeLine", c.getType().toString());
            }
            if (withState) { m.put("tapped", c.isTapped());
                if (!c.isFaceDown() && c.isPlaneswalker()) m.put("loyalty", c.getCurrentLoyalty());
                if (c.isCreature()) { m.put("power", c.getNetPower()); m.put("toughness", c.getNetToughness()); if (c.hasFirstStrike() || c.hasDoubleStrike()) m.put("firstStrike", true); }
                if (c.getEntityAttachedTo() instanceof Card host) m.put("attachedTo", host.getId());
                // v0.35: lore (Urza's Saga), burden (The One Ring), +1/+1 and every other counter on the battlefield.
                try {
                    Map<String, Integer> counters = new LinkedHashMap<>();
                    for (var e : c.getCounters().entrySet()) if (e.getCount() > 0) counters.put(e.getElement().getName(), e.getCount());
                    if (!counters.isEmpty()) m.put("counters", counters);
                } catch (RuntimeException ignored) { } }
            list.add(m);
        }
        return list;
    }

    /**
     * Exile entries plus the viewer's play grants (impulse draws, Ragavan-style theft,
     * adventures on the stack's other side) and counters (suspend time counters), so the
     * table can shelve cards the player may still cast from exile.
     */
    static List<Map<String, Object>> exileCards(Player p, Player viewer) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Card c : new ArrayList<>(p.getCardsIn(ZoneType.Exile))) {
            if (c.isImmutable()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", visibleName(c));
            if (!c.isFaceDown()) {
                m.put("token", c.isToken());
                m.put("typeLine", c.getType().toString());
            }
            if (viewer != null && !c.isFaceDown()) {
                try {
                    List<CardPlayOption> grants = c.mayPlay(viewer);
                    if (!grants.isEmpty()) {
                        m.put("playable", true);
                        for (CardPlayOption o : grants) {
                            if (o.getPayManaCost() == CardPlayOption.PayManaCost.NO) { m.put("freeCast", true); break; }
                        }
                    }
                } catch (RuntimeException ignored) { }
            }
            try {
                Map<String, Integer> counters = new LinkedHashMap<>();
                for (var e : c.getCounters().entrySet()) if (e.getCount() > 0) counters.put(e.getElement().getName(), e.getCount());
                if (!counters.isEmpty()) m.put("counters", counters);
            } catch (RuntimeException ignored) { }
            list.add(m);
        }
        return list;
    }

    static void recordManaAction(forge.game.spellability.SpellAbility sa) {
        if (actionLog != null) actionLog.manaAction(visibleName(sa.getHostCard()));
    }

    static String visibleName(Card c) { return c.isFaceDown() ? "Face-down card" : c.getName(); }

    /** Owning a library does not reveal it. Inspect only its top and the actual website viewer. */
    static Card visibleLibraryTop(Player owner, Player viewer) {
        if (viewer == null) return null;
        var library = owner.getCardsIn(ZoneType.Library);
        if (library.isEmpty()) return null;
        Card top = library.get(0);
        return LibraryVisibility.allowed(top.getView().canBeShownTo(viewer.getView()),
            top.getView().canFaceDownBeShownTo(viewer.getView())) ? top : null;
    }

    /** Display only; leave individual mana objects, restrictions and provenance in Forge. */
    static List<Map<String, Object>> manaPool(Player p) {
        byte[] colors = { forge.card.MagicColor.WHITE, forge.card.MagicColor.BLUE,
            forge.card.MagicColor.BLACK, forge.card.MagicColor.RED, forge.card.MagicColor.GREEN, (byte) forge.card.mana.ManaAtom.COLORLESS };
        return ManaPoolProjection.project(colors, color -> p.getManaPool().getAmountOfColor((byte) color));
    }

    static void emit(Object o) { try { out.println(JSON.writeValueAsString(o)); } catch (Exception ignored) { } }
}
