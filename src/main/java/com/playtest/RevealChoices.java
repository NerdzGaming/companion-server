package com.playtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** The observed batch sets display order, never the legal selection pool. */
final class RevealChoices {
    static <T> List<T> display(List<T> context, List<T> valid, Function<T, String> key) {
        LinkedHashMap<String, T> all = new LinkedHashMap<>();
        for (T item : context) all.putIfAbsent(key.apply(item), item);
        for (T item : valid) all.putIfAbsent(key.apply(item), item);
        return new ArrayList<>(all.values());
    }
    static boolean valid(Set<String> offered, Set<String> chosen, int min, int max) {
        return chosen.size() >= min && chosen.size() <= max && offered.containsAll(chosen);
    }
}