package com.automationanywhere.botcommand.utilities.mode;

import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.toolchain.runtime.session.CloseableSessionObject;

import java.util.Locale;

public final class RuntimeMode implements CloseableSessionObject {
    private final String mode;

    public RuntimeMode(String mode) {
        this.mode = normalize(mode);
    }

    public String getMode() {
        return mode;
    }

    public static String normalize(String mode) {
        String normalized = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("DEV") && !normalized.equals("QA") && !normalized.equals("PRD")) {
            throw new BotCommandException("Runtime mode must be DEV, QA or PRD. Received: '" + mode + "'.");
        }
        return normalized;
    }

    public static String effectiveMode(RuntimeMode session, String defaultMode) {
        String fallback = normalize(defaultMode);
        return session == null ? fallback : session.getMode();
    }

    @Override
    public void close() {
        // This immutable session has no resources to release.
    }

    @Override
    public boolean isClosed() {
        return false;
    }
}
