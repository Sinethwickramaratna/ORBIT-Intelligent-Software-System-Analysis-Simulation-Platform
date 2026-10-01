package com.orbit.backend.entity;

/** Kind of software system a project is. Stored by name; mirrored by the CHECK constraint in V2__create_projects.sql. */
public enum ProjectType {
    WEB_APPLICATION("Web Application"),
    DESKTOP_APPLICATION("Desktop Application"),
    MOBILE_APPLICATION("Mobile Application"),
    DISTRIBUTED_SYSTEM("Distributed System"),
    MICROSERVICES_SYSTEM("Microservices System"),
    BACKEND_API("Backend / API"),
    DATA_ML_SYSTEM("Data / ML System"),
    EMBEDDED_IOT_SYSTEM("Embedded / IoT System"),
    OTHER("Other");

    private final String label;

    ProjectType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
