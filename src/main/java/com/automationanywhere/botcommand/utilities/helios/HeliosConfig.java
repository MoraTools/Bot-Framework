package com.automationanywhere.botcommand.utilities.helios;

import java.net.ProxySelector;

/**
 * Immutable connection and context details for one Helios Cloud streaming session.
 *
 * <p>Built by {@code StartLoggerSession} when the user enables streaming and handed to
 * {@code CustomLogger}. A {@code null} instance means the feature is off and nothing
 * Helios-related is wired into the logger context.
 *
 * @author jamir-boop
 */
public final class HeliosConfig {

    /** Server root, always without a trailing slash. */
    public final String baseUrl;
    /** Value sent in the {@code X-Helios-Ingest-Key} header. */
    public final String ingestKey;
    public final String executionId;
    public final String botUri;
    /** URI of the master Task Bot that started this run; empty when the logger runs in the master itself. */
    public final String parentBotUri;
    /** Control Room file id when the parent or bot URI carries one; empty otherwise. */
    public final String fileId;
    public final String machine;
    public final String user;
    /** Proxy selector supplied by the bot agent, or {@code null} when no proxy is configured. */
    public final ProxySelector proxySelector;

    public HeliosConfig(String baseUrl, String ingestKey, String executionId, String botUri,
                        String parentBotUri, String fileId, String machine, String user,
                        ProxySelector proxySelector) {
        this.baseUrl = stripTrailingSlashes(baseUrl);
        this.ingestKey = ingestKey == null ? "" : ingestKey;
        this.executionId = nullToEmpty(executionId);
        this.botUri = nullToEmpty(botUri);
        this.parentBotUri = nullToEmpty(parentBotUri);
        this.fileId = nullToEmpty(fileId);
        this.machine = nullToEmpty(machine);
        this.user = nullToEmpty(user);
        this.proxySelector = proxySelector;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String stripTrailingSlashes(String url) {
        String trimmed = nullToEmpty(url).trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
