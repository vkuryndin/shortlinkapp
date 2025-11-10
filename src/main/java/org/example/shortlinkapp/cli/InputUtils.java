package org.example.shortlinkapp.cli;

import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Scanner;

public final class InputUtils {

  private static final Scanner SC = new Scanner(System.in, StandardCharsets.UTF_8.name());

  private InputUtils() {}

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
