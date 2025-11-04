package org.example.shortlinkapp.util;


import java.time.LocalDateTime;


public final class TimeUtils {
    private TimeUtils() {}


    public static boolean isExpired(LocalDateTime now, LocalDateTime expiresAt) {
        return now.isAfter(expiresAt) || now.isEqual(expiresAt);
    }
}