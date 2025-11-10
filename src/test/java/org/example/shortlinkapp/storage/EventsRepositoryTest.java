package org.example.shortlinkapp.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import org.example.shortlinkapp.model.EventLog;
import org.example.shortlinkapp.model.EventType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link EventsRepository}.
 *
 * <p><b>Isolation:</b> Each test runs with a fresh {@code @TempDir}. We override {@code user.dir}
 * to point into that temp directory, so that {@code data/events.json} is created inside the sandbox
 * and cannot be polluted by other tests.
 *
 * <p><b>Covered:</b>
 *
 * <ul>
 *   <li>First run creates {@code data/events.json} and returns empty list
 *   <li>{@code add()} persists events; reopening repository sees the same data
 *   <li>{@code list()} returns a defensive copy
 *   <li>{@code listByOwner(owner)} filters correctly
 * </ul>
 */
public class EventsRepositoryTest {

  @TempDir Path tempDir;

  private String originalUserDir;
  private PrintStream originalErr;
  private ByteArrayOutputStream errCapture;

  @BeforeEach
  void setUp() throws Exception {
    // point user.dir to a fresh sandbox for this test
    originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

    // ensure "data" dir exists to match repository behavior that expects parent dirs
    Files.createDirectories(DataPaths.DATA_DIR);

    // silence stderr noise from repository flush warnings (we still capture it for debugging)
    originalErr = System.err;
    errCapture = new ByteArrayOutputStream();
    System.setErr(new PrintStream(errCapture, true, StandardCharsets.UTF_8));

    // extra safety: if someone left a stale file (shouldn't happen with @TempDir), remove it
    if (Files.exists(DataPaths.EVENTS_JSON)) {
      Files.delete(DataPaths.EVENTS_JSON);
    }
  }

  @AfterEach
  void tearDown() {
    System.setProperty("user.dir", originalUserDir);
    System.setErr(originalErr);
  }

  private static EventLog ev(String owner, String code, EventType type, String msg) {
    EventLog e = new EventLog();
    e.ts = LocalDateTime.now();
    e.type = type;
    e.ownerUuid = owner;
    e.shortCode = code;
    e.message = msg;
    return e;
  }

  /**
   * First run should:
   *
   * <ol>
   *   <li>not find existing {@code data/events.json} before repository construction,
   *   <li>create it on first use,
   *   <li>expose an empty list of events.
   * </ol>
   */
  @Test
  @DisplayName("First run: repository creates data/events.json and returns empty list")
  void firstRun_createsFile_and_isEmpty() {
    Path file = DataPaths.EVENTS_JSON;

    // before constructing repository, the file must not exist inside fresh tempDir
    assertFalse(Files.exists(file), "events.json must NOT exist before repository is created.");

    EventsRepository repo = new EventsRepository();

    // after construction, the file should exist (JsonRepository.readOrDefault writes default)
    assertTrue(Files.exists(file), "events.json must be created on first repository use.");

    // and initial state is empty
    assertTrue(repo.list().isEmpty(), "Initial repository state must be empty.");
  }

  /** Adding events must persist them. A new repository instance should load the same events. */
  @Test
  @DisplayName("add() persists events; new repository instance loads them")
  void add_and_persist() {
    String A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    EventsRepository repo1 = new EventsRepository();
    repo1.add(ev(A, "AA11", EventType.INFO, "A1"));
    repo1.add(ev(B, "BB22", EventType.ERROR, "B1"));

    assertEquals(2, repo1.list().size(), "Repository must contain two events after add().");

    // Reopen: must see the same 2 events
    EventsRepository repo2 = new EventsRepository();
    assertEquals(2, repo2.list().size(), "New instance must load previously added events.");

    // Filter by owner
    assertEquals(1, repo2.listByOwner(A).size(), "Owner A must have exactly one event.");
    assertEquals(1, repo2.listByOwner(B).size(), "Owner B must have exactly one event.");
  }

  /**
   * {@link EventsRepository#list()} must return a defensive copy: modifying the returned list must
   * not affect repository's internal state.
   */
  @Test
  @DisplayName(
      "list() returns a defensive copy (modifying result must not affect repository state)")
  void list_returns_defensive_copy() {
    EventsRepository repo = new EventsRepository();
    // seed exactly one event
    repo.add(ev("11111111-1111-1111-1111-111111111111", "X1", EventType.INFO, "hello"));

    assertEquals(1, repo.list().size(), "Repository should contain exactly one event initially.");

    // mutate returned list
    var ext = repo.list();
    assertEquals(1, ext.size(), "External list should start with size=1.");
    ext.clear(); // should not affect repo

    // internal state must remain intact
    assertEquals(
        1,
        repo.list().size(),
        "Repository internal state must not be affected by client list mutations.");
  }

  /**
   * {@link EventsRepository#listByOwner(String)} must return only entries with the given owner,
   * using a new list instance each time (no aliasing).
   */
  @Test
  @DisplayName("listByOwner() filters by owner and returns a fresh list")
  void listByOwner_filters_and_is_fresh() {
    String ownerX = "22222222-2222-2222-2222-222222222222";
    String ownerY = "33333333-3333-3333-3333-333333333333";

    EventsRepository repo = new EventsRepository();
    repo.add(ev(ownerX, "X1", EventType.INFO, "x-1"));
    repo.add(ev(ownerY, "Y1", EventType.ERROR, "y-1"));
    repo.add(ev(ownerX, "X2", EventType.LIMIT_REACHED, "x-2"));

    var xs = repo.listByOwner(ownerX);
    var ys = repo.listByOwner(ownerY);

    assertEquals(2, xs.size(), "Owner X must have 2 events.");
    assertEquals(1, ys.size(), "Owner Y must have 1 event.");

    // ensure fresh list (no aliasing)
    xs.clear();
    assertEquals(
        2,
        repo.listByOwner(ownerX).size(),
        "Clearing client list must not affect repository state.");
  }
}
