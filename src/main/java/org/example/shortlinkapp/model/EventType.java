package org.example.shortlinkapp.model;

/**
 * Canonical set of event categories emitted by the application.
 *
 * <p>Used by {@link org.example.shortlinkapp.service.EventService} and stored in {@code
 * EventLog.type}. Categories are intentionally compact to keep the log readable and easy to filter.
 */
public enum EventType {
  /**
   * Click limit has been reached for a link. The link is blocked until the owner raises the limit
   * or sets it to {@code unlimited}.
   */
  LIMIT_REACHED,

  /**
   * Time-to-live (TTL) has expired for a link. Depending on configuration, the link is either
   * hard-deleted or marked as {@code EXPIRED}.
   */
  EXPIRED,

  /**
   * Informational message (non-error). Examples: CREATE, OPEN counters, EXPORT completed, config
   * reload toggles, etc.
   */
  INFO,

  /**
   * Error-level message indicating an exceptional or failed operation (I/O failure, malformed
   * input, security/restriction violation).
   */
  ERROR
}
