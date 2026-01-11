package com.wannaverse.persistence;

public enum NotificationEventType {
    DEPLOYMENT_STARTED("Deployment Started", "deployments"),
    DEPLOYMENT_COMPLETED("Deployment Completed", "deployments"),
    DEPLOYMENT_FAILED("Deployment Failed", "deployments"),

    CONTAINER_STOPPED_UNEXPECTEDLY("Container Stopped Unexpectedly", "containers"),
    CONTAINER_HEALTH_CHECK_FAILED("Container Health Check Failed", "containers"),
    CONTAINER_RESTART_LOOP("Container Restart Loop", "containers"),
    CONTAINER_OOM_KILLED("Container OOM Killed", "containers"),

    USER_LOGIN("User Login", "security"),
    PASSWORD_CHANGED("Password Changed", "security"),

    HOST_DISCONNECTED("Host Disconnected", "system"),
    HOST_RECONNECTED("Host Reconnected", "system");

    private final String displayName;
    private final String category;

    NotificationEventType(String displayName, String category) {
        this.displayName = displayName;
        this.category = category;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCategory() {
        return category;
    }
}
