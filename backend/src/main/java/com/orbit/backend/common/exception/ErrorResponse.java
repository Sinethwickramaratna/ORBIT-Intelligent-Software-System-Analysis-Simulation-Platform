package com.orbit.backend.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(Instant timestamp, int status, String code, String message, String path,
                            Map<String, String> fieldErrors) {

    public static ErrorResponse of(ErrorCode code, String message, String path) {
        return new ErrorResponse(Instant.now(), code.getStatus().value(), code.name(), message, path, null);
    }

    public static ErrorResponse of(ErrorCode code, String message, String path, Map<String, String> fieldErrors) {
        return new ErrorResponse(Instant.now(), code.getStatus().value(), code.name(), message, path, fieldErrors);
    }
}
