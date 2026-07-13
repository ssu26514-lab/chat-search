package com.susu.duplicatecleaner;

final class ToolSession {
    enum Mode {
        DUPLICATE,
        SEMANTIC_DUPLICATE,
        RENAME,
        BROWSER
    }

    private static Mode activeMode;

    private ToolSession() {
    }

    static synchronized boolean acquire(Mode mode) {
        if (activeMode == null || activeMode == mode) {
            activeMode = mode;
            return true;
        }
        return false;
    }

    static synchronized void release(Mode mode) {
        if (activeMode == mode) activeMode = null;
    }

    static synchronized Mode activeMode() {
        return activeMode;
    }
}
