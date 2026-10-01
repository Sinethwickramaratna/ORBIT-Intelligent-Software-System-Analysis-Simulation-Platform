package com.orbit.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFolderRequest(
        @NotBlank(message = "Parent folder is required") @Size(max = 1024) String parent,
        @NotBlank(message = "Folder name is required") @Size(max = 100) String name) {
}
