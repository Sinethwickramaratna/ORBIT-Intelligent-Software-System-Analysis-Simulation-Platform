package com.orbit.backend.exception;

import com.orbit.backend.entity.User;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    USER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "No user exists with that username"),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "Incorrect password"),
    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "That username is already taken"),
    USER_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "Access token is required"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Access token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Access token is invalid"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Refresh token is invalid"),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Refresh token has expired, please log in again"),
    SECRET_KEY_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "The token secret key has not been configured yet"),
    ENVIRONMENT_ALREADY_CONFIGURED(HttpStatus.CONFLICT, "That setting is already configured and cannot be changed here"),
    DATABASE_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "The database is not ready yet"),
    SECRET_KEY_TOO_WEAK(HttpStatus.BAD_REQUEST, "The secret key must be at least 32 characters long"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed for this endpoint"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type"),
    ENV_FILE_NOT_WRITABLE(HttpStatus.INTERNAL_SERVER_ERROR, "The .env file could not be written"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    INVALID_SETTING(HttpStatus.BAD_REQUEST, "Invalid setting value"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You are not allowed to do that"),
    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "Project not found"),
    PROJECT_NAME_MISMATCH(HttpStatus.BAD_REQUEST, "The typed name does not match the project name"),
    PROJECT_ALREADY_EXISTS(HttpStatus.CONFLICT, "You already have a project at that location"),
    PROJECT_LOCATION_INVALID(HttpStatus.BAD_REQUEST, "The project location is not valid"),
    PROJECT_LOCATION_OUTSIDE_ROOT(HttpStatus.BAD_REQUEST, "The project location is outside the folder ORBIT can access"),
    PROJECT_LOCATION_NOT_DIRECTORY(HttpStatus.BAD_REQUEST, "The project location exists but is not a folder"),
    PROJECT_FOLDER_UNAVAILABLE(HttpStatus.CONFLICT, "The project folder cannot be read right now"),
    PROJECT_PATH_INVALID(HttpStatus.BAD_REQUEST, "That path is not inside the project"),
    PROJECT_GIT_INIT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Git could not be initialized in the project folder"),
    GIT_URL_INVALID(HttpStatus.BAD_REQUEST, "That is not a valid repository URL"),
    GIT_REPOSITORY_UNAVAILABLE(HttpStatus.BAD_REQUEST, "The repository could not be reached"),
    GIT_CLONE_FAILED(HttpStatus.BAD_GATEWAY, "The repository could not be cloned"),
    PROJECT_CLONE_TARGET_EXISTS(HttpStatus.CONFLICT, "The clone folder already exists and is not empty"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}
