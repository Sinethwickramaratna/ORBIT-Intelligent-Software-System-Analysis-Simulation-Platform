package com.orbit.backend.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** UI theme preference. Public because the theme is chosen before anyone has registered. */
@Slf4j
@RestController
@RequestMapping("/api/settings/theme")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    public record ThemeRequest(@NotBlank String theme) {
    }

    /** {@code theme} is null when the user has not picked one yet. */
    public record ThemeResponse(String theme) {
    }

    @GetMapping
    public ThemeResponse get() {
        return new ThemeResponse(settingsService.getTheme().orElse(null));
    }

    @PutMapping
    public ThemeResponse put(@Valid @RequestBody ThemeRequest request) {
        return new ThemeResponse(settingsService.saveTheme(request.theme()));
    }
}
