package com.playtest;

public final class MatchNamesTest {
    public static void main(String[] args) {
        String[] names = { "Spike", "Timmy", "Johnny" };
        for (int i = 0; i < names.length; i++) {
            if (!names[i].equals(MatchNames.aiName(i))) throw new AssertionError("Unexpected AI name");
        }
        for (int i = -20; i <= 20; i++) {
            if (!java.util.List.of(names).contains(MatchNames.aiName(i))) throw new AssertionError("Invalid AI name");
        }
        System.out.println("Match names: 44 checks passed");
    }
}