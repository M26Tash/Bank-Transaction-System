package com.banksystem.backend.firebase;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import com.banksystem.backend.Authenticator;
import com.banksystem.backend.BankException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class FirebaseAuthenticator implements Authenticator {

    private static final String ENDPOINT = "https://identitytoolkit.googleapis.com/v1/accounts:";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String apiKey;
    private final HttpClient http;

    public FirebaseAuthenticator(String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public String register(String email, String password) {
        return call("signUp", email, password);
    }

    @Override
    public String login(String email, String password) {
        return call("signInWithPassword", email, password);
    }

    private String call(String method, String email, String password) {
        JsonObject body = new JsonObject();
        body.addProperty("email", email);
        body.addProperty("password", password);
        body.addProperty("returnSecureToken", true);

        HttpRequest request = HttpRequest.newBuilder(
                URI.create(ENDPOINT + method + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BankException("Cannot reach Firebase Authentication. Check your internet connection.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BankException("Request was interrupted");
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new BankException("Unexpected response from Firebase Authentication (HTTP "
                    + response.statusCode() + ")");
        }

        if (response.statusCode() == 200 && json.has("localId")) {
            return json.get("localId").getAsString();
        }
        throw new BankException(describeError(errorCode(json)));
    }

    private static String errorCode(JsonObject json) {
        JsonElement error = json.get("error");
        if (error != null && error.isJsonObject() && error.getAsJsonObject().has("message")) {
            return error.getAsJsonObject().get("message").getAsString();
        }
        return "UNKNOWN";
    }

    static String describeError(String code) {
        if (code.startsWith("API key not valid")) {
            return "The Firebase API key is not valid. Check FIREBASE_API_KEY / firebase.properties.";
        }
        String key = code.split(" ", 2)[0];
        return switch (key) {
            case "EMAIL_EXISTS" -> "An account with this email already exists";
            case "INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "INVALID_PASSWORD" -> "Invalid email or password";
            case "INVALID_EMAIL" -> "Please enter a valid email address";
            case "WEAK_PASSWORD" -> "Password is too weak (at least 6 characters)";
            case "MISSING_PASSWORD" -> "Password is required";
            case "TOO_MANY_ATTEMPTS_TRY_LATER" -> "Too many attempts. Please try again later.";
            case "USER_DISABLED" -> "This account has been disabled";
            case "OPERATION_NOT_ALLOWED", "PASSWORD_LOGIN_DISABLED" ->
                "Email/password sign-in is not enabled in this Firebase project";
            case "CONFIGURATION_NOT_FOUND" -> "Firebase Authentication is not set up in this project yet";
            default -> "Firebase Authentication error: " + code;
        };
    }
}
