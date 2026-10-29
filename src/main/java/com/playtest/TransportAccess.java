package com.playtest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class TransportAccess {
    static void requireKey(String key) {
        if (key == null || key.length() < 32) throw new IllegalStateException("Set FORGE_TRANSPORT_SECRET to at least 32 characters");
    }
    static boolean same(String a, String b) {
        if (a == null || b == null || a.length() > 1024) return false;
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(hash.digest(a.getBytes(StandardCharsets.UTF_8)), hash.digest(b.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { return false; }
    }
    static boolean authorized(String header, String key) {
        return key != null && key.length() >= 32 && header != null && header.startsWith("Bearer ") && same(header.substring(7), key);
    }
    static boolean validTicket(String ticket, String expected, long expires, long now) {
        return now < expires && same(ticket, expected);
    }
    static String newToken() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
