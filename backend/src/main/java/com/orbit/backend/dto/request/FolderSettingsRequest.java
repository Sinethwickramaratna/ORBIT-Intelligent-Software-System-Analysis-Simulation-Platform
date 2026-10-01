package com.orbit.backend.dto.request;

import java.util.List;

/** The folders/drives ORBIT may open (host paths, forward slashes), at most 8. */
public record FolderSettingsRequest(List<String> folders) {
}
