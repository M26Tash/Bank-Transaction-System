package com.banksystem;

import java.nio.file.Path;

import com.banksystem.backend.Backend;
import com.banksystem.backend.firebase.FirebaseBackend;
import com.banksystem.backend.firebase.FirebaseConfig;
import com.banksystem.backend.local.LocalBackend;
import com.banksystem.cli.ConsoleApp;
import com.banksystem.service.BankService;


public final class Main {

    private static final String USAGE = """
            Usage:
              (no options)              local mode, SQLite database data/bank.db
              --data <file>             local mode with a different database file
              --online                  online mode: Firebase Auth + Cloud Firestore
              --online --config <file>  online mode with a different config (default: firebase.properties)
              --help                    show this message
            """;

    private Main() {
    }

    public static void main(String[] args) {
        boolean online = false;
        Path dataFile = Path.of("data/bank.db");
        Path configFile = FirebaseConfig.DEFAULT_FILE;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--online" -> online = true;
                case "--data" -> dataFile = Path.of(requireValue(args, ++i, "--data"));
                case "--config" -> configFile = Path.of(requireValue(args, ++i, "--config"));
                case "--help", "-h" -> {
                    System.out.print(USAGE);
                    return;
                }
                default -> exitWithError("Unknown option: " + args[i] + "\n\n" + USAGE);
            }
        }

        Backend backend;
        try {
            backend = online
                    ? FirebaseBackend.connect(FirebaseConfig.load(configFile))
                    : LocalBackend.open(dataFile);
        } catch (RuntimeException e) {
            exitWithError("Could not start: " + e.getMessage());
            return;
        }

        try (backend) {
            System.out.println("Mode: " + backend.description());
            BankService bank = new BankService(backend.authenticator(), backend.repository());
            new ConsoleApp(bank, System.in, System.out).run();
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            exitWithError(option + " needs a value\n\n" + USAGE);
        }
        return args[index];
    }

    private static void exitWithError(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
