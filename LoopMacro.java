package com.playtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-match recorded decisions, never state edits or skipped opponent priority. */
final class LoopMacro {
    private static final Set<String> KINDS = Set.of("PRIORITY", "TARGET", "CHOOSE", "MODE", "CONFIRM", "NUMBER", "MANA");
    private record Step(JsonNode context, JsonNode answer, List<JsonNode> choices, String label) {}
    private final List<Step> steps = new ArrayList<>();
    private String mode = "idle", reason = "", scope = "";
    private int cursor, completed, iterations;
    private volatile boolean stopRequested;
    void requestStop() { stopRequested = true; }
    boolean active() { return mode.equals("recording") || mode.equals("running"); }
    Map<String, Object> status() {
        return Map.of("mode", mode, "reason", reason, "steps", steps.stream().map(Step::label).toList(), "completed", completed, "iterations", iterations);
    }
    void stop(String why) { mode = steps.isEmpty() ? "idle" : "ready"; reason = why; stopRequested = false; }
    void opponentResponse() { if (active()) stop("Opponent responded; loop stopped."); }
    void command(String command, int count, JsonNode prompt, String currentScope) {
        if (command.equals("STOP")) { stop("Loop stopped."); return; }
        boolean boundary = prompt.path("kind").asText().equals("PRIORITY") && prompt.path("stackSize").asInt() == 0;
        if (!boundary) { reason = "Wait for your priority with an empty stack."; return; }
        if (command.equals("RECORD")) {
            steps.clear(); mode = "recording"; scope = currentScope; reason = ""; completed = 0; stopRequested = false;
        } else if (command.equals("FINISH") && mode.equals("recording")) {
            stop(steps.isEmpty() ? "No actions recorded." : "Loop recorded.");
        } else if (command.equals("RUN") && !active() && !steps.isEmpty()) {
            if (count < 1 || count > 1000) { reason = "Choose 1–1000 repetitions."; return; }
            mode = "running"; scope = currentScope; cursor = 0; completed = 0; iterations = count; reason = ""; stopRequested = false;
        }
    }
    private static JsonNode context(JsonNode prompt) {
        ObjectNode node = ((ObjectNode) prompt).deepCopy();
        node.remove(List.of("type", "promptId", "options", "pool", "canUndo", "stackSize"));
        return node;
    }
    private static JsonNode identity(JsonNode option) {
        ObjectNode node = ((ObjectNode) option).deepCopy();
        node.remove(List.of("index", "key"));
        return node;
    }
    private static String selector(JsonNode prompt, JsonNode answer) {
        return answer.has("keys") ? "keys" : answer.has("indices") ? "indices" : answer.path("index").isNumber() ? "index" : "";
    }
    private static List<JsonNode> choices(JsonNode prompt, JsonNode answer) {
        String field = selector(prompt, answer);
        List<JsonNode> result = new ArrayList<>();
        if (field.isEmpty()) return result;
        List<JsonNode> values = new ArrayList<>();
        if (field.equals("index")) values.add(answer.get(field)); else answer.path(field).forEach(values::add);
        for (JsonNode value : values) {
            JsonNode found = null;
            for (JsonNode option : prompt.path("options")) if (value.equals(option.path(field.equals("keys") ? "key" : "index")) && !option.path("unavailable").asBoolean()) { found = identity(option); break; }
            if (found == null) return null;
            result.add(found);
        }
        return result;
    }
    void record(JsonNode prompt, JsonNode answer, String currentScope) {
        if (!mode.equals("recording")) return;
        if (!scope.equals(currentScope) || !KINDS.contains(prompt.path("kind").asText()) || answer.path("undo").asBoolean()) { stop("This decision cannot be safely repeated."); return; }
        if (steps.size() >= 128) { stop("Recording reached 128 decisions."); return; }
        List<JsonNode> picks = choices(prompt, answer);
        if (picks == null) { stop("A recorded choice was not legal."); return; }
        ObjectNode payload = ((ObjectNode) answer).deepCopy(); payload.remove(List.of("type", "promptId"));
        String label = picks.isEmpty() ? prompt.path("kind").asText() + (answer.has("index") && answer.path("index").isNull() ? " · Pass priority" : " · " + prompt.path("source").asText()) : picks.stream().map(p -> p.path("label").asText(p.path("cardName").asText())).reduce((a, b) -> a + ", " + b).orElse("");
        steps.add(new Step(context(prompt), payload, picks, label));
    }
    JsonNode replay(JsonNode prompt, String currentScope) {
        if (stopRequested) stop("Loop stopped.");
        if (!mode.equals("running")) return null;
        if (!scope.equals(currentScope)) { stop("Turn or phase changed."); return null; }
        if (cursor == steps.size()) {
            completed++; cursor = 0;
            if (completed >= iterations) { stop("Loop complete."); return null; }
        }
        Step step = steps.get(cursor);
        if (!context(prompt).equals(step.context())) { stop("Decision changed; choose manually."); return null; }
        ObjectNode answer = ((ObjectNode) step.answer()).deepCopy();
        String field = selector(prompt, answer);
        if (!field.isEmpty()) {
            var values = answer.arrayNode();
            for (JsonNode choice : step.choices()) {
                List<JsonNode> found = new ArrayList<>();
                for (JsonNode option : prompt.path("options")) if (!option.path("unavailable").asBoolean() && identity(option).equals(choice)) found.add(option);
                if (found.size() != 1) { stop("A recorded choice is missing or ambiguous."); return null; }
                values.add(found.get(0).get(field.equals("keys") ? "key" : "index"));
            }
            if (field.equals("index")) answer.set(field, values.get(0)); else answer.set(field, values);
        }
        answer.put("type", "ACT"); answer.put("promptId", prompt.path("promptId").asText()); cursor++;
        return answer;
    }
}