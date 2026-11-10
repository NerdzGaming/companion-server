package com.playtest;

import java.util.List;

/** Effect-provided visibility and legal discard options remain separate. */
final class DiscardChoices {
    record Plan<T>(List<T> valid, List<T> visible, int min, int max) {}

    static <T> Plan<T> regular(List<T> valid, List<T> visible, int min, int max) {
        return new Plan<>(List.copyOf(valid), List.copyOf(visible),
            Math.min(Math.max(0, min), valid.size()), Math.min(Math.max(0, max), valid.size()));
    }

    static <T> Plan<T> unlessType(List<T> hand, List<T> matching, int number, boolean discardMatching) {
        if (discardMatching && matching.isEmpty()) throw new IllegalArgumentException("No matching discard available");
        List<T> valid = discardMatching ? matching : hand;
        int count = discardMatching ? 1 : Math.min(Math.max(0, number), hand.size());
        return new Plan<>(List.copyOf(valid), List.copyOf(hand), count, count);
    }
}