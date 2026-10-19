package com.vaultdrive.sharing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class ShareLinkTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private ShareLinkTokens() {}

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw ShareLinkService.notFound();
        byte[] decoded = Base64.getUrlDecoder().decode(token);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) {
            throw ShareLinkService.notFound();
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
