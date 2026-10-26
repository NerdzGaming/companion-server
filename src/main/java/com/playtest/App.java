package com.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Companion server: keeps the existing /match/create + WebSocket contract the website uses,
 * and runs each match in its own Forge worker JVM (Forge's models are process-global).
 * The worker prints one JSON line per state change; this server relays those lines to the socket.
 */
public final class App {
    static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_DECK = 65536;
    static final int MAX_MATCHES = Integer.parseInt(System.getenv().getOrDefault("MAX_MATCHES", "1"));

    static final class MatchSession {
        final String id; final String playerDeck; final String aiDeck; final String format;
        final Set<WsContext> sockets = ConcurrentHashMap.newKeySet();
        volatile Process worker; volatile String lastState; final long created = System.currentTimeMillis();
        MatchSession(String id, String p, String a, String f) { this.id = id; playerDeck = p; aiDeck = a; format = f; }
    }
    static final Map<String, MatchSession> MATCHES = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Javalin app = Javalin.create(cfg -> cfg.bundledPlugins.enableCors(c -> c.addRule(r -> r.anyHost())));

        app.get("/", ctx -> ctx.result("Forge Companion Server is active"));

        app.post("/match/create", ctx -> {
            JsonNode body = JSON.readTree(ctx.body());
            String p = body.path("playerDeckDck").asText(""), a = body.path("aiDeckDck").asText("");
            String format = body.path("format").asText("Commander");
            if (p.isBlank() || a.isBlank() || p.length() > MAX_DECK || a.length() > MAX_DECK
                    || !List.of("Commander", "Constructed").contains(format)) {
                ctx.status(400).json(Map.of("error", "Send playerDeckDck and aiDeckDck (.dck text)")); return;
            }
            String id = UUID.randomUUID().toString();
            MATCHES.put(id, new MatchSession(id, p, a, format));
            ctx.json(Map.of("matchId", id, "status", "created"));
        });

        app.ws("/match/{id}", ws -> {
            ws.onConnect(ctx -> {
                MatchSession m = MATCHES.get(ctx.pathParam("id"));
                if (m == null) { ctx.send("{\"type\":\"ERROR\",\"message\":\"Unknown match\"}"); ctx.closeSession(); return; }
                ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(15));
                m.sockets.add(ctx);
                if (m.lastState != null) ctx.send(m.lastState);
                synchronized (m) { if (m.worker == null) start(m); }
            });
            ws.onMessage(ctx -> {
                JsonNode msg = JSON.readTree(ctx.message());
                String type = msg.path("type").asText("");
                if (type.equals("PING")) { ctx.send("{\"type\":\"PONG\"}"); return; }
                // Interactive human decisions are the next milestone; acknowledge so the UI isn't left hanging.
                ctx.send(JSON.writeValueAsString(Map.of("type", "ACK", "action", type,
                    "note", "Human controls are not wired yet; Forge AI is playing your seat.")));
            });
            ws.onClose(ctx -> {
                MatchSession m = MATCHES.get(ctx.pathParam("id"));
                if (m != null) m.sockets.remove(ctx);
            });
        });

        // Clean up abandoned matches every minute (15 minutes with nobody watching).
        Thread reaper = new Thread(() -> {
            while (true) {
                try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
                long cutoff = System.currentTimeMillis() - 15 * 60_000;
                MATCHES.values().removeIf(m -> {
                    boolean dead = m.sockets.isEmpty() && m.created < cutoff;
                    if (dead && m.worker != null) m.worker.destroyForcibly();
                    return dead;
                });
            }
        });
        reaper.setDaemon(true); reaper.start();
        app.start(port);
    }

    static void start(MatchSession m) {
        long running = MATCHES.values().stream().filter(x -> x.worker != null && x.worker.isAlive()).count();
        if (running >= MAX_MATCHES) { broadcast(m, "{\"type\":\"ERROR\",\"message\":\"Server busy, try again shortly\"}"); return; }
        try {
            Path job = Files.createTempDirectory("match-");
            Path a = job.resolve("player.dck"), b = job.resolve("ai.dck");
            Files.writeString(a, m.playerDeck); Files.writeString(b, m.aiDeck);
            List<String> cmd = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + System.getenv().getOrDefault("WORKER_HEAP", "1200m"), "-Djava.awt.headless=true", "-Duser.home=" + job,
                "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"), MatchWorker.class.getName(), a.toString(), b.toString(), m.format);
            m.worker = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            broadcast(m, "{\"type\":\"STATUS\",\"message\":\"Forge is loading cards (about 30-60s)\"}");
            Thread relay = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(m.worker.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!line.startsWith("{")) continue; // skip Forge's own log noise
                        if (line.contains("\"type\":\"STATE\"")) m.lastState = line;
                        broadcast(m, line);
                    }
                } catch (Exception ignored) { }
                broadcast(m, "{\"type\":\"STATUS\",\"message\":\"Match worker stopped\"}");
            });
            relay.setDaemon(true); relay.start();
        } catch (Exception e) {
            broadcast(m, "{\"type\":\"ERROR\",\"message\":\"Could not start Forge\"}");
        }
    }

    static void broadcast(MatchSession m, String json) {
        for (WsContext s : m.sockets) { try { s.send(json); } catch (Exception ignored) { } }
    }
}
