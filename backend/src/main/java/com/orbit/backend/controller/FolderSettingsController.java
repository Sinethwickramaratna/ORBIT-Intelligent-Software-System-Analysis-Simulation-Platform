package com.orbit.backend.controller;

import com.orbit.backend.dto.request.FolderSettingsRequest;
import com.orbit.backend.dto.response.FolderSettingsResponse;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import com.orbit.backend.service.FolderSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The drives/folders ORBIT may open (ORBIT_MOUNT_n in .env). Requires a signed-in user. */
@RestController
@RequestMapping("/api/settings/folders")
@RequiredArgsConstructor
public class FolderSettingsController {

    private final FolderSettingsService service;

    @GetMapping
    public FolderSettingsResponse get() {
        return response();
    }

    @PutMapping
    public FolderSettingsResponse put(@RequestBody FolderSettingsRequest request) {
        if (!service.editable()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "ORBIT is running directly on this computer and can open every folder - nothing to configure");
        }
        service.save(request.folders());
        if (service.restartRequired()) {
            service.requestApply();
        }
        return response();
    }

    private FolderSettingsResponse response() {
        return new FolderSettingsResponse(service.desired(), service.active(), service.restartRequired(),
                service.editable(), FolderSettingsService.MAX_FOLDERS, service.autoApplyAvailable(),
                service.applying(), service.helperState());
    }
}
