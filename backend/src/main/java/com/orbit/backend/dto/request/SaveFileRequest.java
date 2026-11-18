package com.orbit.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Saves a text file as UTF-8. {@code bom} writes a byte-order mark first (keep what the file had).
 * {@code expectedLastModified}: when given and the file changed on disk since then, the save is refused (409) so
 * nobody silently overwrites someone else's change; send null to overwrite anyway.
 */
public record SaveFileRequest(
        @NotBlank(message = "Path is required") @Size(max = 4096) String path,
        @NotNull(message = "Content is required") String content,
        boolean bom,
        Long expectedLastModified) {
}
