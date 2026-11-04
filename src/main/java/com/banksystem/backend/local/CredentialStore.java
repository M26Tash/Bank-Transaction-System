package com.banksystem.backend.local;

import java.util.Optional;

public interface CredentialStore {

    record Credential(String email, String userId, String passwordHash) {
    }

    Optional<Credential> findCredential(String email);

    void saveCredential(Credential credential);
}
