package com.orbit.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Renames the file or folder at {@code path} (relative to the project root); it stays in the same folder. */
public record RenameEntryRequest(
        @NotBlank(message = "Path is required") @Size(max = 4096) String path,
        @NotBlank(message = "New name is required") @Size(max = 255) String newName) {
}
