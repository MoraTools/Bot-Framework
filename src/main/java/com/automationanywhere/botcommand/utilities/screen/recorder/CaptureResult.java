package com.automationanywhere.botcommand.utilities.screen.recorder;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** One actual encode, shared by all rows and owners that request it. */
public final class CaptureResult {
    public final String captureId;
    public final Path video;
    public final Path scratch;
    public final CompletableFuture<Path> completion = new CompletableFuture<>();
    public volatile String unavailableReason;
    public volatile String mediaFailure;
    volatile boolean mediaManaged;
    public final java.util.concurrent.atomic.AtomicBoolean localRequired = new java.util.concurrent.atomic.AtomicBoolean();
    volatile boolean cloudScratchReserved;

    CaptureResult(String captureId, Path video, Path scratch, String reason) {
        this.captureId = captureId;
        this.video = video;
        this.scratch = scratch;
        this.unavailableReason = reason;
        if (reason != null) completion.complete(null);
    }

    public static CaptureResult unavailable(String id, String reason) {
        return new CaptureResult(id, null, null, reason);
    }
}
