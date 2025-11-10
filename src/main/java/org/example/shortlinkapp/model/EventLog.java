package org.example.shortlinkapp.model;

import java.time.LocalDateTime;

/**
 * Represents a single event/notification produced by the Shortlink application.
 *
 * <p>Events are appended to the persistent event log (see {@code
 * org.example.shortlinkapp.storage.EventsRepository}) and can be queried per-owner or globally via
 * {@code org.example.shortlinkapp.service.EventService}. Typical producers include lifecycle
 * operations on short links (create/open/delete), automatic cleanups (expired/limit reached), and
 * validation routines.
 *
 * <h2>Serialization</h2>
 *
 * <ul>
 *   <li>Instances are serialized to JSON using the app's {@code Gson} configuration (see {@code
 *       org.example.shortlinkapp.util.JsonUtils}).
 *   <li>{@link #ts} is written as ISO-8601 local date-time ({@code yyyy-MM-dd'T'HH:mm:ss.SSS})
 *       without timezone.
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>This is a simple data holder (POJO) with public fields and no internal synchronization.
 * Instances are not thread-safe by themselves; repositories that store them are responsible for
 * synchronization and atomic persistence.
 *
 * <h2>Field semantics</h2>
 *
 * <ul>
 *   <li>{@link #ts} — event timestamp (set when the event is created).
 *   <li>{@link #type} — event category (e.g., INFO, EXPIRED, LIMIT_REACHED, ERROR).
 *   <li>{@link #ownerUuid} — UUID of the user associated with the event; may be {@code null} for
 *       global/system events.
 *   <li>{@link #shortCode} — short link code related to the event (if applicable); may be {@code
 *       null} or {@code "-"} for owner-only or system events.
 *   <li>{@link #message} — human-readable description (compact, suitable for console listing).
 * </ul>
 *
 * @see org.example.shortlinkapp.model.EventType
 * @see org.example.shortlinkapp.service.EventService
 * @see org.example.shortlinkapp.storage.EventsRepository
 */
public class EventLog {
  /** Event timestamp (local time), written in ISO-8601 format during JSON serialization. */
  public LocalDateTime ts;

  /** High-level type of the event (INFO, EXPIRED, LIMIT_REACHED, ERROR). */
  public EventType type;

  /** UUID of the user associated with this event; can be {@code null} for global/system events. */
  public String ownerUuid;

  /**
   * Short link code related to this event (if any). May be {@code null} or {@code "-"} for
   * owner-only or system events.
   */
  public String shortCode;

  /** Human-readable event message suitable for console output and logs. */
  public String message;
}
