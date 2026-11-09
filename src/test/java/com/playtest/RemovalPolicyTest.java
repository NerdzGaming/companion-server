package com.playtest;

public final class RemovalPolicyTest {
    static int n;
    static void eq(boolean want, boolean got, String label) {
        n++; if (want != got) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        eq(false, RemovalPolicy.applies(true, 1, "DealDamage", null, null, true, false), "burn to player bypasses permanent patience, including lethal");
        eq(true, RemovalPolicy.applies(true, 1, "DealDamage", null, null, false, false), "planeswalker burn retains permanent assessment");
        eq(false, RemovalPolicy.applies(true, 1, "ChangeZone", "Graveyard", "Hand", false, true), "graveyard recursion never removal");
        eq(false, RemovalPolicy.applies(true, 1, "ChangeZone", "Library", "Hand", false, false), "tutor never removal");
        eq(true, RemovalPolicy.applies(true, 1, "ChangeZone", "Battlefield", "Hand", false, false), "battlefield bounce is removal");
        eq(true, RemovalPolicy.applies(true, 1, "ChangeZone", "Battlefield", "Exile", false, false), "battlefield exile is removal");
        eq(false, RemovalPolicy.applies(true, 2, "Destroy", null, null, false, false), "multi-target destruction left to engine");
        eq(false, RemovalPolicy.applies(false, 1, "Destroy", null, null, false, false), "activated abilities left to engine");
        eq(false, RemovalPolicy.applies(true, 0, "Destroy", null, null, false, false), "untargeted destruction left to engine");
        System.out.println(n + " removal policy checks passed");
    }
}