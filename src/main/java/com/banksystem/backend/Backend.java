package com.banksystem.backend;

import java.util.Objects;

public record Backend(String description, Authenticator authenticator, BankRepository repository,
        AutoCloseable resources) implements AutoCloseable {

    public Backend {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(authenticator, "authenticator");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(resources, "resources");
    }

    @Override
    public void close() {
        try {
            resources.close();
        } catch (Exception e) {

        }
    }
}
