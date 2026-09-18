package com.bkanent.common.agent;

public final class SessionStreamVisibility {

    public static final String USER = "user";
    public static final String PROGRESS = "progress";
    public static final String INTERNAL = "internal";

    private SessionStreamVisibility() {
    }

    public static boolean isExternallyVisible(String visibility) {
        return USER.equals(visibility) || PROGRESS.equals(visibility);
    }
}
