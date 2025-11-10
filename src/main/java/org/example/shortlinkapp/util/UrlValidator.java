package org.example.shortlinkapp.util;

import java.net.URI;

/**
 * Utility for validating HTTP/HTTPS URLs used by the short-link service.
 *
 * <p>The validator is intentionally conservative: it only accepts URIs that (1) parse successfully,
 * (2) use the {@code http} or {@code https} scheme, and (3) have a non-empty host. Trailing/leading
 * whitespace is ignored. Extremely long inputs are rejected early via a length check.
 *
 * <p>This class is a static holder and is not meant to be instantiated.
 */
public final class UrlValidator {
  private UrlValidator() {}

  /**
   * Checks whether the given string is a syntactically valid HTTP/HTTPS URL.
   *
   * <p>Validation rules:
   *
   * <ul>
   *   <li>Input must be non-null, non-empty after {@code trim()}, and not exceed {@code maxLen}
   *       characters.
   *   <li>{@link URI#create(String)} must succeed (i.e., no {@link IllegalArgumentException}).
   *   <li>Scheme must be {@code http} or {@code https} (case-insensitive).
   *   <li>Host component must be present and non-blank.
   * </ul>
   *
   * <p>Note: This performs syntactic checks only; it does not verify reachability or DNS validity.
   *
   * @param url the candidate URL string
   * @param maxLen maximum allowed length for {@code url}; longer inputs are rejected
   * @return {@code true} if the input is a valid HTTP/HTTPS URL under the rules above; {@code
   *     false} otherwise
   */
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
