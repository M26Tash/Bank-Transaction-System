package com.banksystem.backend.local;

import java.nio.file.Path;

import com.banksystem.backend.Backend;
import com.banksystem.security.PasswordHasher;

public final class LocalBackend {

    private LocalBackend() {
    }

    public static Backend open(Path databaseFile) {
        SqliteDatabase db = SqliteDatabase.open(databaseFile);
        return new Backend("Local (SQLite database " + databaseFile.toAbsolutePath() + ")",
                new LocalAuthenticator(db, new PasswordHasher()), db, db);
    }
}
