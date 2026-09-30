package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.json.JSONObject;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.Level;

public class CaptureRegistrationTest {
    @Test public void rejectionAfterRegistrationCompletesTheOriginalCaptureExactlyOnce() throws Exception {
        if (!System.getProperty("os.name").equals("Linux")) throw new SkipException("This synthetic process test uses a POSIX shell");
        Path root = Files.createTempDirectory("capture-registration");
        Path log = Files.createDirectories(root.resolve("logs"));
        Path outbox = Files.createDirectories(root.resolve("outbox/media/partition"));
        Path ffmpeg = root.resolve("fake-ffmpeg");
        Files.writeString(ffmpeg, "#!/bin/sh\nfor last; do :; done\nring=${last%/*}\nprintf a > \"$ring/0.mov\"\nprintf b > \"$ring/1.mov\"\nwhile read line; do [ \"$line\" = q ] && exit 0; done\n");
        Files.setPosixFilePermissions(ffmpeg, PosixFilePermissions.fromString("rwx------"));
        ScreenRecorder.RealScreenRecorder recorder = new ScreenRecorder.RealScreenRecorder("logger", log, 30,
                Set.of(Level.ERROR), root, ffmpeg, EncodingMode.FAST);
        String previous = System.getProperty("helios.outbox.directory");
        System.setProperty("helios.outbox.directory", root.resolve("outbox").toString());
        try {
            java.lang.reflect.Field field = ScreenRecorder.RealScreenRecorder.class.getDeclaredField("encoderPool");
            field.setAccessible(true);
            ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(recorder);
            AtomicInteger count = new AtomicInteger();
            CaptureResult[] registered = new CaptureResult[1];
            Path manifest = outbox.resolve("row.json");
            CaptureResult result = recorder.snapshotForError("capture", false, capture -> {
                count.incrementAndGet();
                registered[0] = capture;
                try {
                    Files.writeString(manifest, new JSONObject().put("captureId", capture.captureId).put("kind", "video")
                            .put("source", capture.video.toString()).put("completed", false).put("reserved", 0).put("state", "pending").toString());
                } catch (java.io.IOException error) { throw new AssertionError(error); }
                pool.shutdown();
            });
            Assert.assertEquals(count.get(), 1);
            Assert.assertSame(result, registered[0]);
            Assert.assertTrue(result.completion.isDone());
            Assert.assertNull(result.completion.join());
            Assert.assertEquals(result.unavailableReason, "videoUnavailable");
            Assert.assertNull(recorder.snapshotForError("wrapper-capture"), "the original Path API still returns null on rejection");
            JSONObject terminal = new JSONObject(Files.readString(manifest));
            Assert.assertEquals(terminal.getString("state"), "unavailable");
            Assert.assertEquals(terminal.getString("reason"), "videoUnavailable");
            Assert.assertFalse(Files.exists(result.scratch));
        } finally {
            recorder.close();
            if (previous == null) System.clearProperty("helios.outbox.directory");
            else System.setProperty("helios.outbox.directory", previous);
        }
    }
}
