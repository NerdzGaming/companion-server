package com.playtest;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Validate browser choices without moving any engine cards. */
public final class HumanChoices {
    private HumanChoices() { }
    static boolean validPartition(List<Integer> offered, List<Integer> top, List<Integer> graveyard) {
        var all = new java.util.ArrayList<>(top);
        all.addAll(graveyard);
        return all.size() == offered.size() && new HashSet<>(all).size() == all.size()
            && new HashSet<>(all).equals(new HashSet<>(offered));
    }
    static boolean validAttack(Map<Integer, List<String>> legal, Map<Integer, String> assignments) {
        return assignments.entrySet().stream().allMatch(e -> legal.getOrDefault(e.getKey(), List.of()).contains(e.getValue()));
    }
}