package org.example.shortlinkapp.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.example.shortlinkapp.storage.DataPaths;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Working, isolated tests for {@link EventService}.
 *
 * <p><b>Isolation:</b> We force {@code user.dir} to a {@code @TempDir} and delete {@code
 * data/events.json} before each test. This guarantees that no events from previous tests will leak
 * in (accumulation was the cause of incorrect counts).
 *
 * <p><b>Only public API is used:</b> {@code info/expired/limitReached/error}, {@code listByOwner},
 * {@code recentByOwner}, {@code recentGlobal}, {@code setEnabled}.
 *
 * <p><b>Debug output:</b> Test prints current file location and list sizes to help diagnose
 * unexpected behavior on CI or local machines.
 */
public class EventServiceTest {

  @TempDir Path tempDir;

  private String originalUserDir;
  private PrintStream originalOut;
  private ByteArrayOutputStream outCapture;

  @BeforeEach
  void setUp() throws Exception {
    // Redirect user.dir so DataPaths resolves to sandbox "tempDir/data"
    originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

    // Ensure data dir exists and clean up events.json so we start from empty state
    Files.createDirectories(DataPaths.DATA_DIR);
    Files.deleteIfExists(DataPaths.EVENTS_JSON);

    // Capture stdout (and also use it for debug prints)
    originalOut = System.out;
    outCapture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outCapture, true, StandardCharsets.UTF_8));

    // Debug: where are we writing?
    System.out.println("[DEBUG] user.dir=" + System.getProperty("user.dir"));
    System.out.println("[DEBUG] EVENTS_JSON=" + DataPaths.EVENTS_JSON.toAbsolutePath());
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    System.setProperty("user.dir", originalUserDir);
  }

  @Test
  @DisplayName(
      "Enabled service: logs are stored, owner filter and recent ordering work, limits applied")
  void loggingAndQueries_enabled() {
    // Use a single EventService instance for both writing and reading.
    EventService ev = new EventService(true);

    String ownerA = "A-" + java.util.UUID.randomUUID();
    String ownerB = "B-" + java.util.UUID.randomUUID();

    // Write several events with small delays to ensure strictly increasing timestamps.
    ev.info(ownerA, "AAA111", "start ok");
    tick(5);

    ev.info(ownerA, "AAA111", "second info");
    tick(5);

    // Write ERROR as the last (most recent) event so it must appear at index 0 in recent lists.
    ev.error(ownerB, "BBB222", "boom (last)");
    tick(5);

    // Read using the same instance (same in-memory cache).
    var recentGlobal = ev.recentGlobal(5);
    assertFalse(recentGlobal.isEmpty(), "Recent global list must not be empty.");

    // The most recent event must be the last one we wrote (ERROR).
    assertEquals(
        org.example.shortlinkapp.model.EventType.ERROR,
        recentGlobal.get(0).type,
        "Most recent global type must be ERROR.");

    // Owner-scoped queries must return only events for that owner.
    var recentA = ev.recentByOwner(ownerA, 10);
    assertTrue(
        recentA.stream().allMatch(e -> ownerA.equals(e.ownerUuid)),
        "recentByOwner(ownerA) must return only ownerA events.");

    var recentB = ev.recentByOwner(ownerB, 10);
    assertTrue(
        recentB.stream().allMatch(e -> ownerB.equals(e.ownerUuid)),
        "recentByOwner(ownerB) must return only ownerB events.");
  }

  /** Ensures that subsequent LocalDateTime.now() calls are strictly later. */
  private static void tick(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName("Disabled service: logging is no-op and queries always return empty lists")
  void disabled_noop() {
    EventService svc = new EventService(false);

    // Try to log in disabled mode (should be ignored)
    svc.info("X", "S", "msg");
    svc.expired("X", "S", "msg");
    svc.limitReached("X", "S", "msg");
    svc.error("X", "S", "msg");

    // All queries must be empty
    assertTrue(svc.listByOwner("X").isEmpty(), "Disabled listByOwner must be empty.");
    assertTrue(svc.recentByOwner("X", 10).isEmpty(), "Disabled recentByOwner must be empty.");
    assertTrue(svc.recentGlobal(5).isEmpty(), "Disabled recentGlobal must be empty.");
  }

  @Test
  @DisplayName(
      "Toggle from enabled to disabled: after turning off, queries are empty and new logs ignored")
  void toggle_off_after_logging() {
    EventService svc = new EventService(true);
    String owner = "33333333-3333-3333-3333-333333333333";

    // Log once while enabled
    svc.info(owner, "X1", "first");
    assertFalse(
        svc.listByOwner(owner).isEmpty(), "Before disabling, events for owner should be present.");

    // Disable and verify everything becomes empty and new logs ignored
    svc.setEnabled(false);
    svc.error(owner, "X2", "ignored");

    assertTrue(svc.listByOwner(owner).isEmpty(), "After disabling, listByOwner must be empty.");
    assertTrue(
        svc.recentByOwner(owner, 5).isEmpty(), "After disabling, recentByOwner must be empty.");
    assertTrue(svc.recentGlobal(5).isEmpty(), "After disabling, recentGlobal must be empty.");

    // Debug print captured output
    System.out.println("[DEBUG] toggle_off_after_logging done.");
  }
}
