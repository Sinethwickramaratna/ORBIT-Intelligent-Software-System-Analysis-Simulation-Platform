package com.orbit.backend.dto.response;

import java.util.List;

/** One level of the folder chooser. {@code parent} is null at the top; paths use forward slashes. */
public record FolderBrowseResponse(String path, String parent, List<Folder> folders, List<Folder> shortcuts,
                                   boolean truncated) {

    public record Folder(String name, String path) {
    }
}
