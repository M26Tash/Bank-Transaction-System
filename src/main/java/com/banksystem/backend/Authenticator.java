package com.banksystem.backend;

public interface Authenticator {

    String register(String email, String password);

    String login(String email, String password);
}
