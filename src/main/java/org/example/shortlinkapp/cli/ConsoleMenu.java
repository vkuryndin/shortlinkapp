package org.example.shortlinkapp.cli;

import java.time.format.DateTimeFormatter;
import org.example.shortlinkapp.model.ShortLink;
import org.example.shortlinkapp.service.EventService;
import org.example.shortlinkapp.service.ShortLinkService;
import org.example.shortlinkapp.service.UserService;
import org.example.shortlinkapp.storage.ConfigJson;

/**
 * UI for ShortlinkApp.
 *
 * <p>This class is responsible for rendering menus, reading user input from {@code System.in},
 * delegating business operations to services, and printing results to {@code System.out}.
 *
 * <h2>Responsibilities</h2>
 *
 * <ul>
 *   <li>Top-level main loop and navigation across submenus
 *   <li>CRUD operations for short links via {@link ShortLinkService}
 *   <li>User management actions via {@link UserService}
 *   <li>Event viewing via {@link EventService}
 *   <li>Runtime configuration inspection/reload via {@link ConfigJson}
 * </ul>
 *
 * <h2>I/O model</h2>
 *
 * <ul>
 *   <li>Reads trimmed lines using {@link InputUtils#readTrimmed(String)}.
 *   <li>Gracefully exits the loop on EOF, or if the user chooses an exit command.
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>The class is intended for single-threaded, interactive console use.
 *
 * <h2>Error handling</h2>
 *
 * <ul>
 *   <li>Validation messages are printed to the console.
 *   <li>Operations that can fail return early and inform the user without throwing UI-level
 *       exceptions.
 * </ul>
 */
public class ConsoleMenu {

  private ConfigJson config;
  private String userUuid;

  private final UserService userService;
  private final ShortLinkService shortLinkService;

  private final EventService events;

  /**
   * Creates the console menu with the given configuration and current user UUID.
   *
   * @param config loaded configuration (non-null)
   * @param userUuid UUID of the current user (non-null)
   */
  public ConsoleMenu(ConfigJson config, String userUuid) {
    this.config = config;
    this.userUuid = userUuid;
    this.userService = new UserService(userUuid);
    this.events = new EventService(config.eventsLogEnabled);
    this.shortLinkService = new ShortLinkService(userUuid, config, events);
  }

  /**
   * Runs the main interactive loop until the user chooses to exit or input is closed.
   *
   * <p>Reads a single-line command, routes to the appropriate submenu/action, and repeats.
   */
  public void mainLoop() {
    while (true) {
      printMainMenu();
      String choice = InputUtils.readTrimmed("Select: ");

      if (choice == null) {
        System.out.println("Input closed. Exiting.");
        return;
      }

      switch (choice.toLowerCase()) {
        case "1" -> menuMyLinks();
        case "2" -> menuOpenShortLink();
        case "3" -> menuMaintenance();
        case "4" -> menuHelp();
        case "5" -> showSettings();
        case "6" -> menuUsers();
        case "7", "q", "quit", "exit" -> {
          return;
        }
        default -> System.out.println("Unknown option. Please try again.");
      }
    }
  }

  /** Prints the top-level menu options. */
  private void printMainMenu() {
    System.out.println();
    System.out.println("Main Menu");
    System.out.println("1. My Links");
    System.out.println("2. Open Short Link");
    System.out.println("3. Maintenance");
    System.out.println("4. Help / Examples");
    System.out.println("5. Settings (from config.json)");
    System.out.println("6. Users");
    System.out.println("7. Exit"); // <-- new item
  }

  // =========================
  // Submenu: My Links
  // =========================

