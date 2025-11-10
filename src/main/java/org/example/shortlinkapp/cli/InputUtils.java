package org.example.shortlinkapp.cli;

import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Scanner;

/**
 * Minimal console input helper used by the CLI.
 *
 * <p>This utility wraps a single {@link Scanner} over {@code System.in} (UTF-8) and provides a
 * single convenience method {@link #readTrimmed(String)} that:
 *
 * <ul>
 *   <li>Prints a prompt (without a newline),
 *   <li>Reads a line from standard input,
 *   <li>Trims leading/trailing whitespace,
 *   <li>Returns {@code null} when the input stream is closed, the scanner is closed, or a {@link
 *       NoSuchElementException} occurs (EOF).
 * </ul>
 *
 * <p><strong>Notes:</strong>
 *
 * <ul>
 *   <li>This class is not intended for unit tests that need to inject custom {@code InputStream}s.
 *       Tests should capture/redirect {@code System.in} or use higher-level abstractions.
 *   <li>The scanner is static and created once with UTF-8 charset to ensure consistent behavior
 *       across platforms.
 * </ul>
 */
public final class InputUtils {

  /** Shared UTF-8 {@link Scanner} over {@code System.in}. */
  private static final Scanner SC = new Scanner(System.in, StandardCharsets.UTF_8);

  /** Utility class; no instances. */
  private InputUtils() {}

  /**
   * Prints the given prompt and reads a single line from standard input.
   *
   * <p>Behavior:
   *
   * <ul>
   *   <li>Returns the trimmed line when available.
   *   <li>Returns {@code null} if the input stream is closed or an error occurs while reading
   *       (e.g., {@link IllegalStateException} or {@link NoSuchElementException}).
   * </ul>
   *
   * @param prompt a prompt to write before reading (printed as-is, without newline)
   * @return trimmed input line, or {@code null} if EOF/closed/error
   */
  public static String readTrimmed(String prompt) {
    System.out.print(prompt);
    try {
      String s = SC.nextLine();
      return s == null ? null : s.trim();
    } catch (IllegalStateException | NoSuchElementException e) {
      return null; // input stream closed
    }
  }
}
