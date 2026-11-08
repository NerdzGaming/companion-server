package com.playtest;

import java.util.List;
import java.util.Set;

public final class RevealChoicesTest {
    public static void main(String[] args) {
        List<String> six = List.of("Elf", "Forest", "Island", "Ponder", "Sol Ring", "Bear");
        List<String> legal = List.of("Elf", "Bear");
        if (!RevealChoices.display(six, legal, c -> c).equals(six)) throw new AssertionError("Show exactly the top six, not six creatures");
        if (RevealChoices.valid(Set.copyOf(legal), Set.of("Forest"), 0, 1)) throw new AssertionError("Ineligible card accepted");
        if (!RevealChoices.valid(Set.copyOf(legal), Set.of("Bear"), 0, 1)) throw new AssertionError("Legal card rejected");
        if (!RevealChoices.valid(Set.copyOf(legal), Set.of(), 0, 1)) throw new AssertionError("Optional choice must allow none");
        if (RevealChoices.valid(Set.copyOf(legal), Set.of("Elf", "Bear"), 0, 1)) throw new AssertionError("Too many choices accepted");
        if (RevealChoices.display(six, legal, c -> c).contains("Opponent Island")) throw new AssertionError("Unrelated library added");
        System.out.println("6 reveal choice checks passed");
    }
}