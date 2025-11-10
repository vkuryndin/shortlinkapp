package org.example.shortlinkapp.util;

import java.time.LocalDateTime;

/**
 * Time-related helper methods used by the ShortlinkApp.
 *
 * <p>This utility currently provides a single semantic check for link expiration:
 * whether the current moment is <b>after or exactly equal to</b> the expiration timestamp.
 *
 * <p><b>Design notes:</b>
 * <ul>
 *   <li>The class is {@code final} and has a private constructor to prevent instantiation.</li>
 *   <li>All members are static and stateless.</li>
 * </ul>
 */
public final class TimeUtils {
    private TimeUtils() {}

    /**
     * Returns {@code true} if the {@code now} instant is the same as or later than
     * the {@code expiresAt} instant.
     *
     * <p>This implements an <em>inclusive</em> expiration rule:
     * a link is considered expired at the exact moment of {@code expiresAt}.
     *
     * @param now the current timestamp to compare (must not be {@code null})
     * @param expiresAt the expiration timestamp to check against (must not be {@code null})
     * @return {@code true} if {@code now >= expiresAt}; {@code false} otherwise
     */
    public static boolean isExpired(LocalDateTime now, LocalDateTime expiresAt) {
        return now.isAfter(expiresAt) || now.isEqual(expiresAt);
    }
}
