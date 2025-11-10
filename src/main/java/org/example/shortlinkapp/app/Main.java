package org.example.shortlinkapp.app;

import org.example.shortlinkapp.cli.ConsoleMenu;
import org.example.shortlinkapp.storage.ConfigJson;
import org.example.shortlinkapp.storage.LocalUuid;

/**
 * Entry point of the ShortLink CLI Java application.
 *
 * <p>This class is responsible for bootstrapping the runtime environment:
 *
 * <ol>
 *   <li>Loads application configuration from {@code config.json}, creating a default one if it does
 *       not exist (via {@link ConfigJson#loadOrCreateDefault()}).
 *   <li>Ensures a persistent UUID is available for the current local user/session (via {@link
 *       LocalUuid#ensureCurrentUserUuid()}), which is used as the owner identifier for link
 *       management and other user-scoped operations.
 *   <li>Prints a simple welcome banner with diagnostic information (resolved user UUID and the
 *       absolute path of the configuration file).
 *   <li>Constructs and runs the main interactive console menu (see {@link ConsoleMenu#mainLoop()}),
 *       which handles all subsequent user interactions until exit.
 *   <li>Performs a graceful shutdown by printing a farewell message.
 * </ol>
 *
 * <p><b>Console I/O:</b> The application is an interactive CLI and uses standard input/output for
 * reading commands and displaying results. It is expected to run in a terminal or an IDE console.
 *
 * <p><b>Threading:</b> The application runs on a single thread; no background workers are started
 * from this entry point.
 *
 * <p><b>Persistence &amp; Files:</b> Configuration and user identity are stored in local JSON files
 * (see {@link ConfigJson} and {@link LocalUuid}). The exact locations depend on the storage
 * abstractions used by those classes.
 *
 * @see ConsoleMenu
 * @see ConfigJson
 * @see LocalUuid
 */
public class Main {

  /**
   * Launches the ShortLink CLI application.
   *
   * <p>The method follows these steps:
   *
   * <ol>
   *   <li>Load or initialize configuration.
   *   <li>Ensure a stable local user UUID.
   *   <li>Print a banner with basic runtime info.
   *   <li>Instantiate the {@link ConsoleMenu} and enter the main loop.
   *   <li>Print a goodbye message upon exit.
   * </ol>
   *
   * @param args command-line arguments (currently unused)
   */
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
