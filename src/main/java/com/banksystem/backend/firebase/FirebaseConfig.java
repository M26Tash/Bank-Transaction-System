package com.banksystem.backend.firebase;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import com.banksystem.backend.BankException;

public record FirebaseConfig(String apiKey, Path credentialsFile) {

    public static final Path DEFAULT_FILE = Path.of("firebase.properties");

    public static FirebaseConfig load(Path propertiesFile) {
        return load(propertiesFile, System.getenv());
    }

    static FirebaseConfig load(Path propertiesFile, Map<String, String> env) {
        Properties props = new Properties();
        if (propertiesFile != null && Files.isRegularFile(propertiesFile)) {
            try (Reader reader = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                throw new BankException("Cannot read " + propertiesFile + ": " + e.getMessage());
            }
        }

        String apiKey = firstNonBlank(env.get("FIREBASE_API_KEY"), props.getProperty("apiKey"));
        String credentials = firstNonBlank(env.get("FIREBASE_CREDENTIALS"), props.getProperty("credentials"),
                env.get("GOOGLE_APPLICATION_CREDENTIALS"));

        if (apiKey == null) {
            throw new BankException("Firebase API key is missing. Set FIREBASE_API_KEY or 'apiKey' in "
                    + propertiesFile + " (see firebase.properties.example)");
        }
        if (credentials == null) {
            throw new BankException("Firebase service account is missing. Set FIREBASE_CREDENTIALS or "
                    + "'credentials' in " + propertiesFile + " (see firebase.properties.example)");
        }
        Path credentialsFile = Path.of(credentials);
        if (!Files.isRegularFile(credentialsFile)) {
            throw new BankException("Service account file not found: " + credentialsFile.toAbsolutePath());
        }
        return new FirebaseConfig(apiKey, credentialsFile);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "FirebaseConfig[apiKey=***, credentialsFile=" + credentialsFile + "]";
    }
}
