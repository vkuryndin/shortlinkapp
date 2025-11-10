package org.example.shortlinkapp.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Robust tests for {@link InputUtils} that run the calls in a forked JVM process.
 *
 * <p>Why fork? {@link InputUtils} holds a private static final {@link java.util.Scanner} that binds
 * to {@code System.in} at class-load time. In a regular unit test suite, other tests might have
 * already touched/initialized the class with a different {@code System.in}, causing flakiness.
 * Forking guarantees a fresh JVM where we fully control {@code System.in}.
 *
 * <p>The helper runner (inner public static class with a {@code main}) invokes {@link
 * InputUtils#readTrimmed(String)} three times and prints the observed values in a stable format so
 * we can assert them from the parent test process.
 */
public class InputUtilsTest {

  @TempDir Path tempDir;

  // ---------- Helper runner executed in a separate JVM ----------

  /**
   * A tiny main-program that calls InputUtils.readTrimmed() three times and prints normalized
   * results to stdout. It's compiled into test-classes and available on the test classpath.
   */
  public static class Runner {
    public static void main(String[] args) {
      String v1 = InputUtils.readTrimmed("P1: ");
      System.out.println("P1_DONE");
      String v2 = InputUtils.readTrimmed("P2: ");
      System.out.println("P2_DONE");
      String v3 = InputUtils.readTrimmed("P3: ");
      System.out.println("P3_DONE");

      System.out.println("VAL1=" + String.valueOf(v1));
      System.out.println("VAL2=" + String.valueOf(v2));
      System.out.println("VAL3=" + String.valueOf(v3));
    }
  }

  // ---------- Tests ----------

  @Test
  @DisplayName("readTrimmed: trims spaces, returns empty for blank line, returns null on EOF")
  void readTrimmed_variousCases() throws Exception {
    // Prepare stdin content:
    // 1) "   hello  "  -> should be trimmed to "hello"
    // 2) "" (blank)    -> should become "" (empty string)
    // 3) EOF           -> should yield null
    String input = "   hello  \n\n"; // two lines only; third call hits EOF

    Process proc = launchForkedRunner(input);

    String stdout = readAll(proc.getInputStream());
    String stderr = readAll(proc.getErrorStream());

    int code = proc.waitFor();
    if (code != 0) {
      fail("Runner exited with code " + code + "\nSTDOUT:\n" + stdout + "\nSTDERR:\n" + stderr);
    }

    // Prompts are printed by InputUtils; markers by the Runner.
    assertTrue(stdout.contains("P1: "), "Prompt P1 must be shown.");
    assertTrue(stdout.contains("P2: "), "Prompt P2 must be shown.");
    assertTrue(stdout.contains("P3: "), "Prompt P3 must be shown.");
    assertTrue(stdout.contains("P1_DONE"), "Marker P1_DONE must be printed.");
    assertTrue(stdout.contains("P2_DONE"), "Marker P2_DONE must be printed.");
    assertTrue(stdout.contains("P3_DONE"), "Marker P3_DONE must be printed.");

    // Validate returned values
    assertTrue(stdout.contains("VAL1=hello"), "First value should be trimmed to 'hello'.");
    // For a blank line, InputUtils returns "" (empty string), not null
    assertTrue(stdout.contains("VAL2="), "Second value should be an empty string.");
    // On EOF, InputUtils returns null
    assertTrue(stdout.contains("VAL3=null"), "Third value should be null due to EOF.");
  }

  @Test
  @DisplayName("readTrimmed: returns null immediately when input is already closed (EOF)")
  void readTrimmed_immediateEOF() throws Exception {
    // No bytes -> immediate EOF before the first prompt is consumed
    String input = "";

    Process proc = launchForkedRunner(input);

    String stdout = readAll(proc.getInputStream());
    String stderr = readAll(proc.getErrorStream());

    int code = proc.waitFor();
    if (code != 0) {
      fail("Runner exited with code " + code + "\nSTDOUT:\n" + stdout + "\nSTDERR:\n" + stderr);
    }

    // Even with EOF, prompts are still printed before Scanner.nextLine() throws
    assertTrue(stdout.contains("P1: "), "Prompt P1 should still be printed.");
    assertTrue(stdout.contains("VAL1=null"), "On immediate EOF the first value must be null.");
  }

  // ---------- Forking utilities ----------

  private Process launchForkedRunner(String stdinContent) throws IOException {
    String javaBin = getJavaBin();
    String cp = System.getProperty("java.class.path");
    String mainClass = this.getClass().getName() + "$Runner"; // nested class with main

    ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", cp, mainClass);
    pb.redirectErrorStream(false);
    Process p = pb.start();

    // Feed stdin to the child process (UTF-8)
    try (OutputStream os = p.getOutputStream()) {
      os.write(stdinContent.getBytes(StandardCharsets.UTF_8));
      os.flush();
    }
    return p;
  }

  private static String getJavaBin() {
    String javaHome = System.getProperty("java.home");
    File java = new File(javaHome, "bin" + File.separator + "java");
    if (System.getProperty("os.name").toLowerCase().contains("win")) {
      File javaExe = new File(java.getPath() + ".exe");
      if (javaExe.exists()) return javaExe.getAbsolutePath();
    }
    return java.getAbsolutePath();
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
}
