package org.example.shortlinkapp.model;

import java.time.LocalDateTime;

public class EventLog {
    public LocalDateTime ts;
    public EventType type;
    public String ownerUuid;
    public String shortCode;
    public String message;
}
