package com.banksystem.backend.local;

import java.util.Objects;
import java.util.UUID;

import com.banksystem.backend.Authenticator;
import com.banksystem.backend.BankException;
import com.banksystem.backend.local.CredentialStore.Credential;
import com.banksystem.security.PasswordHasher;

public final class LocalAuthenticator implements Authenticator {

    private final CredentialStore store;
    private final PasswordHasher hasher;

    public LocalAuthenticator(CredentialStore store, PasswordHasher hasher) {
        this.store = Objects.requireNonNull(store, "store");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    @Override
    public String register(String email, String password) {
        if (store.findCredential(email).isPresent()) {
            throw new BankException("An account with this email already exists");
        }
        String userId = UUID.randomUUID().toString();
        store.saveCredential(new Credential(email, userId, hasher.hash(password)));
        return userId;
    }

    @Override
    public String login(String email, String password) {

        return store.findCredential(email)
                .filter(c -> hasher.verify(password, c.passwordHash()))
                .map(Credential::userId)
                .orElseThrow(() -> new BankException("Invalid email or password"));
    }
}
