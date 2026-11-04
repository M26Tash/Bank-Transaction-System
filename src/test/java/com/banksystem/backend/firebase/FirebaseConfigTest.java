package com.banksystem.backend.firebase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.banksystem.backend.BankException;

class FirebaseConfigTest {

    @Test
    void readsPropertiesFile() throws IOException {
        Path dir = Files.createTempDirectory("firebase-config");
        Path key = Files.writeString(dir.resolve("key.json"), "{}");
        Path props = Files.writeString(dir.resolve("firebase.properties"),
                "apiKey=abc123\ncredentials=" + key.toString().replace("\\", "\\\\") + "\n");

        FirebaseConfig config = FirebaseConfig.load(props, Map.of());
        assertEquals("abc123", config.apiKey());
        assertEquals(key, config.credentialsFile());
        assertFalse(config.toString().contains("abc123"));
    }

    @Test
    void environmentOverridesFile() throws IOException {
        Path dir = Files.createTempDirectory("firebase-config");
        Path key = Files.writeString(dir.resolve("key.json"), "{}");
        Path props = Files.writeString(dir.resolve("firebase.properties"), "apiKey=from-file\n");

        FirebaseConfig config = FirebaseConfig.load(props,
                Map.of("FIREBASE_API_KEY", "from-env", "FIREBASE_CREDENTIALS", key.toString()));
        assertEquals("from-env", config.apiKey());
        assertEquals(key, config.credentialsFile());
    }

    @Test
    void explainsWhatIsMissing() throws IOException {
        Path missing = Files.createTempDirectory("firebase-config").resolve("firebase.properties");

        BankException noKey = assertThrows(BankException.class, () -> FirebaseConfig.load(missing, Map.of()));
        assertTrue(noKey.getMessage().contains("API key"), noKey.getMessage());

        BankException noFile = assertThrows(BankException.class, () -> FirebaseConfig.load(missing,
                Map.of("FIREBASE_API_KEY", "k", "FIREBASE_CREDENTIALS", "/does/not/exist.json")));
        assertTrue(noFile.getMessage().contains("not found"), noFile.getMessage());
    }
}