  /**
   * Handles the "My Links" submenu: create/list/details/edit-limit/delete/bulk/notifications.
   *
   * <p>Loops until the user returns to the previous menu.
   */
  private void menuMyLinks() {
    while (true) {
      System.out.println();
      System.out.println("My Links");
      System.out.println("1. Create Short Link");
      System.out.println("2. List My Links");
      System.out.println("3. Link Details");
      System.out.println("4. Edit Click Limit (owner)");
      System.out.println("5. Delete Link (owner)");
      System.out.println("6. Bulk Actions (delete EXPIRED / LIMIT_REACHED; export JSON)");
      System.out.println("7. View Notifications");
      System.out.println("0. Back");

      String c = InputUtils.readTrimmed("Select: ");
      if (c == null || "0".equals(c)) return;

      switch (c) {
        case "1" -> actionCreateShortLink();
        case "2" -> actionListMyLinks();
        case "3" -> actionLinkDetails();
        case "4" -> actionEditClickLimit();
        case "5" -> actionDeleteLink();
        case "6" -> menuBulkMyLinks();
        case "7" -> actionViewNotifications();
        default -> System.out.println("Unknown option. Try again.");
      }
    }
  }

  /**
   * Creates a short link for a user-provided URL with an optional click limit.
   *
   * <p>Validates number input, delegates creation to {@link
   * ShortLinkService#createShortLink(String, Integer)}, and prints the resulting link summary or
   * validation error.
   */
  private void actionCreateShortLink() {
    String url = InputUtils.readTrimmed("Enter long URL: ");
    if (url == null || url.isBlank()) {
      System.out.println("No URL provided.");
      return;
    }
    String limitStr = InputUtils.readTrimmed("Enter click limit (empty = default): ");
    Integer limit = null;
    if (limitStr != null && !limitStr.isBlank()) {
      try {
        limit = Integer.parseInt(limitStr);
      } catch (NumberFormatException ignored) {
        System.out.println("Invalid number. Using default.");
      }
    }
    try {
      ShortLink l = shortLinkService.createShortLink(url, limit);
      System.out.println(
          "Created: "
              + config.baseUrl
              + l.shortCode
              + " \u2192 "
              + l.longUrl
              + " | expires: "
              + l.expiresAt
              + " | limit: "
              + (l.clickLimit == null ? "unlimited" : l.clickLimit));
    } catch (IllegalArgumentException ex) {
      System.out.println(ex.getMessage());
    }
  }

