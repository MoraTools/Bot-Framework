package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.json.JSONObject;
import org.testng.Assert;
import screen.process.RecorderTestSupport;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.Level;

public class CaptureRegistrationTest {
    @Test public void rejectionAfterRegistrationCompletesTheOriginalCaptureExactlyOnce() throws Exception {
        RecorderTestSupport.requireShell();
        Path root = Files.createTempDirectory("capture-registration");
        Path log = Files.createDirectories(root.resolve("logs"));
        Path outbox = Files.createDirectories(root.resolve("outbox/media/partition"));
        Path ffmpeg = root.resolve("fake-ffmpeg");
        Files.writeString(ffmpeg, "#!/bin/sh\nfor last; do :; done\nring=${last%/*}\nprintf a > \"$ring/0.mov\"\nprintf b > \"$ring/1.mov\"\nprintf '0.mov\\n' > \"$ring/list.txt.tmp\"\nmv \"$ring/list.txt.tmp\" \"$ring/list.txt\"\nwhile read line; do [ \"$line\" = q ] && exit 0; done\n");
        Files.setPosixFilePermissions(ffmpeg, PosixFilePermissions.fromString("rwx------"));
        ScreenRecorder.RealScreenRecorder recorder = new ScreenRecorder.RealScreenRecorder("logger", log, 30,
                Set.of(Level.ERROR), root, ffmpeg, EncodingMode.FAST, true);
        try (AutoCloseable outboxProperty = RecorderTestSupport.useOutbox(root.resolve("outbox"))) {
            java.lang.reflect.Field field = ScreenRecorder.RealScreenRecorder.class.getDeclaredField("encoderPool");
            field.setAccessible(true);
            ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(recorder);
            AtomicInteger count = new AtomicInteger();
            CaptureResult[] registered = new CaptureResult[1];
            Path manifest = outbox.resolve("row.json");
            CaptureResult result = recorder.snapshotForError("capture", capture -> {
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
            Assert.assertEquals(recorder.snapshotForError("next-capture", ignored -> { }).unavailableReason, "videoUnavailable");
            JSONObject terminal = new JSONObject(Files.readString(manifest));
            Assert.assertEquals(terminal.getString("state"), "unavailable");
            Assert.assertEquals(terminal.getString("reason"), "videoUnavailable");
            Assert.assertFalse(Files.exists(result.scratch));
        } finally {
            recorder.close();
            RecorderTestSupport.deleteRecursivelyQuietly(root);
        }
    }

    @Test public void disabledRecorderRegistersEachCallExactlyOnceAndDoesNotInventCompletion() {
        AtomicInteger registrations = new AtomicInteger();
        CaptureResult first = ScreenRecorder.DISABLED.snapshotForError("first", capture -> registrations.incrementAndGet());
        CaptureResult second = ScreenRecorder.DISABLED.snapshotForError("second", capture -> registrations.incrementAndGet());
        Assert.assertEquals(registrations.get(), 2);
        Assert.assertNull(first.video);
        Assert.assertNull(second.completion.join());
        Assert.assertEquals(first.unavailableReason, "videoUnavailable");
        CaptureResult pending = new CaptureResult("capture", Path.of("clip.mp4"), Path.of("scratch"), null);
        Assert.assertFalse(pending.completion.isDone(), "a future path is never a completed clip");
    }
}
