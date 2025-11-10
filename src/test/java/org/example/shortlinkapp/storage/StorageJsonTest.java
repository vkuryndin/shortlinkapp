package org.example.shortlinkapp.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link StorageJson#writeAtomic(Path, List, Class)}.
 *
 * <p>Focus:
 *
 * <ul>
 *   <li>Creates parent directories and writes JSON
 *   <li>Overwrites existing file "atomically" and leaves no temp file
 *   <li>Throws {@link IOException} when target has no parent (by design)
 *   <li>Throws {@link NullPointerException} on null arguments
 *   <li>Removes temp file (.<basename>.tmp) after successful write
 * </ul>
 */
public class StorageJsonTest {

  @TempDir Path tmp;

  /** Simple DTO for writing JSON lists. */
  public static class Item {
    public int id;
    public String name;

    public Item() {}

    public Item(int id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  @Test
  @DisplayName("Creates parent directories and writes JSON content")
  void write_createsParent_and_writesJson() throws Exception {
    Path target = tmp.resolve("nested/dir/items.json");

    List<Item> data = new ArrayList<>();
    data.add(new Item(1, "alpha"));
    data.add(new Item(2, "beta"));

    // parent "nested/dir" doesn't exist yet
    assertFalse(Files.exists(target.getParent()), "Parent directory must not exist before write.");

    StorageJson.writeAtomic(target, data, Item.class);

    assertTrue(Files.exists(target), "Target file must be created.");
    assertTrue(Files.size(target) > 0, "Target file must be non-empty.");

    String text = Files.readString(target, StandardCharsets.UTF_8);
    assertTrue(text.contains("\"id\""), "JSON must contain field names.");
    assertTrue(
        text.contains("alpha") && text.contains("beta"), "JSON must contain written values.");
  }

  @Test
  @DisplayName("Overwrites existing file atomically and leaves no temp file behind")
  void write_overwrites_atomically_and_no_tmp_left() throws Exception {
    Path parent = tmp.resolve("data");
    Files.createDirectories(parent);

    Path target = parent.resolve("items.json");

    // initial content
    List<Item> first = List.of(new Item(10, "first"));
    StorageJson.writeAtomic(target, first, Item.class);

    String before = Files.readString(target, StandardCharsets.UTF_8);
    assertTrue(before.contains("first"), "Initial file must contain 'first'.");

    // overwrite with new content
    List<Item> second = List.of(new Item(20, "second"), new Item(30, "third"));
    StorageJson.writeAtomic(target, second, Item.class);

    String after = Files.readString(target, StandardCharsets.UTF_8);
    assertFalse(after.contains("first"), "Old content must be fully replaced.");
    assertTrue(after.contains("second") && after.contains("third"), "New content must be present.");

    // temp file should be removed
    Path tmpFile = parent.resolve(".items.json.tmp");
    assertFalse(Files.exists(tmpFile), "Temporary file should be removed after successful move.");
  }

  @Test
  @DisplayName("Throws IOException when target has no parent directory (design behavior)")
  void write_throws_if_no_parent() {
    // getParent() returns null for a simple file name with no directory component
    Path target = Paths.get("items.json");
    List<Item> data = List.of(new Item(1, "x"));

    IOException ex =
        assertThrows(
            IOException.class,
            () -> StorageJson.writeAtomic(target, data, Item.class),
            "Expected IOException when parent is null.");
    assertTrue(
        ex.getMessage().toLowerCase().contains("no parent"),
        "Error message should mention missing parent directory.");
  }

  @Test
  @DisplayName("Null arguments: target, list, or type must throw NullPointerException")
  void write_throws_on_nulls() {
    Path p = tmp.resolve("a/b.json");
    List<Item> data = List.of(new Item(1, "x"));

    assertThrows(NullPointerException.class, () -> StorageJson.writeAtomic(null, data, Item.class));
    assertThrows(NullPointerException.class, () -> StorageJson.writeAtomic(p, null, Item.class));
    assertThrows(NullPointerException.class, () -> StorageJson.writeAtomic(p, data, null));
  }

  @Test
  @DisplayName("Temp file (.basename.tmp) is cleaned even after multiple writes")
  void tmp_removed_after_multiple_writes() throws Exception {
    Path parent = tmp.resolve("multi");
    Path target = parent.resolve("sample.json");

    // First write
    StorageJson.writeAtomic(target, List.of(new Item(1, "one")), Item.class);
    // Second write
    StorageJson.writeAtomic(target, List.of(new Item(2, "two")), Item.class);
    // Third write
    StorageJson.writeAtomic(target, List.of(new Item(3, "three")), Item.class);

    // File exists with the latest content
    assertTrue(Files.exists(target), "Target file must exist after multiple writes.");
    try (BufferedReader br = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
      String all = br.readLine() + ""; // read first line to ensure file is readable
      assertTrue(all.contains("{") || all.contains("["), "File should contain JSON.");
    }

    // No leftover temp file
    Path tmpFile = parent.resolve(".sample.json.tmp");
    assertFalse(Files.exists(tmpFile), "Temp file must not remain after multiple writes.");
  }
}
