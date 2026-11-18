package com.orbit.backend.dto.response;

/**
 * A text file opened in the editor. {@code content} has no byte-order mark ({@code bom} says whether the file had
 * one); {@code lineEnding} is "CRLF" when the file uses Windows line endings, otherwise "LF". On a save response
 * {@code content} is null.
 */
public record FileContentResponse(String path, String name, String content, long size, long lastModified,
                                  boolean bom, String lineEnding) {
}
