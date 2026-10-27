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

    public static void main(String[] args) throws Exception {
        out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err); // Forge logs go to stderr; stdout carries only our JSON lines
        System.setProperty("java.awt.headless", "true");
        String assets = Path.of(System.getenv().getOrDefault("FORGE_ASSETS", ".")).toAbsolutePath() + "/";
        GuiBase.setInterface(new GuiDesktop() { @Override public String getAssetsDir() { return assets; } });
        FModel.initialize(null, null);
        HumanBridgeController.startInbox();

        Deck a = DeckSerializer.fromFile(Path.of(args[0]).toFile());
        Deck b = DeckSerializer.fromFile(Path.of(args[1]).toFile());
        if (a == null || b == null) { emit(Map.of("type", "ERROR", "message", "Deck could not be read")); System.exit(1); }
        GameType type = GameType.valueOf(args[2]);
        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));
        RegisteredPlayer pa = (type == GameType.Commander ? RegisteredPlayer.forCommander(a) : new RegisteredPlayer(a))
            .setPlayer(websiteSeat());
        RegisteredPlayer pb = (type == GameType.Commander ? RegisteredPlayer.forCommander(b) : new RegisteredPlayer(b))
            .setPlayer(GamePlayerUtil.createAiPlayer("opponent", 1, ""));
        Match match = new Match(rules, List.of(pa, pb), "Nerdz rules match");
        Game game = match.createGame();
        game.setNoGUIUser();
        emit(Map.of("type", "STATUS", "message", "Game started"));

        // Snapshot the board every 400ms from a side thread; only send when something changed.
        Thread watcher = new Thread(() -> {
            String last = "";
            while (!game.isGameOver()) {
                try {
                    String snap = JSON.writeValueAsString(snapshot(game));
                    if (!snap.equals(last)) { out.println(snap); last = snap; }
                    Thread.sleep(400);
                } catch (InterruptedException e) { return; } catch (Exception ignored) { /* board changed mid-read */ }
            }
        });
        watcher.setDaemon(true); watcher.start();

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
        for (Player p : players) {
            Map<String, Object> side = new LinkedHashMap<>();
            side.put("life", p.getLife());
            side.put("handCount", p.getCardsIn(ZoneType.Hand).size());
            side.put("libraryCount", p.getCardsIn(ZoneType.Library).size());
            side.put("battlefield", cards(p, ZoneType.Battlefield, true));
            side.put("graveyard", cards(p, ZoneType.Graveyard, false));
            side.put("exile", cards(p, ZoneType.Exile, false));
            side.put("command", cards(p, ZoneType.Command, false));
            // Fog of war: only the website player's own hand is named.
            if (p.getName().equals("player")) side.put("hand", cards(p, ZoneType.Hand, false));
            s.put(p.getName(), side);
        }
        List<String> stack = new ArrayList<>();
        game.getStack().forEach(si -> stack.add(si.getStackDescription()));
        s.put("stack", stack);
        return s;
    }

    static List<Map<String, Object>> cards(Player p, ZoneType zone, boolean withState) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Card c : new ArrayList<>(p.getCardsIn(zone))) {
            if (c.isImmutable()) continue; // engine-only effect cards (e.g. commander effect)
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            if (withState) { m.put("tapped", c.isTapped()); if (c.isCreature()) { m.put("power", c.getNetPower()); m.put("toughness", c.getNetToughness()); } }
            list.add(m);
        }
        return list;
    }

    static void emit(Object o) { try { out.println(JSON.writeValueAsString(o)); } catch (Exception ignored) { } }
}
