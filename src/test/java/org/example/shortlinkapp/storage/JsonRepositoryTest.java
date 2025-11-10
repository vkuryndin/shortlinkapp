package org.example.shortlinkapp.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.example.shortlinkapp.util.JsonUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link JsonRepository} aligned with the current production behavior.
 *
 * <p><b>Important:</b> We DO NOT change production code assumptions.
 *
 * <ul>
 *   <li>If a file doesn't exist: {@code readOrDefault} creates it with default JSON and returns the
 *       default.
 *   <li>If JSON is invalid (malformed): {@code readOrDefault} throws {@link JsonSyntaxException}
 *       (only {@link java.io.IOException} is caught in production).
 *   <li>{@code writeAtomic} must atomically replace the file content.
 * </ul>
 *
 * <p>All tests are isolated under a per-test temporary directory via JUnit's {@link TempDir}.
 */
public class JsonRepositoryTest {

  @TempDir Path tempDir;

  private static final Gson GSON = JsonUtils.gson();

  // Simple DTO to avoid Map-number coercion (7 vs 7.0) in round-trip tests.
  static class DemoDTO {
    String name;
    int count;
    List<String> items;

    DemoDTO() {}

    DemoDTO(String name, int count, List<String> items) {
      this.name = name;
      this.count = count;
      this.items = items;
    }
  }

  private static final Type DEMO_TYPE = new TypeToken<List<DemoDTO>>() {}.getType();

  /** Utility: read file as string (UTF-8). */
  private static String readAll(Path p) throws Exception {
    return Files.readString(p, StandardCharsets.UTF_8);
  }

  /** Utility: write text (UTF-8). */
  private static void writeAll(Path file, String content) throws IOException {
    safeMkdirs(file);
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            file,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      bw.write(content);
      bw.write("\n");
    }
  }

  @Test
  @DisplayName("First run: readOrDefault() creates file with default JSON and returns default")
  void firstRun_createsFile_and_returnsDefault() throws Exception {
    Path target = tempDir.resolve("data").resolve("demo.json");
    assertFalse(Files.exists(target), "Target must not exist before readOrDefault().");

    // default value we expect to be written and returned
    List<DemoDTO> def = List.of(new DemoDTO("demo", 7, List.of("a", "b")));

    List<DemoDTO> result = JsonRepository.readOrDefault(target, DEMO_TYPE, def);

    assertNotNull(result, "readOrDefault must return non-null (the default).");
    assertEquals(1, result.size(), "Default list must contain one element.");
    assertTrue(Files.exists(target), "File must be created on first run.");

    // Ensure file contains the same structure (deserialize back to typed list)
    try (BufferedReader br = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
      List<DemoDTO> fromFile = GSON.fromJson(br, DEMO_TYPE);
      assertEquals(1, fromFile.size(), "File must contain one element.");
      assertEquals("demo", fromFile.get(0).name);
      assertEquals(7, fromFile.get(0).count);
      assertEquals(List.of("a", "b"), fromFile.get(0).items);
    }
  }

  @Test
  @DisplayName("Invalid JSON: readOrDefault() throws JsonSyntaxException (current behavior)")
  void readOrDefault_invalidJson_throws() throws Exception {
    Path target = tempDir.resolve("data").resolve("broken.json");
    // Intentionally malformed JSON (missing colon after key)
    writeAll(target, "{\"name\" \"oops\"}");

    List<DemoDTO> def = List.of(new DemoDTO("x", 1, List.of()));

    // With current production code, malformed JSON bubbles up as JsonSyntaxException.
    assertThrows(
        JsonSyntaxException.class, () -> JsonRepository.readOrDefault(target, DEMO_TYPE, def));
  }

  @Test
  @DisplayName("Roundtrip: writeAtomic() then readOrDefault() returns the same typed structure")
  void roundtrip_write_then_read() throws Exception {
    Path target = tempDir.resolve("data").resolve("round.json");

    List<DemoDTO> original =
        List.of(
            new DemoDTO("demo", 7, List.of("a", "b", "c")), new DemoDTO("second", 2, List.of()));

    // Write via writeAtomic
    JsonRepository.writeAtomic(target, original);

    // Read back via readOrDefault using the same Type
    List<DemoDTO> readBack = JsonRepository.readOrDefault(target, DEMO_TYPE, List.of());

    assertEquals(2, readBack.size(), "Roundtrip must preserve list size.");
    assertEquals("demo", readBack.get(0).name);
    assertEquals(7, readBack.get(0).count);
    assertEquals(List.of("a", "b", "c"), readBack.get(0).items);

    assertEquals("second", readBack.get(1).name);
    assertEquals(2, readBack.get(1).count);
    assertEquals(List.of(), readBack.get(1).items);
  }

  @Test
  @DisplayName("writeAtomic(): replaces existing file content atomically")
  void writeAtomic_overwrites_existing() throws Exception {
    Path target = tempDir.resolve("data").resolve("overwrite.json");
    safeMkdirs(target);

    // Seed with initial JSON
    List<DemoDTO> v1 = List.of(new DemoDTO("v1", 1, List.of("x")));
    JsonRepository.writeAtomic(target, v1);
    String first = readAll(target);
    assertTrue(first.contains("v1"), "Initial JSON should be written.");

    // Now overwrite with different JSON
    List<DemoDTO> v2 = List.of(new DemoDTO("v2", 999, List.of("y", "z")));
    JsonRepository.writeAtomic(target, v2);

    String second = readAll(target);
    assertTrue(second.contains("v2"), "New JSON should be present.");
    assertFalse(second.contains("v1"), "Old JSON content must be replaced.");
    assertTrue(second.contains("999"), "Updated numeric value must appear.");
  }

  @Test
  @DisplayName("readOrDefault(): existing well-formed JSON is returned as-is (no overwrite)")
  void readOrDefault_reads_existing_as_is() throws Exception {
    Path target = tempDir.resolve("data").resolve("existing.json");
    safeMkdirs(target);

    // Prepare existing content
    List<DemoDTO> existing = List.of(new DemoDTO("keep", 42, List.of("k1")));
    try (BufferedWriter bw =
        Files.newBufferedWriter(
            target,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      GSON.toJson(existing, bw);
    }

    // Provide a different default — it must NOT be used
    List<DemoDTO> def = List.of(new DemoDTO("default", 0, List.of()));

    List<DemoDTO> got = JsonRepository.readOrDefault(target, DEMO_TYPE, def);

    assertEquals(1, got.size(), "Must read existing JSON, not use default.");
    assertEquals("keep", got.get(0).name);
    assertEquals(42, got.get(0).count);
    assertEquals(List.of("k1"), got.get(0).items);
  }

  /**
   * Ensures directory exists if and only if the path has a parent. Avoids NP warnings for
   * getParent() used on root-like paths (spotbugs).
   */
  private static void safeMkdirs(Path file) throws IOException {
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
  }
}
