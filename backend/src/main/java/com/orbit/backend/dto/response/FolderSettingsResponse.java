package com.orbit.backend.dto.response;

import java.util.List;

/**
 * {@code folders} is what .env asks for, {@code active} what the running backend can really open. They differ until
 * the containers are re-created ({@code restartRequired}). {@code editable} is false when the backend runs on the
 * user's computer (it can open every folder, nothing to configure). {@code autoApply} is true while the host helper
 * is running ({@code helper}: RUNNING, NOT_RUNNING or NO_SIGNAL_FOLDER when these containers predate the helper), so saving re-creates the containers by itself; {@code applying} while such a request is pending.
 */
public record FolderSettingsResponse(List<String> folders, List<String> active, boolean restartRequired,
                                     boolean editable, int max, boolean autoApply,
                                     boolean applying, String helper) {
}
