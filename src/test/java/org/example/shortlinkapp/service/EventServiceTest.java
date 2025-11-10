package org.example.shortlinkapp.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.example.shortlinkapp.model.EventLog;
import org.example.shortlinkapp.model.EventType;
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
    EventService svc = new EventService(true);

    String ownerA = "11111111-1111-1111-1111-111111111111";
    String ownerB = "22222222-2222-2222-2222-222222222222";

    // Log 4 events for A (in order), 2 for B
    svc.info(ownerA, "AAA111", "create A1");
    svc.expired(ownerA, "AAA111", "expired A1");
    svc.limitReached(ownerA, "AAA222", "limit A2");
    svc.error(ownerA, null, "error A");

    svc.info(ownerB, "BBB111", "create B1");
    svc.error(ownerB, "BBB222", "error B2");

    var listA = svc.listByOwner(ownerA);
    var listB = svc.listByOwner(ownerB);

    // Debug sizes to console
    System.out.println("[DEBUG] listByOwner(A).size=" + listA.size());
    System.out.println("[DEBUG] listByOwner(B).size=" + listB.size());

    assertEquals(4, listA.size(), "Owner A should have 4 events.");
    assertEquals(2, listB.size(), "Owner B should have 2 events.");

    for (EventLog e : listA) {
      assertEquals(ownerA, e.ownerUuid, "listByOwner must return only events of that owner.");
      assertNotNull(e.ts, "Each event must have a timestamp.");
      assertNotNull(e.type, "Each event must have a type.");
    }

    var recentA2 = svc.recentByOwner(ownerA, 2);
    System.out.println("[DEBUG] recentByOwner(A,2).size=" + recentA2.size());
    assertEquals(2, recentA2.size(), "Limit must be applied for recentByOwner.");

    // The most recent for owner A should be the last one we appended for A: ERROR
    assertEquals(
        EventType.ERROR,
        recentA2.get(0).type,
        "Most recent owner A event should be ERROR (ordering by ts desc).");

    var recent3 = svc.recentGlobal(3);
    System.out.println("[DEBUG] recentGlobal(3).size=" + recent3.size());
    assertEquals(3, recent3.size(), "Global limit must be applied.");
    // The very last logged overall was error for owner B
    assertEquals(ownerB, recent3.get(0).ownerUuid, "Most recent global must be from owner B.");
    assertEquals(EventType.ERROR, recent3.get(0).type, "Most recent global type must be ERROR.");
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
