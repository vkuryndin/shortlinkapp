package org.example.shortlinkapp.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.example.shortlinkapp.storage.ConfigJson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration-style tests for {@link ConsoleMenu}.
 *
 * <p><b>Key testing strategy:</b>
 * <ul>
 *   <li>We simulate a real interactive session by piping a scripted sequence into {@code System.in}.
 *       This is critical because {@link InputUtils} uses a static final {@link java.util.Scanner}
 *       bound to {@code System.in} at class-load time. Therefore, we set {@code System.in} <i>before</i>
 *       the first call that might initialize {@code InputUtils}.</li>
 *   <li>The test captures {@code System.out} to assert on visible console output only (public
 *       contract) without using any reflection or hacks.</li>
 *   <li>The app writes JSON files under the relative {@code data/} folder. We isolate that by
 *       switching {@code user.dir} to a JUnit {@code @TempDir} so all repositories work inside a
 *       disposable sandbox.</li>
 * </ul>
 *
 * <p>The scripted run goes through:
 * <ol>
 *   <li>Settings → reload config (covers {@code showSettings()} and {@code actionReloadConfig()})</li>
 *   <li>Users → show current + list known users (covers {@code menuUsers()})</li>
 *   <li>My Links → create link (valid URL, invalid limit → "using default"), list, details (blank),
 *       edit limit (blank), delete (blank), notifications</li>
 *   <li>Open Short Link → option 1 with blank code</li>
 *   <li>Maintenance → JSON validation</li>
 *   <li>Help</li>
 *   <li>Exit</li>
 * </ol>
 */
public class ConsoleMenuTest {

    private PrintStream originalOut;
    private java.io.InputStream originalIn;
    private String originalUserDir;

    @TempDir Path tempDir;

    private ByteArrayOutputStream out;

    @BeforeEach
    void setUp() {
        // 1) Isolate working directory so DataPaths (relative "data") point into a sandbox
        originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

        // 2) Capture stdout
        originalOut = System.out;
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

        // 3) Keep original stdin reference (we will override per-test)
        originalIn = System.in;
    }

    @AfterEach
    void tearDown() throws Exception {
        // Restore I/O and working dir
        System.setOut(originalOut);
        System.setIn(originalIn);
        System.setProperty("user.dir", originalUserDir);
    }

    /**
     * Runs a full interactive scenario through most ConsoleMenu branches and asserts on key
     * user-visible messages. This should provide >80% coverage of ConsoleMenu.
     */
    @Test
    void fullFlow_shouldPrintExpectedPromptsAndExitCleanly() {
        // Arrange: prepare a deterministic config instance (we still allow reload to hit disk defaults)
        ConfigJson cfg = new ConfigJson();
        cfg.baseUrl = "cli://";
        cfg.eventsLogEnabled = true;
        cfg.cleanupOnEachOp = true;
        cfg.allowOwnerEditLimit = true;

        String userUuid = "11111111-1111-1111-1111-111111111111";

        // Important: set System.in BEFORE the first call into InputUtils to ensure its static Scanner
        // will read from our stream (class initialization happens on first use).
        String script =
                String.join(
                        "\n",
                        // 5) Settings -> reload (covers actionReloadConfig)
                        "5",
                        "reload",
                        // 6) Users -> show current -> list known -> back
                        "6",
                        "1",
                        "2",
                        "0",
                        // 1) My Links
                        "1",
                        // Create: valid URL, invalid limit -> "Invalid number. Using default."
                        "1",
                        "http://example.com",
                        "x",
                        // List: blank filters (ALL / no query / default sort)
                        "2",
                        "",
                        "",
                        "",
                        // Details: blank code
                        "3",
                        "",
                        // Edit limit: blank code -> "No value" path starts only after non-blank; here we trigger "No code provided."
                        "4",
                        "",
                        // Delete: blank code
                        "5",
                        "",
                        // View Notifications (limit blank)
                        "7",
                        "",
                        // Back from "My Links"
                        "0",
                        // 2) Open Short Link -> 1 -> blank code -> back
                        "2",
                        "1",
                        "",
                        "0",
                        // 3) Maintenance -> JSON validation -> back
                        "3",
                        "3",
                        "0",
                        // 4) Help
                        "4",
                        // 7) Exit
                        "7") + "\n";

        System.setIn(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));

        ConsoleMenu menu = new ConsoleMenu(cfg, userUuid);

        // Act
        menu.mainLoop();

        // Assert: check important, stable fragments only (avoid overly brittle exact matches)
        String console = out.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("Main Menu"), "Main menu header should be printed at least once.");
        assertTrue(console.contains("Settings (config.json):"), "Settings screen should appear.");
        assertTrue(console.contains("Config reloaded."), "Reload confirmation should be printed.");

        // Users flow
        assertTrue(console.contains("Users"), "Users menu should appear.");
        assertTrue(console.contains("== Current User =="), "Current user header should appear.");
        assertTrue(console.contains("UUID: " + userUuid), "Current user UUID should be shown.");

        // My Links: create + list + details/edit/delete blanks + notifications
        assertTrue(console.contains("My Links"), "My Links menu should appear.");
        assertTrue(
                console.contains("Enter long URL: "),
                "Prompt for long URL should be shown when creating a link.");
        assertTrue(
                console.contains("Invalid number. Using default."),
                "Invalid limit should fall back to default.");
        assertTrue(
                console.contains("Created: "),
                "Successful creation should print a 'Created:' line.");
        assertTrue(
                console.contains("http://example.com"),
                "Created line should mention the original long URL.");
        assertTrue(
                console.contains("You have no links yet.") || console.contains("shortCode"),
                "Either no links or a table header should be shown when listing links.");
        assertTrue(
                console.contains("No code provided."),
                "Blank inputs for code should be handled gracefully.");

        // Open Short Link submenu
        assertTrue(console.contains("Open Short Link"), "Open Short Link menu should appear.");

        // Maintenance -> validation
        assertTrue(console.contains("== JSON Validation Report =="), "Validation report header.");
        assertTrue(console.contains("Total links:"), "Validation report total count.");
        // Either zero-issues path or messages list (we accept both, depending on timing/content)
        assertTrue(
                console.contains("No problems found.") || console.contains("Issues:"),
                "Validation should print issues count or a 'no problems' message.");

        // Help
        assertTrue(console.contains("Help / Examples"), "Help screen should be printed.");
    }
}
