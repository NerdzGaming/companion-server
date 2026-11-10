package com.playtest;

public final class MulliganNoticeTest {
    public static void main(String[] args) {
        check(MulliganNotice.keptCount(7, 1, 0) == 6, "Constructed first mulligan keeps six");
        check(MulliganNotice.keptCount(7, 1, 1) == 7, "Commander first mulligan is free");
        check(MulliganNotice.keptCount(7, 2, 1) == 6, "Commander second mulligan keeps six");
        check(MulliganNotice.keptCount(7, 3, 0) == 4, "Repeated London mulligans reduce kept count");
        check(MulliganNotice.keptCount(7, 20, 0) == 0, "Count never negative");
        check(MulliganNotice.text("Spike", 7, 1, 1).equals("Spike takes a mulligan to 7 cards (1 free used)."), "Public notice reports free mulligan");
        System.out.println("MulliganNotice: 6 checks passed");
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}