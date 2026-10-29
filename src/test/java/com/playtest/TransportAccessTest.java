package com.playtest;

public final class TransportAccessTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        String key = "transport-test-only-credential-32-characters";
        check(!TransportAccess.authorized(null, key));
        check(!TransportAccess.authorized("Bearer wrong", key));
        check(!TransportAccess.authorized("Bearer " + key, null));
        check(TransportAccess.authorized("Bearer " + key, key));
        String ticket = TransportAccess.newToken();
        check(TransportAccess.validTicket(ticket, ticket, 300000, 299999));
        check(!TransportAccess.validTicket(ticket, ticket, 300000, 300000));
        check(!TransportAccess.validTicket(null, ticket, 300000, 0));
        check(!TransportAccess.validTicket(TransportAccess.newToken(), ticket, 300000, 0));
        try { TransportAccess.requireKey(null); throw new AssertionError(); } catch (IllegalStateException expected) { }
        System.out.println("9 transport authorization checks passed");
    }
}
