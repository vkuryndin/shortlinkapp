package org.example.shortlinkapp.model;

/**
 * Represents the lifecycle state of a short link.
 *
 * <p>The status is recalculated automatically based on expiration time, click limits, and user
 * actions (such as deletion).
 *
 * <h2>States</h2>
 *
 * <ul>
 *   <li>{@code ACTIVE} – the link is valid and usable.
 *   <li>{@code EXPIRED} – the TTL has passed and the link is no longer active.
 *   <li>{@code LIMIT_REACHED} – the click limit has been reached.
 *   <li>{@code DELETED} – the link was removed by the owner.
 * </ul>
 *
 * @since 1.0
 */
public enum Status {
  ACTIVE,
  EXPIRED,
  LIMIT_REACHED,
  DELETED
}
