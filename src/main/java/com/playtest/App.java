package com.playtest;

import io.javalin.Javalin;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class App {
    public static void main(String[] args) {
        int port = Integer.parseInt(Optional.ofNullable(System.getenv("PORT")).orElse("8080"));

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        }).start(port);

        app.get("/", ctx -> ctx.result("Forge Companion Server is active"));

        app.post("/match/create", ctx -> {
            String matchId = UUID.randomUUID().toString();
            ctx.json(Map.of("matchId", matchId, "status", "created"));
        });

        app.ws("/match/{id}", ws -> {
            ws.onConnect(ctx -> System.out.println("Client connected: " + ctx.pathParam("id")));
            ws.onMessage(ctx -> ctx.send("{\"type\":\"PONG\"}"));
            ws.onClose(ctx -> System.out.println("Client disconnected: " + ctx.pathParam("id")));
        });

        System.out.println("Server running on port: " + port);
    }
}
