package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.json.JSONObject;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;

public class MediaCrashRecoveryTest {
    @Test public void exactScratchRecoversCloudBytesBeforeRemovalAndExistingTargetDoesNotMeanReady() throws Exception {
        Path ffmpeg = Path.of("/usr/bin/ffmpeg");
        if (!Files.isExecutable(ffmpeg)) throw new SkipException("Linux synthetic finalizer check requires host FFmpeg");
        Path appData = Files.createTempDirectory("media-crash-recorder");
        Path outbox = Files.createTempDirectory("media-crash-outbox");
        Path directory = Files.createDirectories(outbox.resolve("media/partition"));
        Path logs = Files.createTempDirectory("media-crash-logs");
        String captureId = UUID.randomUUID().toString();
        String loggerId = UUID.randomUUID().toString();
        Path session = appData.resolve("sessions").resolve(loggerId);
        Path scratch = Files.createDirectories(session.resolve("scratch").resolve(captureId));
        Path target = Files.createDirectories(logs.resolve("clips")).resolve(captureId + ".mp4");
        Files.writeString(target, "partial");
        Process source = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc2=size=640x480:rate=5", "-t", "2", "-c:v", "ffvhuff", "-pix_fmt", "yuv420p",
                scratch.resolve("1.mov").toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        Assert.assertTrue(source.waitFor(10, TimeUnit.SECONDS));
        Assert.assertEquals(source.exitValue(), 0);
        new SessionMeta(loggerId, logs.toString(), 30, Set.of(Level.ERROR), Instant.now(), "4.5.0", Long.MAX_VALUE, EncodingMode.FAST)
                .write(session.resolve("session.meta"));
        Files.createFile(session.resolve("session.active"));
        Files.writeString(scratch.resolve("capture.json"), new JSONObject().put("captureId", captureId)
                .put("target", target.toString()).put("localRequired", true).put("cloudOnly", false).toString());
        Path manifest = directory.resolve(loggerId + "-0-video.json");
        Files.writeString(manifest, new JSONObject().put("captureId", captureId).put("source", target.toString())
                .put("kind", "video").put("state", "pending").put("completed", false).put("reserved", 0).toString());
        String previous = System.getProperty("helios.outbox.directory");
        try {
            System.setProperty("helios.outbox.directory", outbox.toString());
            CrashSweep.sweepForTest(appData, ffmpeg);
        } finally {
            if (previous == null) System.clearProperty("helios.outbox.directory");
            else System.setProperty("helios.outbox.directory", previous);
        }
        JSONObject completed = new JSONObject(Files.readString(manifest));
        Assert.assertTrue(completed.getBoolean("completed"));
        Assert.assertTrue(completed.getLong("bytes") > 7);
        Assert.assertTrue(Files.isRegularFile(manifest.resolveSibling(manifest.getFileName() + ".ready")));
        Assert.assertTrue(Files.isRegularFile(target), "requested local clip keeps its original path");
        Assert.assertFalse(Files.exists(session));
        Assert.assertFalse(Files.exists(session.resolveSibling(loggerId + ".salvaging")));
    }
}
