package dev.stacklight.backend.ingest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Body of {@code POST /api/events}.
 *
 * <p>{@code eventId} is optional; the server generates one when the caller omits it.
 */
public record IngestRequest(
        UUID eventId,

        @NotBlank @Size(max = 100) String service,

        @NotBlank @Size(max = 20) String level,

        @NotBlank @Size(max = 4000) String message,

        JsonNode payload) {}
