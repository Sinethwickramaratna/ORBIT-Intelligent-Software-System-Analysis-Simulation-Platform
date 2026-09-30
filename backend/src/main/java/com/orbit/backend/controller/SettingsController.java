package com.orbit.backend.controller;

import com.orbit.backend.dto.request.ThemeRequest;
import com.orbit.backend.dto.response.ThemeResponse;
import com.orbit.backend.service.SettingsService;
import jakarta.validation.Valid;
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

    @GetMapping
    public ThemeResponse get() {
        return new ThemeResponse(settingsService.getTheme().orElse(null));
    }

    @PutMapping
    public ThemeResponse put(@Valid @RequestBody ThemeRequest request) {
        return new ThemeResponse(settingsService.saveTheme(request.theme()));
    }
}
