package com.banksystem.backend.firebase;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import com.banksystem.backend.Backend;
import com.banksystem.backend.BankException;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;

public final class FirebaseBackend {

    private FirebaseBackend() {
    }

    public static Backend connect(FirebaseConfig config) {
        GoogleCredentials credentials;
        try (InputStream in = Files.newInputStream(config.credentialsFile())) {
            credentials = GoogleCredentials.fromStream(in);
        } catch (IOException e) {
            throw new BankException("Cannot read service account file " + config.credentialsFile()
                    + ": " + e.getMessage());
        }

        String projectId = credentials instanceof ServiceAccountCredentials account ? account.getProjectId() : null;
        if (projectId == null) {
            throw new BankException(config.credentialsFile() + " is not a service account key file");
        }

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId(projectId)
                .build();
        FirebaseApp app = FirebaseApp.getApps().isEmpty()
                ? FirebaseApp.initializeApp(options)
                : FirebaseApp.getInstance();
        Firestore firestore = FirestoreClient.getFirestore(app);

        return new Backend(
                "Online (Firebase project " + projectId + ")",
                new FirebaseAuthenticator(config.apiKey()),
                new FirestoreRepository(firestore),
                () -> {
                    firestore.close();
                    app.delete();
                });
    }
}
