package com.playtest;

import java.util.List;
import java.util.Map;

public final class HumanChoicesTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(HumanChoices.validPartition(List.of(1, 2, 3), List.of(3, 1), List.of(2)));
        check(HumanChoices.validPartition(List.of(1), List.of(1), List.of()));
        check(HumanChoices.validPartition(List.of(1), List.of(), List.of(1)));
        check(HumanChoices.validPartition(List.of(), List.of(), List.of()));
        check(!HumanChoices.validPartition(List.of(1, 2), List.of(1), List.of(1)));
        check(!HumanChoices.validPartition(List.of(1, 2), List.of(1), List.of()));
        check(!HumanChoices.validPartition(List.of(1), List.of(9), List.of()));
        Map<Integer, List<String>> legal = Map.of(10, List.of("p2", "c20"), 11, List.of("p2"));
        check(HumanChoices.validAttack(legal, Map.of(10, "c20", 11, "p2")));
        check(!HumanChoices.validAttack(legal, Map.of(11, "c20")));
        check(!HumanChoices.validAttack(legal, Map.of(99, "p2")));
        check(HumanChoices.validAttack(legal, Map.of()));
        System.out.println("11 human choice validation checks passed");
    }
}