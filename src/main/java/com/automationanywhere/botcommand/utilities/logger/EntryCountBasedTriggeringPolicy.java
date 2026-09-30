package com.automationanywhere.botcommand.utilities.logger;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.rolling.AbstractTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.RollingFileManager;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Log4j2 triggering policy that triggers rollover based on the number of log entries
 * rather than file size.
 *
 * @author Sumit Kumar
 */
@Plugin(name = "EntryCountBasedTriggeringPolicy", category = "Core", printObject = true)
public class EntryCountBasedTriggeringPolicy extends AbstractTriggeringPolicy {

    private final int maxEntries;
    private final AtomicInteger currentEntryCount = new AtomicInteger(0);

    private EntryCountBasedTriggeringPolicy(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * Initializes this triggering policy.
     * This method is called when the policy is first attached to a RollingFileManager.
     * Counts existing entries in the file to properly handle appends across program restarts.
     */
    @Override
    public void initialize(final RollingFileManager manager) {
        int existingEntries = countExistingEntries(manager.getFileName());
        currentEntryCount.set(existingEntries);
    }

    /**
     * Checks if rollover should be triggered based on the entry count.
     *
     * @param logEvent The log event
     * @return true if rollover should occur, false otherwise
     */
    @Override
    public boolean isTriggeringEvent(final LogEvent logEvent) {
        int count = currentEntryCount.incrementAndGet();

        // Trigger rollover AFTER we exceed the max entries
        // This ensures we write exactly maxEntries before rotating
        if (count > maxEntries) {
            // Reset the counter for the next cycle
            currentEntryCount.set(1); // Set to 1 because current entry will be first in new file
            return true;
        }

        return false;
    }

    /**
     * Get the current entry count
     */
    public int getCurrentEntryCount() {
        return currentEntryCount.get();
    }

    /** Counts completed events, independent of tables inside variable snapshots. */
    private int countExistingEntries(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || file.length() == 0) {
            return 0;
        }

        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals(CustomHTMLLayout.ENTRY_END.trim())) count++;
            }
        } catch (IOException e) {
            // Preserve the existing fallback when a file cannot be read.
            return 0;
        }
        return count;
    }

    @PluginBuilderFactory
    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder implements org.apache.logging.log4j.core.util.Builder<EntryCountBasedTriggeringPolicy> {

        @PluginBuilderAttribute
        private int maxEntries = 1000; // Default to 1000 entries

        public Builder withMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
            return this;
        }

        @Override
        public EntryCountBasedTriggeringPolicy build() {
            return new EntryCountBasedTriggeringPolicy(maxEntries);
        }
    }
}