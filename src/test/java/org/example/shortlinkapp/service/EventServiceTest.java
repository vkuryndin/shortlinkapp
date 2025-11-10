package org.example.shortlinkapp.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.example.shortlinkapp.model.EventLog;
import org.example.shortlinkapp.model.EventType;
import org.example.shortlinkapp.storage.DataPaths;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Working, isolated tests for {@link EventService}.
 *
 * <p><b>Isolation:</b> We override {@code user.dir} to a {@code @TempDir} so repositories (which
 * use relative {@code data/} paths) read/write only inside the sandbox.
 *
 * <p><b>Covered:</b>
 *
 * <ul>
 *   <li>Logging ({@code info}, {@code expired}, {@code limitReached}, {@code error})
 *   <li>Queries: {@code listByOwner}, {@code recentByOwner(limit)}, {@code recentGlobal(limit)}
 *   <li>Disabled mode (no-op, empty lists)
 *   <li>Toggle from enabled → disabled (lists become empty, new logs ignored)
 * </ul>
 */
public class EventServiceTest {

  @TempDir Path tempDir;

  private String originalUserDir;
  private PrintStream originalErr;
  private ByteArrayOutputStream errCapture;

  @BeforeEach
  void setUp() throws Exception {
    // Isolate relative "data" directory
    originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    Files.createDirectories(DataPaths.DATA_DIR);

    // Silence stderr noise from repository flush failures (if any)
    originalErr = System.err;
    errCapture = new ByteArrayOutputStream();
    System.setErr(new PrintStream(errCapture, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void tearDown() {
    System.setProperty("user.dir", originalUserDir);
    System.setErr(originalErr);
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

    // listByOwner: exact match and size
    var listA = svc.listByOwner(ownerA);
    assertEquals(4, listA.size(), "Owner A should have 4 events.");
    for (EventLog e : listA) {
      assertEquals(
          ownerA, e.ownerUuid, "All events in listByOwner must match the requested owner.");
      assertNotNull(e.ts, "Every logged event must have a timestamp.");
      assertNotNull(e.type, "Every logged event must have a type.");
    }

    var listB = svc.listByOwner(ownerB);
    assertEquals(2, listB.size(), "Owner B should have 2 events.");

    // recentByOwner: order by ts desc (most recent first), limited by 'limit'
    var recentA2 = svc.recentByOwner(ownerA, 2);
    assertEquals(2, recentA2.size(), "Limit must be applied.");
    // The most recent for owner A is the last one we logged for A: error(A, null, "error A")
    assertEquals(
        EventType.ERROR, recentA2.get(0).type, "Most recent event should be ERROR for owner A.");

    // recentGlobal: mix of A and B, desc by ts, limited to 3
    var recent3 = svc.recentGlobal(3);
    assertEquals(3, recent3.size(), "Global limit must be applied.");
    // The very last log among all was error for owner B
    assertEquals(
        ownerB, recent3.get(0).ownerUuid, "The most recent global event should be from owner B.");
    assertEquals(
        EventType.ERROR, recent3.get(0).type, "The most recent global event type should be ERROR.");
  }

  @Test
  @DisplayName("Disabled service: logging is no-op and queries always return empty lists")
  void disabled_noop() {
    EventService svc = new EventService(false);

    // Attempt to log; nothing should be stored
    svc.info("X", "code", "msg");
    svc.expired("X", "code", "msg");
    svc.limitReached("X", "code", "msg");
    svc.error("X", "code", "msg");

    assertTrue(svc.listByOwner("X").isEmpty(), "Disabled service must return empty listByOwner.");
    assertTrue(
        svc.recentByOwner("X", 10).isEmpty(), "Disabled service must return empty recentByOwner.");
    assertTrue(svc.recentGlobal(5).isEmpty(), "Disabled service must return empty recentGlobal.");
  }

  @Test
  @DisplayName(
      "Toggle from enabled to disabled: after turning off, queries are empty and new logs ignored")
  void toggle_off_after_logging() {
    EventService svc = new EventService(true);
    String owner = "33333333-3333-3333-3333-333333333333";

    svc.info(owner, "X1", "first");
    assertFalse(svc.listByOwner(owner).isEmpty(), "Before disabling, events should be visible.");

    // Turn off logging/visibility
    svc.setEnabled(false);

    // Further logs ignored; all queries empty due to enabled=false guards
    svc.error(owner, "X2", "second (ignored)");
    assertTrue(svc.listByOwner(owner).isEmpty(), "After disabling, listByOwner must be empty.");
    assertTrue(
        svc.recentByOwner(owner, 5).isEmpty(), "After disabling, recentByOwner must be empty.");
    assertTrue(svc.recentGlobal(5).isEmpty(), "After disabling, recentGlobal must be empty.");
  }
}
