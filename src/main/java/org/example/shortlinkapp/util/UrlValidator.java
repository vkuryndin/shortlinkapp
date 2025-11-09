package org.example.shortlinkapp.util;

import java.net.URI;

public final class UrlValidator {
  private UrlValidator() {}

  public static boolean isValidHttpUrl(String url, int maxLen) {
    if (url == null) return false;
    url = url.trim();
    if (url.isEmpty() || url.length() > maxLen) return false;
    try {
      URI uri = URI.create(url);
      String scheme = uri.getScheme();
      if (scheme == null) return false;
      if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return false;
      String host = uri.getHost();
      return host != null && !host.isBlank();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
