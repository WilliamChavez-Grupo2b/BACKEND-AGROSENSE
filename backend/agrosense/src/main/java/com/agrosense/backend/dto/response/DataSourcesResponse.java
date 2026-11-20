package com.agrosense.backend.dto.response;

import java.util.List;
import java.util.Map;

/**
 * GET /api/data-sources: where each figure the application shows comes from (traceability catalogue), how many of
 * the caller's readings of the last 24 hours came from each source, and whether the virtual sensor is on.
 */
public record DataSourcesResponse(List<Entry> sources, Map<String, Long> readingCountsBySource,
        boolean simulatorEnabled) {

    /** One figure: its origin (SENSOR_FISICO, SENSOR_VIRTUAL, ENDPOINT, IA, SEED or CALCULO), the Java class that
     * produces it, where it is stored (table.column, or null when it is not stored), the endpoint that serves it
     * and the file of the repository that holds the producer. */
    public record Entry(String key, String label, String origin, String producer, String storage, String endpoint,
            String file) {
    }
}
