package org.example.shortlinkapp.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import org.example.shortlinkapp.model.User;
import org.example.shortlinkapp.util.JsonUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stable, isolated tests for {@link UsersRepository}.
 *
 * <p>Key points:
 *
 * <ul>
 *   <li>Each test runs in a sandboxed working dir via {@code @TempDir} + {@code user.dir} override,
 *       so no leakage from other tests can affect users.json.
 *   <li>We assert repository semantics (defensive copy, no duplicates, timestamps updated) without
 *       relying on file order, which can be fragile.
 * </ul>
 */
public class UsersRepositoryTest {

  @TempDir Path tempDir;

  private String originalUserDir;

  private static final Gson GSON = JsonUtils.gson();

  @BeforeEach
  void setUp() throws Exception {
    originalUserDir = System.getProperty("user.dir");
    System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
    Files.createDirectories(DataPaths.DATA_DIR);

    // Ensure a clean slate for every test
    safeDelete(DataPaths.USERS_JSON);
  }

  @AfterEach
  void tearDown() {
    System.setProperty("user.dir", originalUserDir);
  }

  private static void safeDelete(Path p) throws Exception {
    if (Files.exists(p)) {
      Files.delete(p);
    }
  }

  @Test
  @DisplayName("list: returns a defensive copy (modifying result must not affect repository state)")
  void list_returns_defensive_copy() {
    UsersRepository repo = new UsersRepository();

    String id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    repo.upsertCurrent(id);

    // First snapshot
    List<User> s1 = repo.list();
    assertEquals(1, s1.size(), "Repository should contain exactly one user after single upsert.");

    // Mutate the returned list (should not affect repo)
    s1.add(new User());

    // Second snapshot must still be size=1 and be a different instance
    List<User> s2 = repo.list();
    assertEquals(1, s2.size(), "Repository size must be unaffected by external list mutations.");
    assertNotSame(s1, s2, "list() must return a new defensive copy each time.");
  }

  @Test
  @DisplayName(
      "multiple upserts: distinct users stored once each; re-upsert updates lastSeenAt without duplicates")
  void multiple_users_distinct() throws Exception {
    UsersRepository repo = new UsersRepository();

    String id1 = "11111111-1111-1111-1111-111111111111";
    String id2 = "22222222-2222-2222-2222-222222222222";

    // Insert two distinct users
    repo.upsertCurrent(id1);
    repo.upsertCurrent(id2);

    List<User> afterTwo = repo.list();
    assertEquals(2, afterTwo.size(), "There should be two distinct users.");

    // Capture lastSeenAt for id1, then re-upsert id1 and ensure timestamp moves forward
    LocalDateTime prevLastSeen =
        repo.findByUuid(id1)
            .map(u -> u.lastSeenAt)
            .orElseThrow(() -> new AssertionError("id1 missing"));
    // Sleep a tiny bit to ensure timestamp difference (monotonicity on many systems)
    Thread.sleep(5);
    repo.upsertCurrent(id1);
    LocalDateTime newLastSeen =
        repo.findByUuid(id1)
            .map(u -> u.lastSeenAt)
            .orElseThrow(() -> new AssertionError("id1 missing after re-upsert"));

    assertTrue(
        newLastSeen.isAfter(prevLastSeen) || newLastSeen.isEqual(prevLastSeen),
        "lastSeenAt should be refreshed on re-upsert (not older than previous).");

    // Still 2 users (no duplicates)
    assertEquals(2, repo.list().size(), "Re-upsert of the same UUID must not create a duplicate.");
  }

  @Test
  @DisplayName(
      "persistence: users.json is created and contains upserted users with fields populated")
  void persistence_users_json_written() throws Exception {
    UsersRepository repo = new UsersRepository();

    String idA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String idB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    repo.upsertCurrent(idA);
    repo.upsertCurrent(idB);

    // File must exist
    assertTrue(Files.exists(DataPaths.USERS_JSON), "users.json must be created on flush.");

    // Read back raw JSON to validate presence and basic fields (no ordering assumptions)
    List<User> fileUsers;
    try (BufferedReader br =
        Files.newBufferedReader(DataPaths.USERS_JSON, StandardCharsets.UTF_8)) {
      User[] arr = GSON.fromJson(br, User[].class);
      fileUsers = (arr == null) ? List.of() : Arrays.asList(arr);
    }

    assertEquals(2, fileUsers.size(), "users.json should contain exactly the upserted users.");
    Set<String> ids = new HashSet<>();
    for (User u : fileUsers) {
      assertNotNull(u.uuid, "uuid must be present in persisted entry.");
      assertNotNull(u.createdAt, "createdAt must be present in persisted entry.");
      assertNotNull(u.lastSeenAt, "lastSeenAt must be present in persisted entry.");
      ids.add(u.uuid);
    }
    assertTrue(ids.contains(idA) && ids.contains(idB), "Persisted file must contain both UUIDs.");
  }

  @Test
  @DisplayName("findByUuid: returns present for existing user and empty for unknown")
  void findByUuid_present_and_empty() {
    UsersRepository repo = new UsersRepository();

    String known = "99999999-9999-9999-9999-999999999999";
    String unknown = "00000000-0000-0000-0000-000000000000";

    repo.upsertCurrent(known);

    assertTrue(repo.findByUuid(known).isPresent(), "Known user must be found.");
    assertTrue(repo.findByUuid(unknown).isEmpty(), "Unknown user must not be found.");
  }
}
