package com.orbit.backend.settings;

import com.orbit.backend.common.exception.ApiException;
import com.orbit.backend.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    static final String THEME_KEY = "theme";

    /** Must stay in sync with the theme ids offered by the frontend. */
    public static final Set<String> THEMES = Set.of(
            "orbit-dark", "orbit-light", "vs-dark", "vs-light",
            "monokai", "solarized-dark", "solarized-light", "high-contrast");

    private final AppSettingRepository repository;

    @Transactional(readOnly = true)
    public Optional<String> getTheme() {
        return repository.findById(THEME_KEY).map(AppSetting::getValue);
    }

    @Transactional
    public String saveTheme(String theme) {
        if (theme == null || !THEMES.contains(theme)) {
            throw new ApiException(ErrorCode.INVALID_SETTING, "Unknown theme '" + theme + "'");
        }
        repository.save(new AppSetting(THEME_KEY, theme, Instant.now()));
        log.info("Theme preference saved: {}", theme);
        return theme;
    }
}
