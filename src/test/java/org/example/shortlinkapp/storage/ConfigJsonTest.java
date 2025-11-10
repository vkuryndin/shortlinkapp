package org.example.shortlinkapp.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stable, process-forked tests for {@link ConfigJson}.
 *
 * <p>Why fork a JVM? {@code ConfigJson} keeps a static final {@code CONFIG_PATH} computed at
 * class-load time based on the current {@code user.dir}. If another test class has already loaded
 * {@code ConfigJson} with a different {@code user.dir}, assertions here may become flaky. Forking
 * guarantees fresh class loading with the desired working directory.
 *
 * <p>All console comments are English (per project rule); explanation text to the user is in
 * Russian.
 */
public class ConfigJsonTest {

  @TempDir Path tempDir;

  // -------------- Helpers to fork a JVM with given working dir --------------

  private static String javaBin() {
    String javaHome = System.getProperty("java.home");
    Path java = Paths.get(javaHome, "bin", "java");
    if (System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT).contains("win")) {
      Path win = Paths.get(java.toString() + ".exe");
      if (Files.exists(win)) return win.toString();
    }
    return java.toString();
  }

  private static String readAll(InputStream is) throws IOException {
    try (BufferedReader br =
        new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line).append('\n');
      }
      return sb.toString();
    }
  }

  private Process runFork(Path workDir, Class<?> mainClass, String... args) throws IOException {
    String cp = System.getProperty("java.class.path");
    ProcessBuilder pb =
        new ProcessBuilder(javaBin(), "-cp", cp, mainClass.getName()).directory(workDir.toFile());
    if (args != null && args.length > 0) {
      pb.command().addAll(java.util.Arrays.asList(args));
    }
    pb.redirectErrorStream(false);
    return pb.start();
  }

  // -------------- Scenario runners (executed in the forked JVM) --------------

  /**
   * Runner for: create default config when file is missing.
   *
   * <p>It prints:
   *
   * <ul>
   *   <li>DIR_EXISTS=true/false
   *   <li>FILE_EXISTS=true/false
   *   <li>BASEURL=...
   *   <li>CONFIG_PATH=...
   * </ul>
   */
  /** Mini runner used by tests to verify default config creation behavior. */
  public static class RunnerCreateDefault {
    public static void main(String[] args) {
      try {
        // Load (and create default file if missing)
        ConfigJson cfg = ConfigJson.loadOrCreateDefault();

        // Null-safe parent handling to satisfy SpotBugs
        Path cfgPath = ConfigJson.getConfigPath(); // usually data/config.json
        Path parent = (cfgPath == null) ? null : cfgPath.getParent();

        // Evaluate file system state safely
        boolean dirExists = (parent != null) && java.nio.file.Files.isDirectory(parent);
        boolean fileExists = (cfgPath != null) && java.nio.file.Files.exists(cfgPath);

        // Print simple key=value lines for the test harness
        System.out.println("DIR_EXISTS=" + dirExists);
        System.out.println("FILE_EXISTS=" + fileExists);
        System.out.println("BASEURL=" + (cfg == null ? "null" : cfg.baseUrl));
        System.out.println(
            "CONFIG_PATH=" + (cfgPath == null ? "null" : cfgPath.toAbsolutePath().toString()));

        // Optional: a tiny peek to help diagnose content issues (harmless if missing)
        if (fileExists) {
          try (java.io.BufferedReader br =
              java.nio.file.Files.newBufferedReader(
                  cfgPath, java.nio.charset.StandardCharsets.UTF_8)) {
            String first = br.readLine();
            System.out.println("FIRST_LINE=" + (first == null ? "" : first));
          } catch (java.io.IOException ignore) {
            // The runner should never fail just because we couldn't read the first line
          }
        }
      } catch (Throwable t) {
        t.printStackTrace(System.err);
        System.exit(1);
      }
    }
  }

  /**
   * Runner for: read existing config values (do not overwrite).
   *
   * <p>It prints:
   *
   * <ul>
   *   <li>BASEURL=...
   *   <li>DEFAULT_LIMIT_NULL=true/false
   * </ul>
   */
  public static class RunnerReadExisting {
    public static void main(String[] args) {
      try {
        // Pre-create data/config.json with custom values.
        Path p = ConfigJson.getConfigPath(); // "data/config.json"
        Path parent = p.getParent();
        if (parent != null) Files.createDirectories(parent);

        // Write a config with baseUrl http://x/ and defaultClickLimit:null
        String json =
            "{\n"
                + "  \"baseUrl\": \"http://x/\",\n"
                + "  \"shortCodeLength\": 7,\n"
                + "  \"defaultTtlHours\": 24,\n"
                + "  \"defaultClickLimit\": null,\n"
                + "  \"maxUrlLength\": 2048,\n"
                + "  \"cleanupOnEachOp\": true,\n"
                + "  \"allowOwnerEditLimit\": true,\n"
                + "  \"hardDeleteExpired\": true,\n"
                + "  \"eventsLogEnabled\": true,\n"
                + "  \"clockSkewToleranceSec\": 2\n"
                + "}\n";
        try (BufferedWriter bw =
            Files.newBufferedWriter(
                p,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
          bw.write(json);
        }

        ConfigJson cfg = ConfigJson.loadOrCreateDefault();
        System.out.println("BASEURL=" + cfg.baseUrl);
        System.out.println("DEFAULT_LIMIT_NULL=" + (cfg.defaultClickLimit == null));
      } catch (Throwable t) {
        t.printStackTrace(System.err);
        System.exit(1);
      }
    }
  }

  /**
   * Runner for: getConfigPath() behavior relative to the current working directory (user.dir).
   *
   * <p>It prints:
   *
   * <ul>
   *   <li>PATH_STR=data/config.json
   *   <li>ABSOLUTE=false
   *   <li>RESOLVED=...absolute path under working dir...
   * </ul>
   */
  public static class RunnerPathBehavior {
    public static void main(String[] args) {
      try {
        Path rel = ConfigJson.getConfigPath();
        System.out.println("PATH_STR=" + rel.toString());
        System.out.println("ABSOLUTE=" + rel.isAbsolute());

        // Resolve manually against current user.dir to show where it points.
        Path resolved = Paths.get("").toAbsolutePath().resolve(rel).normalize();
        System.out.println("RESOLVED=" + resolved.toString());
      } catch (Throwable t) {
        t.printStackTrace(System.err);
        System.exit(1);
      }
    }
  }

  // -------------- Actual tests (assert based on runner output) --------------

  @Test
  @DisplayName(
      "When file is missing: loadOrCreateDefault() creates data/config.json and returns defaults")
  void creates_default_file_and_returns_defaults() throws Exception {
    // Fresh working dir (no "data" present yet in this folder)
    Process p = runFork(tempDir, RunnerCreateDefault.class);
    String out = readAll(p.getInputStream());
    String err = readAll(p.getErrorStream());
    int code = p.waitFor();
    if (code != 0) {
      fail(
          "RunnerCreateDefault failed with code "
              + code
              + "\nSTDOUT:\n"
              + out
              + "\nSTDERR:\n"
              + err);
    }

    // Expect directory and file creation; baseUrl stays at default "cli://"
    assertTrue(out.contains("DIR_EXISTS=true"), "Parent directory 'data' must be created.");
    assertTrue(out.contains("FILE_EXISTS=true"), "config.json must be created.");
    assertTrue(out.contains("BASEURL=cli://"), "Default baseUrl should be 'cli://'.");
  }

  @Test
  @DisplayName("When file exists: loadOrCreateDefault() reads existing JSON values (no overwrite)")
  void reads_existing_config_values() throws Exception {
    // Prepare working dir and precreate file INSIDE the forked JVM
    Process p = runFork(tempDir, RunnerReadExisting.class);
    String out = readAll(p.getInputStream());
    String err = readAll(p.getErrorStream());
    int code = p.waitFor();
    if (code != 0) {
      fail(
          "RunnerReadExisting failed with code "
              + code
              + "\nSTDOUT:\n"
              + out
              + "\nSTDERR:\n"
              + err);
    }

    // Must reflect our custom values (baseUrl=http://x/, defaultClickLimit=null)
    assertTrue(out.contains("BASEURL=http://x/"), "baseUrl from existing JSON must be preserved.");
    assertTrue(
        out.contains("DEFAULT_LIMIT_NULL=true"),
        "We wrote null -> unlimited; it must remain null when read.");
  }

  @Test
  @DisplayName(
      "getConfigPath() points under current working dir: resolve to <tempDir>/data/config.json")
  void getConfigPath_points_inside_userdir() throws Exception {
    Process p = runFork(tempDir, RunnerPathBehavior.class);
    String out = readAll(p.getInputStream());
    String err = readAll(p.getErrorStream());
    int code = p.waitFor();
    if (code != 0) {
      fail(
          "RunnerPathBehavior failed with code "
              + code
              + "\nSTDOUT:\n"
              + out
              + "\nSTDERR:\n"
              + err);
    }

    // In current implementation getConfigPath() returns a RELATIVE path "data/config.json".
    assertTrue(
        out.contains("PATH_STR=data\\config.json") || out.contains("PATH_STR=data/config.json"),
        "getConfigPath() should be 'data/config.json' (relative).");
    assertTrue(out.contains("ABSOLUTE=false"), "Path should be relative (not absolute).");

    // But RESOLVED must point exactly under our tempDir/data/config.json
    Path expected = tempDir.resolve("data").resolve("config.json").toAbsolutePath().normalize();
    assertTrue(
        out.contains("RESOLVED=" + expected.toString()),
        "Resolved path must be inside tempDir: " + expected);
  }
}
