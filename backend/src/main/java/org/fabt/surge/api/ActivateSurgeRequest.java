package org.fabt.surge.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ActivateSurgeRequest(
        @NotBlank @Size(max = 500) String reason,
        String boundingBox,
        Instant scheduledEnd
) {}
