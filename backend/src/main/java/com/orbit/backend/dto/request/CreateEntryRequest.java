package com.orbit.backend.dto.request;

import com.orbit.backend.dto.response.ProjectTreeResponse.Kind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** New file or folder: {@code parent} is the folder relative to the project root ("" = the root). */
public record CreateEntryRequest(
        @NotNull(message = "Parent folder is required") @Size(max = 4096) String parent,
        @NotBlank(message = "Name is required") @Size(max = 255) String name,
        @NotNull(message = "Kind is required") Kind kind) {
}
