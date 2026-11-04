package com.banksystem.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(1_000);

    @Test
    void verifiesCorrectPasswordOnly() {
        String hash = hasher.hash("secret123");
        assertTrue(hasher.verify("secret123", hash));
        assertFalse(hasher.verify("secret124", hash));
        assertFalse(hasher.verify("", hash));
    }

    @Test
    void usesRandomSalt() {
        assertNotEquals(hasher.hash("same password"), hasher.hash("same password"));
    }

    @Test
    void rejectsMalformedHashes() {
        assertFalse(hasher.verify("x", null));
        assertFalse(hasher.verify("x", "plaintext"));
        assertFalse(hasher.verify("x", "pbkdf2_sha256$abc$AAAA$AAAA"));
        assertFalse(hasher.verify("x", "pbkdf2_sha256$1000$***$***"));
    }
}