  /**
   * Lists the current user's links with optional status/text filters and sorting.
   *
   * <p>Supports:
   *
   * <ul>
   *   <li>Status filter (ALL/ACTIVE/EXPIRED/LIMIT_REACHED/DELETED)
   *   <li>Text search across shortCode and URL
   *   <li>Sort by created desc / clicks desc / expires asc
   * </ul>
   *
   * Prints a fixed-width table or a message if nothing matches.
   */
  private void actionListMyLinks() {
    var all = shortLinkService.listMyLinks();
    if (all.isEmpty()) {
      System.out.println("You have no links yet.");
      return;
    }

    // --- ask filters ---
    String st =
        InputUtils.readTrimmed(
            "Status filter [ALL/ACTIVE/EXPIRED/LIMIT_REACHED/DELETED] (empty=ALL): ");
    String query = InputUtils.readTrimmed("Search text (matches shortCode or URL, empty=none): ");
    String sort =
        InputUtils.readTrimmed("Sort [1=created desc, 2=clicks desc, 3=expires asc] (empty=1): ");

    // normalize
    String q = (query == null) ? "" : query.trim().toLowerCase();
    int sortIdx = 1;
    try {
      if (sort != null && !sort.isBlank()) sortIdx = Integer.parseInt(sort.trim());
    } catch (NumberFormatException ignored) {
    }

    // status filter
    java.util.function.Predicate<org.example.shortlinkapp.model.ShortLink> statusPred = l -> true;
    if (st != null && !st.isBlank()) {
      String s = st.trim().toUpperCase();
      if (!"ALL".equals(s)) { // <-- main change: ALL = no filter
        statusPred = l -> String.valueOf(l.status).equals(s);
      }
    }

    // text filter (shortCode or longUrl contains)
    java.util.function.Predicate<org.example.shortlinkapp.model.ShortLink> textPred =
        l -> {
          if (q.isEmpty()) return true;
          String sc = l.shortCode == null ? "" : l.shortCode.toLowerCase();
          String lu = l.longUrl == null ? "" : l.longUrl.toLowerCase();
          return sc.contains(q) || lu.contains(q);
        };

    // apply filters
    var list = new java.util.ArrayList<org.example.shortlinkapp.model.ShortLink>();
    for (var l : all) if (statusPred.test(l) && textPred.test(l)) list.add(l);

    // sorting
    java.util.Comparator<org.example.shortlinkapp.model.ShortLink> cmp;
    switch (sortIdx) {
      case 2 ->
          cmp =
              java.util.Comparator.comparingInt(
                      (org.example.shortlinkapp.model.ShortLink l) -> l.clickCount)
                  .reversed(); // clicks desc
      case 3 ->
          cmp =
              java.util.Comparator.comparing(
                  (org.example.shortlinkapp.model.ShortLink l) -> l.expiresAt,
                  java.util.Comparator.nullsLast(
                      java.util.Comparator.naturalOrder())); // expires asc (nulls last)
      default ->
          cmp =
              java.util.Comparator.comparing(
                      (org.example.shortlinkapp.model.ShortLink l) -> l.createdAt,
                      java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                  .reversed(); // created desc
    }
    list.sort(cmp);

    if (list.isEmpty()) {
      System.out.println("No links match your filters.");
      return;
    }

    // header
    String h =
        pad("#", 3)
            + " "
            + pad("shortCode", 10)
            + " "
            + pad("clicks/limit", 13)
            + " "
            + pad("expiresAt", 16)
            + " "
            + pad("status", 13)
            + " "
            + "longUrl";
    System.out.println(h);
    System.out.println("-".repeat(3 + 1 + 10 + 1 + 13 + 1 + 16 + 1 + 13 + 1 + 40));

    int i = 1;
    for (var l : list) {
      String limitStr = (l.clickLimit == null) ? "∞" : String.valueOf(l.clickLimit);
      String clicks = l.clickCount + "/" + limitStr;
      String line =
          pad(String.valueOf(i++), 3)
              + " "
              + pad(l.shortCode, 10)
              + " "
              + pad(clicks, 13)
              + " "
              + pad(fmtDT(l.expiresAt), 16)
              + " "
              + pad(String.valueOf(l.status), 13)
              + " "
              + trunc(l.longUrl, 80);
      System.out.println(line);
    }
  }

  /**
   * Prints detailed information about a single link, looked up by short code (or full
   * baseUrl+code).
   */
  private void actionLinkDetails() {
    String code = InputUtils.readTrimmed("Enter short code (or " + config.baseUrl + "code): ");
    if (code == null || code.isBlank()) {
      System.out.println("No code provided.");
      return;
    }
    String norm = normalizeInputCode(code); // <- using ConsoleMenu helper
    var opt = shortLinkService.findByShortCode(norm);
    if (opt.isEmpty()) {
      System.out.println("Link not found: " + norm);
      return;
    }
    var l = opt.get();

    System.out.println();
    System.out.println("== Link Details ==");
    System.out.printf("%-14s %s%n", "ID:", l.id);
    System.out.printf("%-14s %s%n", "Owner UUID:", l.ownerUuid);
    System.out.printf("%-14s %s%n", "Short Code:", l.shortCode);
    System.out.printf("%-14s %s%n", "Long URL:", l.longUrl);
    System.out.printf("%-14s %s%n", "Created At:", fmtDT(l.createdAt));
    System.out.printf("%-14s %s%n", "Expires At:", fmtDT(l.expiresAt));
    System.out.printf(
        "%-14s %s%n", "Click Limit:", (l.clickLimit == null ? "unlimited" : l.clickLimit));
    System.out.printf("%-14s %d%n", "Click Count:", l.clickCount);
    System.out.printf("%-14s %s%n", "Last Access:", fmtDT(l.lastAccessAt));
    System.out.printf("%-14s %s%n", "Status:", l.status);
  }

  /**
   * Normalizes an input code allowing either a raw short code or {@code baseUrl + code}.
   *
   * @param input user input
   * @return normalized short code (never null)
   */
  private String normalizeInputCode(String input) {
    String s = (input == null) ? "" : input.trim();
    String prefix = config.baseUrl;
    if (prefix != null && !prefix.isBlank() && s.startsWith(prefix)) {
      return s.substring(prefix.length());
    }
    return s;
  }

  /** Deletes a link by short code for the current owner after a confirmation prompt. */
  private void actionDeleteLink() {
    String code =
        InputUtils.readTrimmed("Enter short code to delete (or " + config.baseUrl + "code): ");
    if (code == null || code.isBlank()) {
      System.out.println("No code provided.");
      return;
    }
    String confirm = InputUtils.readTrimmed("Type DELETE to confirm: ");
    if (!"DELETE".equalsIgnoreCase(confirm != null ? confirm.trim() : "")) {
      System.out.println("Deletion cancelled.");
      return;
    }
    shortLinkService.deleteLink(code);
  }

  /**
   * Edits the click limit for an owned link (if permitted by configuration).
   *
   * <p>Accepts a number ≥ current clicks or the keyword {@code unlimited}.
   */
  private void actionEditClickLimit() {
    String code = InputUtils.readTrimmed("Enter short code (or " + config.baseUrl + "code): ");
    if (code == null || code.isBlank()) {
      System.out.println("No code provided.");
      return;
    }
    String newLimitStr =
        InputUtils.readTrimmed("New click limit (number >= current clicks) or 'unlimited': ");
    Integer newLimit = null;
    if (newLimitStr == null || newLimitStr.isBlank()) {
      System.out.println("No value provided.");
      return;
    }
    if (!"unlimited".equalsIgnoreCase(newLimitStr.trim())) {
      try {
        newLimit = Integer.parseInt(newLimitStr.trim());
      } catch (NumberFormatException e) {
        System.out.println("Invalid number.");
        return;
      }
    }
    boolean ok = shortLinkService.editClickLimit(code, newLimit);
    if (ok) {
      System.out.println("Click limit updated.");
    }
  }

  // **
  // * Exports the current user's links to a JSON file under {@code data/}.
  // * <p>Prints the result path on success; prints an error message otherwise.</p>
  // */
  // private void actionExportMyLinks() {
  //    var p = shortLinkService.exportMyLinks();
  //    if (p == null) {
  //        System.out.println("Export failed.");
  //    }
  // }

  /** Displays the most recent events/notifications for the current user (if logging is enabled). */
  private void actionViewNotifications() {
    if (!config.eventsLogEnabled) {
      System.out.println("Events/notifications are disabled by configuration.");
      return;
    }
    String limStr = InputUtils.readTrimmed("How many latest events to show? (default 20): ");
    int limit = 20;
    if (limStr != null && !limStr.isBlank()) {
      try {
        limit = Math.max(1, Integer.parseInt(limStr.trim()));
      } catch (NumberFormatException ignored) {
      }
    }

    var list = events.recentByOwner(userUuid, limit);
    if (list.isEmpty()) {
      System.out.println("No notifications yet.");
      return;
    }

    System.out.println(
        pad("time", 16) + " " + pad("type", 14) + " " + pad("shortCode", 10) + " message");
    System.out.println("-".repeat(16 + 1 + 14 + 1 + 10 + 1 + 50));

    for (var e : list) {
      String time = (e.ts == null) ? "-" : DT.format(e.ts);
      System.out.println(
          pad(time, 16)
              + " "
              + pad(String.valueOf(e.type), 14)
              + " "
              + pad(e.shortCode == null ? "-" : e.shortCode, 10)
              + " "
              + (e.message == null ? "-" : e.message));
    }
  }

  // =========================
  // Submenu: Open Short Link
  // =========================

  /**
   * Handles the "Open Short Link" submenu.
   *
   * <p>Allows entering a short code (or {@code baseUrl+code}) to open in the default browser.
   */
  private void menuOpenShortLink() {
    while (true) {
      System.out.println();
      System.out.println("Open Short Link");
      System.out.println("1.Enter short code to open");
      System.out.println("0.Back");
      String c = InputUtils.readTrimmed("Select: ");
      if (c == null || "0".equals(c)) return;

      if (c.equals("1")) {
        String code = InputUtils.readTrimmed("Enter short code (or " + config.baseUrl + "code): ");
        if (code == null || code.isBlank()) {
          System.out.println("No code provided.");
        } else {
          shortLinkService.openShortLink(code);
        }
      } else {
        System.out.println("Unknown option. Try again.");
      }
    }
  }

  // =========================
  // Submenu: Maintenance (stubs)
  // =========================

  /**
   * Handles maintenance utilities: cleanup expired/limit-reached, validate JSON, show stats, view
   * global events.
   */
  private void menuMaintenance() {
    while (true) {
      System.out.println();
      System.out.println("Maintenance");
      System.out.println("1. Cleanup expired (TTL)");
      System.out.println("2. Mark/cleanup limit-reached");
      System.out.println("3. Validate JSON integrity");
      System.out.println("4. Statistics");
      System.out.println("5. View Event Log (global)");
      System.out.println("0. Back");
      String c = InputUtils.readTrimmed("Select: ");
      if (c == null || "0".equals(c)) return;

      switch (c) {
        case "1" -> shortLinkService.cleanupExpired();
        case "2" -> shortLinkService.cleanupLimitReached();
        case "3" -> actionValidateJson();
        case "4" -> actionStatistics();
        case "5" -> actionViewEventLogGlobal();
        default -> System.out.println("Unknown option. Try again.");
      }
    }
  }

  /**
   * Reloads {@code config.json} from disk and applies changes to services.
   *
   * <p>Also updates event logging enablement and passes the new config to {@link ShortLinkService}.
   */
  private void actionReloadConfig() {
    // Reload configuration from disk; the method always returns a non-null instance
    org.example.shortlinkapp.storage.ConfigJson fresh =
        org.example.shortlinkapp.storage.ConfigJson.loadOrCreateDefault();

    this.config = fresh;
    this.events.setEnabled(fresh.eventsLogEnabled);
    this.shortLinkService.reloadConfig(fresh);

    System.out.println("Config reloaded.");
  }

  // =========================
  // Help / Settings
  // =========================

  /** Prints help with basic rules and examples. */
  private void menuHelp() {
    System.out.println();
    System.out.println("Help / Examples");
    System.out.println("- Only valid http/https URLs are accepted (must contain a host).");
    System.out.println(
        "- Short link format: " + config.baseUrl + "<shortCode> (you may enter just <shortCode>).");
    System.out.println("- TTL and default click limit are loaded from data/config.json.");
    System.out.println("- Expired links become EXPIRED and cannot be opened.");
    System.out.println("- After reaching the click limit, a link becomes LIMIT_REACHED.");
    System.out.println("- Only the owner can edit or delete a link.");
    System.out.println("- Your current user UUID: " + userUuid);
    System.out.println("- Example:");
    System.out.println("    Create: https://example.com -> " + config.baseUrl + "A1b2C3d");
    System.out.println("    Open:   " + config.baseUrl + "A1b2C3d -> launches in your browser");
    System.out.println("    After TTL: \"Link expired\"");
    System.out.println("    After limit: \"Click limit reached\"");
  }

  /**
   * Shows current configuration values and lets the user type {@code reload} to re-read the file.
   */
  private void showSettings() {
    System.out.println("\nSettings (config.json):");
    System.out.println("baseUrl: " + config.baseUrl);
    System.out.println("shortCodeLength: " + config.shortCodeLength);
    System.out.println("defaultTtlHours: " + config.defaultTtlHours);
    System.out.println("defaultClickLimit: " + config.defaultClickLimit);
    System.out.println("maxUrlLength: " + config.maxUrlLength);
    System.out.println("cleanupOnEachOp: " + config.cleanupOnEachOp);
    System.out.println("allowOwnerEditLimit: " + config.allowOwnerEditLimit);
    System.out.println("hardDeleteExpired: " + config.hardDeleteExpired);
    System.out.println("eventsLogEnabled: " + config.eventsLogEnabled);
    System.out.println("clockSkewToleranceSec: " + config.clockSkewToleranceSec);

    String act =
        InputUtils.readTrimmed(
            "\nType 'reload' to re-read config.json, or press Enter to go back: ");
    if ("reload".equalsIgnoreCase(act)) {
      actionReloadConfig();
    }
  }

  // users menu

  /** Handles the "Users" submenu: show current, list, switch, create-and-switch, save default. */
  private void menuUsers() {
    while (true) {
      System.out.println();
      System.out.println("Users");
      System.out.println("1. Show current user");
      System.out.println("2. List known users");
      System.out.println("3. Switch user (temporary)");
      System.out.println("4. Create new user and switch");
      System.out.println("5. Make current default");
      System.out.println("0. Back");
      String c = InputUtils.readTrimmed("Select: ");
      if (c == null || "0".equals(c)) return;

      switch (c) {
        case "1" -> actionShowCurrentUser();
        case "2" -> actionListKnownUsers();
        case "3" -> actionSwitchUser();
        case "4" -> actionCreateNewAndSwitch();
        case "5" -> actionMakeCurrentDefault();
        default -> System.out.println("Unknown option. Try again.");
      }
    }
  }

  /** Prints the current user's UUID. */
  private void actionShowCurrentUser() {
    System.out.println();
    System.out.println("== Current User ==");
    System.out.println("UUID: " + userUuid);
  }

  /** Lists all known users with creation and last-seen timestamps. */
  private void actionListKnownUsers() {
    var list = userService.listAll();
    if (list.isEmpty()) {
      System.out.println("No users yet.");
      return;
    }
    System.out.println(
        pad("#", 3)
            + " "
            + pad("UUID", 36)
            + " "
            + pad("Created", 16)
            + " "
            + pad("Last Seen", 16));
    System.out.println("-".repeat(3 + 1 + 36 + 1 + 16 + 1 + 16 + 10));
    int i = 1;
    for (var u : list) {
      System.out.println(
          pad(String.valueOf(i++), 3)
              + " "
              + pad(u.uuid, 36)
              + " "
              + pad(fmtDT(u.createdAt), 16)
              + " "
              + pad(fmtDT(u.lastSeenAt), 16));
    }
  }

  /**
   * Switches the current user to a provided UUID after validating the format.
   *
   * <p>Updates {@link UserService} and {@link ShortLinkService} ownership context.
   */
  private void actionSwitchUser() {
    String newId = InputUtils.readTrimmed("Enter user UUID: ");
    if (!isValidUuid(newId)) {
      System.out.println("Invalid UUID format. Example: 550e8400-e29b-41d4-a716-446655440000");
      return;
    }
    userService.switchCurrent(newId);
    shortLinkService.switchOwner(newId);
    this.userUuid = newId;
    if (events != null) {
      events.info(newId, "-", "USER_SWITCH");
    }
    System.out.println("Switched to: " + newId);
  }

  /** Creates a new random user UUID, switches the session to it, and persists to users' storage. */
  private void actionCreateNewAndSwitch() {
    String newId = userService.createNewUserAndSwitch();
    shortLinkService.switchOwner(newId);
    this.userUuid = newId;
    if (events != null) {
      events.info(newId, "-", "USER_CREATE");
      events.info(newId, "-", "USER_SWITCH");
    }
    System.out.println("New user created and switched: " + newId);
  }

  /**
   * Saves the current user UUID as the default one in the local file ({@code .local/user.uuid}).
   */
  private void actionMakeCurrentDefault() {
    boolean ok = org.example.shortlinkapp.storage.LocalUuid.setCurrentUserUuid(this.userUuid);
    if (ok && events != null) {
      events.info(this.userUuid, "-", "USER_SET_DEFAULT");
    }
    System.out.println(ok ? "Saved as default: " + this.userUuid : "Failed to save default user.");
  }

  /**
   * Computes and prints statistics (mine/global) and Top-N by clicks.
   *
   * <p>Delegates to {@link ShortLinkService#statsMine(int)} or {@link
   * ShortLinkService#statsGlobal(int)}.
   */
  private void actionStatistics() {
    System.out.println();
    String scope = InputUtils.readTrimmed("Scope [mine/global] (empty=mine): ");
    String topStr = InputUtils.readTrimmed("Top N by clicks (empty=5): ");

    int topN = 5;
    try {
      if (topStr != null && !topStr.isBlank()) topN = Math.max(1, Integer.parseInt(topStr.trim()));
    } catch (NumberFormatException ignored) {
    }

    boolean isGlobal = (scope != null && scope.trim().equalsIgnoreCase("global"));

    org.example.shortlinkapp.service.ShortLinkService.Stats st =
        isGlobal ? shortLinkService.statsGlobal(topN) : shortLinkService.statsMine(topN);

    System.out.println("\n== Statistics ==");
    System.out.println("Scope:             " + (isGlobal ? "global" : "mine"));
    System.out.println("Total links:       " + st.total);
    System.out.println("ACTIVE:            " + st.active);
    System.out.println("EXPIRED:           " + st.expired);
    System.out.println("LIMIT_REACHED:     " + st.limitReached);
    System.out.println("DELETED:           " + st.deleted);
    System.out.println("Total clicks:      " + st.totalClicks);

    System.out.println("\nTop by clicks:");
    if (st.topByClicks.isEmpty()) {
      System.out.println("(none)");
      return;
    }

    if (isGlobal) {
      // with ownerUuid
      System.out.println(
          pad("#", 3)
              + " "
              + pad("shortCode", 10)
              + " "
              + pad("clicks", 7)
              + " "
              + pad("status", 13)
              + " "
              + pad("ownerUuid", 36)
              + " longUrl");
      System.out.println("-".repeat(3 + 1 + 10 + 1 + 7 + 1 + 13 + 1 + 36 + 1 + 40));

      int i = 1;
      for (var l : st.topByClicks) {
        System.out.println(
            pad(String.valueOf(i++), 3)
                + " "
                + pad(l.shortCode, 10)
                + " "
                + pad(String.valueOf(l.clickCount), 7)
                + " "
                + pad(String.valueOf(l.status), 13)
                + " "
                + pad(l.ownerUuid == null ? "-" : l.ownerUuid, 36)
                + " "
                + trunc(l.longUrl, 80));
      }
    } else {
      // without ownerUuid (for mine)
      System.out.println(
          pad("#", 3)
              + " "
              + pad("shortCode", 10)
              + " "
              + pad("clicks", 7)
              + " "
              + pad("status", 13)
              + " longUrl");
      System.out.println("-".repeat(3 + 1 + 10 + 1 + 7 + 1 + 13 + 1 + 40));

      int i = 1;
      for (var l : st.topByClicks) {
        System.out.println(
            pad(String.valueOf(i++), 3)
                + " "
                + pad(l.shortCode, 10)
                + " "
                + pad(String.valueOf(l.clickCount), 7)
                + " "
                + pad(String.valueOf(l.status), 13)
                + " "
                + trunc(l.longUrl, 80));
      }
    }
  }

  // MENU bulk my links

  /**
   * Handles "Bulk Actions (My Links)" submenu:
   *
   * <ul>
   *   <li>Delete/mark expired
   *   <li>Delete/mark limit-reached
   *   <li>Export current user's links
   * </ul>
   */
  private void menuBulkMyLinks() {
    while (true) {
      System.out.println();
      System.out.println("Bulk Actions (My Links)");
      System.out.println("1. Delete/mark EXPIRED (mine)");
      System.out.println("2. Delete/mark LIMIT_REACHED (mine)");
      System.out.println("3. Export my links (JSON)");
      System.out.println("0. Back");
      String c = InputUtils.readTrimmed("Select: ");
      if (c == null || "0".equals(c)) return;

      switch (c) {
        case "1" -> shortLinkService.bulkDeleteExpiredMine();
        case "2" -> shortLinkService.bulkDeleteLimitReachedMine();
        case "3" -> {
          var p = shortLinkService.exportMyLinks();
          if (p == null) System.out.println("Export failed.");
        }
        default -> System.out.println("Unknown option. Try again.");
      }
    }
  }

  /**
   * Validates JSON storage for common data issues and prints a concise report.
   *
   * <p>If issues exceed 30, prints only the first 30 lines and a tail count.
   */
  private void actionValidateJson() {
    var rep = shortLinkService.validateJson();
    System.out.println("\n== JSON Validation Report ==");
    System.out.println("Total links: " + rep.totalLinks);
    System.out.println("Issues:      " + rep.issues);

    if (rep.issues == 0) {
      System.out.println("No problems found. ✔");
      return;
    }

    // showing first 30 messages not to overload the console
    int show = Math.min(30, rep.messages.size());
    for (int i = 0; i < show; i++) {
      System.out.println("- " + rep.messages.get(i));
    }
    if (rep.messages.size() > show) {
      System.out.println("... and " + (rep.messages.size() - show) + " more");
    }

    System.out.println(
        "\nHint: run Maintenance → [1] Cleanup expired and [2] Cleanup limit-reached to fix common states.");
  }

  // ---- pretty helpers ----

  /** Shared formatter for timestamps printed in tables and details. */
  private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  /**
   * Formats a {@link java.time.LocalDateTime} or returns {@code "-"} if null.
   *
   * @param dt date-time value (nullable)
   * @return formatted string or {@code "-"}
   */
  private String fmtDT(java.time.LocalDateTime dt) {
    return (dt == null) ? "-" : DT.format(dt);
  }

  /**
   * Truncates a string to the specified max length with an ellipsis if needed.
   *
   * @param s source string (nullable)
   * @param max maximum length to display
   * @return truncated string (or {@code "-"} if null)
   */
  private String trunc(String s, int max) {
    if (s == null) return "-";
    s = s.trim();
    if (s.length() <= max) return s;
    if (max <= 3) return s.substring(0, Math.max(0, max));
    return s.substring(0, max - 3) + "...";
  }

  /**
   * Pads a string on the right with spaces up to the given width.
   *
   * @param s text to pad (nullable)
   * @param width total width
   * @return padded text
   */
  private String pad(String s, int width) {
    if (s == null) s = "";
    if (s.length() >= width) return s;
    StringBuilder sb = new StringBuilder(width);
    sb.append(s);
    while (sb.length() < width) sb.append(' ');
    return sb.toString();
  }

  /**
   * Validates a UUID string using {@link java.util.UUID#fromString(String)}.
   *
   * @param s candidate string
   * @return {@code true} if valid UUID; otherwise {@code false}
   */
  private boolean isValidUuid(String s) {
    try {
      java.util.UUID.fromString(s);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** Prints the most recent events across all users (global log), if enabled. */
  private void actionViewEventLogGlobal() {
    if (!config.eventsLogEnabled) {
      System.out.println("Events/notifications are disabled by configuration.");
      return;
    }
    String limStr = InputUtils.readTrimmed("How many latest events to show? (default 50): ");
    int limit = 50;
    if (limStr != null && !limStr.isBlank()) {
      try {
        limit = Math.max(1, Integer.parseInt(limStr.trim()));
      } catch (NumberFormatException ignored) {
      }
    }

    var list = events.recentGlobal(limit);
    if (list.isEmpty()) {
      System.out.println("No events yet.");
      return;
    }

    System.out.println(
        pad("time", 16)
            + " "
            + pad("type", 14)
            + " "
            + pad("ownerUuid", 36)
            + " "
            + pad("shortCode", 10)
            + " message");
    System.out.println("-".repeat(16 + 1 + 14 + 1 + 36 + 1 + 10 + 1 + 50));

    for (var e : list) {
      String time =
          (e.ts == null) ? "-" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(e.ts);
      System.out.println(
          pad(time, 16)
              + " "
              + pad(String.valueOf(e.type), 14)
              + " "
              + pad(e.ownerUuid == null ? "-" : e.ownerUuid, 36)
              + " "
              + pad(e.shortCode == null ? "-" : e.shortCode, 10)
              + " "
              + (e.message == null ? "-" : e.message));
    }
  }
}
