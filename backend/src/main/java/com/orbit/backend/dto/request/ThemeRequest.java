package com.orbit.backend.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ThemeRequest(@NotBlank String theme) {
}
