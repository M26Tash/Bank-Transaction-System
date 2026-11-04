package com.banksystem.backend.firebase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FirebaseAuthenticatorTest {

    @Test
    void translatesKnownErrorCodes() {
        assertEquals("An account with this email already exists",
                FirebaseAuthenticator.describeError("EMAIL_EXISTS"));
        assertEquals("Invalid email or password",
                FirebaseAuthenticator.describeError("INVALID_LOGIN_CREDENTIALS"));
        assertEquals("Invalid email or password",
                FirebaseAuthenticator.describeError("INVALID_PASSWORD"));
        assertEquals("Password is too weak (at least 6 characters)",
                FirebaseAuthenticator.describeError("WEAK_PASSWORD : Password should be at least 6 characters"));
        assertTrue(FirebaseAuthenticator.describeError("API key not valid. Please pass a valid API key.")
                .contains("API key"));
    }

    @Test
    void keepsUnknownCodesVisible() {
        assertEquals("Firebase Authentication error: SOMETHING_NEW",
                FirebaseAuthenticator.describeError("SOMETHING_NEW"));
    }
}
