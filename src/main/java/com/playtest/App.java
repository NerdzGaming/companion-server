package com.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;
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
        final String id; final String socketToken; volatile long tokenExpires; final String playerDeck; final String aiDeck; final String format;
        final Set<WsContext> sockets = ConcurrentHashMap.newKeySet();
        volatile Process worker; volatile long emptySince; volatile String lastState; volatile String lastPrompt; final long created = System.currentTimeMillis();
        MatchSession(String id, String p, String a, String f) { this.id = id; socketToken = TransportAccess.newToken(); tokenExpires = System.currentTimeMillis() + 5 * 60_000; playerDeck = p; aiDeck = a; format = f; }
    }
    static final Map<String, MatchSession> MATCHES = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        String apiKey = System.getenv("FORGE_TRANSPORT_SECRET");
        TransportAccess.requireKey(apiKey);
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Javalin app = Javalin.create(cfg -> cfg.bundledPlugins.enableCors(c -> c.addRule(r -> r.anyHost())));

        app.get("/", ctx -> ctx.result("Forge Companion Server is active"));

        app.post("/match/create", ctx -> {
            if (!TransportAccess.authorized(ctx.header("Authorization"), apiKey)) throw new UnauthorizedResponse();
            JsonNode body = JSON.readTree(ctx.body());
            String p = body.path("playerDeckDck").asText(""), a = body.path("aiDeckDck").asText("");
            String format = body.path("format").asText("Commander");
            if (p.isBlank() || a.isBlank() || p.length() > MAX_DECK || a.length() > MAX_DECK
                    || !List.of("Commander", "Constructed").contains(format)) {
                ctx.status(400).json(Map.of("error", "Send playerDeckDck and aiDeckDck (.dck text)")); return;
            }
            String id = UUID.randomUUID().toString();
            MatchSession m = new MatchSession(id, p, a, format);
            MATCHES.put(id, m);
            ctx.header("Cache-Control", "no-store");
            ctx.json(Map.of("matchId", id, "socketToken", m.socketToken, "status", "created"));
        });

        app.wsBeforeUpgrade("/match/{id}", ctx -> {
            MatchSession m = MATCHES.get(ctx.pathParam("id"));
            if (m == null || !TransportAccess.validTicket(ctx.queryParam("ticket"), m.socketToken, m.tokenExpires, System.currentTimeMillis()))
                throw new UnauthorizedResponse();
        });

        app.ws("/match/{id}", ws -> {
            ws.onConnect(ctx -> {
                MatchSession m = MATCHES.get(ctx.pathParam("id"));
                if (m == null || !TransportAccess.validTicket(ctx.queryParam("ticket"), m.socketToken, m.tokenExpires, System.currentTimeMillis())) { ctx.send("{\"type\":\"ERROR\",\"message\":\"Unknown match\"}"); ctx.closeSession(); return; }
                ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(15));
                synchronized (m) {
                    if (!m.sockets.isEmpty()) { ctx.closeSession(); return; }
                    m.sockets.add(ctx);
                }
                if (m.lastState != null) ctx.send(m.lastState);
                if (m.lastPrompt != null) ctx.send(m.lastPrompt); // resend pending decision after reconnect
                synchronized (m) { if (m.worker == null) start(m); }
            });
            ws.onMessage(ctx -> {
                MatchSession authenticated = MATCHES.get(ctx.pathParam("id"));
                if (authenticated == null || !authenticated.sockets.contains(ctx)) { ctx.closeSession(); return; }
                JsonNode msg = JSON.readTree(ctx.message());
                String type = msg.path("type").asText("");
                if (type.equals("PING")) { ctx.send("{\"type\":\"PONG\"}"); return; }
                MatchSession m = MATCHES.get(ctx.pathParam("id"));
                if (type.equals("LEAVE")) { if (m != null) end(m); ctx.closeSession(); return; }
                if (!(type.equals("ACT") || type.equals("LOADING_HOLD")) || m == null || m.worker == null || !m.worker.isAlive()) {
                    ctx.send("{\"type\":\"ERROR\",\"message\":\"Unknown action or match not running\"}"); return;
                }
                // Forward the decision to the Forge worker (one JSON line on its stdin).
                synchronized (m) {
                    var in = m.worker.getOutputStream();
                    in.write((JSON.writeValueAsString(msg) + "\n").getBytes(StandardCharsets.UTF_8));
                    in.flush();
                }
            });
            ws.onClose(ctx -> {
                MatchSession m = MATCHES.get(ctx.pathParam("id"));
                if (m != null) m.sockets.remove(ctx);
                if (m != null && m.sockets.isEmpty()) { m.emptySince = System.currentTimeMillis(); m.tokenExpires = m.emptySince + 120_000; }
            });
        });

        // Clean up abandoned matches every minute (15 minutes with nobody watching).
        Thread reaper = new Thread(() -> {
            while (true) {
                try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                MATCHES.values().removeIf(m -> {
                    // Two minutes covers a reconnect; unconnected sessions expire after 15.
                    boolean dead = m.sockets.isEmpty() && (m.emptySince > 0 ? now - m.emptySince > 120_000 : now - m.created > 15 * 60_000);
                    if (dead && m.worker != null) m.worker.destroyForcibly();
                    return dead;
                });
            }
        });
        reaper.setDaemon(true); reaper.start();
        app.start(port);
    }

    static void end(MatchSession m) {
        MATCHES.remove(m.id);
        Process w = m.worker;
        if (w != null && w.isAlive()) { w.descendants().forEach(ProcessHandle::destroyForcibly); w.destroyForcibly(); }
    }

    static void start(MatchSession m) {
        // Reclaim slots held by matches nobody is connected to before refusing a new one.
        MATCHES.values().stream().filter(x -> x != m && x.sockets.isEmpty() && x.worker != null).toList().forEach(App::end);
        long running = MATCHES.values().stream().filter(x -> x.worker != null && x.worker.isAlive()).count();
        if (running >= MAX_MATCHES) { broadcast(m, "{\"type\":\"ERROR\",\"message\":\"Server busy, try again shortly\"}"); return; }
        try {
            Path job = Files.createTempDirectory("match-");
            Path a = job.resolve("player.dck"), b = job.resolve("ai.dck");
            Files.writeString(a, m.playerDeck); Files.writeString(b, m.aiDeck);
            String workerCp = System.getenv().getOrDefault("WORKER_CP", "/app/server.jar:/app/forge/forge-headless.jar:/app/lib/*");
            List<String> cmd = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + System.getenv().getOrDefault("WORKER_HEAP", "1200m"), "-Djava.awt.headless=true", "-Duser.home=" + job,
                "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
                "-cp", workerCp, MatchWorker.class.getName(), a.toString(), b.toString(), m.format);
            m.worker = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            broadcast(m, "{\"type\":\"STATUS\",\"message\":\"Forge is loading cards (about 30-60s)\"}");
            Thread relay = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(m.worker.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (!line.startsWith("{")) continue; // skip Forge's own log noise
                        if (line.contains("\"type\":\"STATE\"")) m.lastState = line;
                        if (line.contains("\"type\":\"PROMPT\"")) m.lastPrompt = line;
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
