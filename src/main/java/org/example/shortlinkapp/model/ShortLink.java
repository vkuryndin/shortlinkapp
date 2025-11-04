package org.example.shortlinkapp.model;

import java.time.LocalDateTime;

public class ShortLink {
    public String id;
    public String ownerUuid;
    public String longUrl;
    public String shortCode;
    public LocalDateTime createdAt;
    public LocalDateTime expiresAt;
    public Integer clickLimit;     // null => unlimited
    public int clickCount;
    public LocalDateTime lastAccessAt;
    public Status status;          // ACTIVE | EXPIRED | LIMIT_REACHED | DELETED
}
