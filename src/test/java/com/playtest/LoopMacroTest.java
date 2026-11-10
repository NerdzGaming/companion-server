package com.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class LoopMacroTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static int checks;
    static JsonNode json(String value) {
        try { return JSON.readTree(value); } catch (Exception e) { throw new AssertionError(e); }
    }
    static void check(boolean value, String rule) { checks++; if (!value) throw new AssertionError(rule); }
    static JsonNode prompt(int index, boolean unavailable) {
        return json("{\"type\":\"PROMPT\",\"kind\":\"PRIORITY\",\"promptId\":\"p" + index + "\",\"stackSize\":0,\"options\":[{\"index\":" + index + ",\"id\":42,\"label\":\"Tap Basalt Monolith\",\"unavailable\":" + unavailable + "}]}");
    }
    static LoopMacro recorded() {
        LoopMacro loop = new LoopMacro();
        loop.command("RECORD", 0, prompt(1, false), "1:MAIN1");
        loop.record(prompt(1, false), json("{\"type\":\"ACT\",\"promptId\":\"p1\",\"index\":1}"), "1:MAIN1");
        loop.command("FINISH", 0, prompt(1, false), "1:MAIN1");
        return loop;
    }
    public static void main(String[] args) {
        LoopMacro loop = recorded();
        loop.command("RUN", 2, prompt(7, false), "1:MAIN1");
        JsonNode answer = loop.replay(prompt(7, false), "1:MAIN1");
        check(answer != null && answer.path("index").asInt() == 7 && answer.path("promptId").asText().equals("p7"), "remap exact legal action and current prompt ID");
        check(loop.replay(prompt(9, false), "1:MAIN1") != null, "repeat next cycle");
        check(loop.replay(prompt(10, false), "1:MAIN1") == null && loop.status().get("completed").equals(2), "stop after exactly two repetitions");
        loop = recorded(); loop.command("RUN", 10, prompt(1, false), "1:MAIN1"); loop.opponentResponse();
        check(loop.replay(prompt(1, false), "1:MAIN1") == null, "opponent response halts replay");
        loop = recorded(); loop.command("RUN", 10, prompt(1, false), "1:MAIN1"); loop.requestStop();
        check(loop.replay(prompt(1, false), "1:MAIN1") == null, "manual stop halts replay");
        loop = recorded(); loop.command("RUN", 10, prompt(1, false), "1:MAIN1");
        check(loop.replay(prompt(1, true), "1:MAIN1") == null, "unavailable action never replays");
        loop = recorded(); loop.command("RUN", 10, prompt(1, false), "1:MAIN1");
        check(loop.replay(prompt(1, false), "1:MAIN2") == null, "phase changes halt replay");
        loop = recorded(); loop.command("RUN", 1001, prompt(1, false), "1:MAIN1");
        check(!loop.active(), "reject more than 1000 repetitions");
        loop.command("RUN", 0, prompt(1, false), "1:MAIN1");
        check(!loop.active(), "reject zero repetitions");
        loop = new LoopMacro(); loop.command("RECORD", 0, json("{\"kind\":\"PRIORITY\",\"stackSize\":1}"), "1:MAIN1");
        check(!loop.active(), "recording requires an empty stack");
        System.out.println("LoopMacroTest: " + checks + " checks passed");
    }
}