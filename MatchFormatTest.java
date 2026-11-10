package com.playtest;

public final class MatchFormatTest {
    static int n;
    static void eq(String want, String got) {
        n++; if (!want.equals(got)) throw new AssertionError("Expected " + want + ", got " + got);
    }
    public static void main(String[] args) {
        eq("Constructed", MatchFormat.resolve(null, "[Main]\n60 Island", "[Main]\n60 Mountain"));
        eq("Commander", MatchFormat.resolve(null, "[Commander]\n1 Atraxa", "[Main]\n60 Island"));
        eq("Commander", MatchFormat.resolve("", "[Main]\n60 Island", "  [commander]  \n1 Krenko"));
        eq("Constructed", MatchFormat.resolve("Constructed", "[Commander]\n1 Atraxa", "[Commander]\n1 Krenko"));
        eq("Commander", MatchFormat.resolve("Commander", "[Main]\n99 Island", "[Main]\n99 Mountain"));
        eq("Constructed", MatchFormat.resolve(null, "[Main]\n1 Commander's Sphere", "[Main]\n60 Mountain"));
        System.out.println(n + " match format checks passed");
    }
}