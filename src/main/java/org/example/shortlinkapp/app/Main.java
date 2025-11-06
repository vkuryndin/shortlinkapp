package org.example.shortlinkapp.app;

import org.example.shortlinkapp.cli.ConsoleMenu;
import org.example.shortlinkapp.storage.ConfigJson;
import org.example.shortlinkapp.storage.LocalUuid;

public class Main {

    public static void main(String[] args) {
        // 1) Load config (create defaults if missing)
        ConfigJson config = ConfigJson.loadOrCreateDefault();

        // 2) Ensure current user UUID exists
        String userUuid = LocalUuid.ensureCurrentUserUuid();

        // 3) Welcome banner
        System.out.println("========================================");
        System.out.println(" ShortLink CLI (Java)");
        System.out.println("========================================");
        System.out.println("User UUID: " + userUuid);
        System.out.println("Config loaded from: " + ConfigJson.getConfigPath().toAbsolutePath());
        System.out.println("Type a number to choose an option, 'q' to quit.\n");

        // 4) Run main menu loop
        ConsoleMenu menu = new ConsoleMenu(config, userUuid);
        menu.mainLoop();

        // 5) Graceful exit
        System.out.println("\nBye!");
    }
}
