package org.example.shortlinkapp.util;

import com.google.gson.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON utilities for the application.
 *
 * <p>This class provides a single, shared {@link Gson} instance configured with:
 * <ul>
 *   <li><b>Pretty printing</b> for human-readable JSON;</li>
 *   <li>Custom (de)serializers for {@link LocalDateTime} using
 *       {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME}.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> The returned {@link Gson} instance is thread-safe after construction,
 * so it can be reused across the application.
 *
 * <p><b>LocalDateTime format:</b> values are encoded/decoded as strings like
 * {@code 2025-11-10T12:34:56.789}. If a JSON field is {@code null}, Gson will handle it as
 * {@code null}; the adapters are not invoked for {@code null} values.</p>
 */
public final class JsonUtils {
    private JsonUtils() {}

    /** Formatter used for {@link LocalDateTime} ISO serialization. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Serializer for {@link LocalDateTime} that writes values as ISO-8601 strings
     * (e.g., {@code 2025-11-10T12:34:56.789}).
     */
    private static final JsonSerializer<LocalDateTime> LDT_SER =
            (src, t, ctx) -> new JsonPrimitive(ISO.format(src));

    /**
     * Deserializer for {@link LocalDateTime} that parses ISO-8601 strings produced by
     * {@link #LDT_SER}.
     */
    private static final JsonDeserializer<LocalDateTime> LDT_DES =
            (json, t, ctx) -> LocalDateTime.parse(json.getAsString(), ISO);

    /**
     * Returns a {@link Gson} instance configured with:
     * <ul>
     *   <li>Pretty printing;</li>
     *   <li>Type adapters for {@link LocalDateTime} using ISO-8601 format.</li>
     * </ul>
     *
     * @return configured {@link Gson} ready for use across the app
     */
    public static Gson gson() {
        return new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(LocalDateTime.class, LDT_SER)
                .registerTypeAdapter(LocalDateTime.class, LDT_DES)
                .create();
    }
}
