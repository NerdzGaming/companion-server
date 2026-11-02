package com.playtest;

public final class LibraryVisibilityTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(!LibraryVisibility.allowed(false, false));
        check(!LibraryVisibility.allowed(false, true));
        check(!LibraryVisibility.allowed(true, false));
        check(LibraryVisibility.allowed(true, true));
        System.out.println("4 library visibility checks passed");
    }
}