package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.testng.Assert;
import screen.process.RecorderTestSupport;
import org.testng.annotations.Test;
import org.apache.logging.log4j.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CaptureOwnershipTest {
    @Test public void sameSecondRowsShareOneEncode() throws Exception {
        try (Recorder recorder = new Recorder(EncodingMode.COMPACT, true)) {
            RecorderTestSupport.awaitNextChunk();
            AtomicInteger registrations = new AtomicInteger();
            CaptureResult first = recorder.value.snapshotForError("first", result -> registrations.incrementAndGet());
            CaptureResult second = recorder.value.snapshotForError("second", result -> registrations.incrementAndGet());
            Assert.assertSame(second, first, "same-second rows share one encode");
            Assert.assertEquals(registrations.get(), 2);
            recorder.release();
            Assert.assertEquals(first.completion.get(5, TimeUnit.SECONDS), first.video);
            Assert.assertTrue(Files.readString(recorder.root.resolve("encode-args")).contains("libaom-av1\n"));
            recorder.value.close();
            Assert.assertTrue(Files.exists(first.video));
        }
    }

    @Test public void pureLocalRecordingNeedsNoMediaSidecarsOrOutboxPublisher() throws Exception {
        try (Recorder recorder = new Recorder(EncodingMode.FAST, false)) {
            Path captures = recorder.root.resolve("sessions/local/captures");
            Files.writeString(captures, "a media sidecar directory cannot be created here");
            Path poisoned = Files.createDirectories(recorder.root.resolve("outbox/media/partition")).resolve("damaged.json");
            Files.writeString(poisoned, "{invalid-media-state");
            CaptureResult local = recorder.value.snapshotForError("local-only", result -> { });
            Assert.assertNull(local.unavailableReason);
            Assert.assertFalse(local.mediaManaged);
            Assert.assertFalse(Files.exists(local.scratch.resolve("capture.json")));
            recorder.release();
            Assert.assertEquals(local.completion.get(5, TimeUnit.SECONDS), local.video);
            recorder.value.close();
            Assert.assertTrue(Files.exists(local.video));
            Assert.assertFalse(Files.exists(local.scratch), "unrelated media storage cannot retain a pure-local scratch");
            Assert.assertEquals(Files.readString(poisoned), "{invalid-media-state");
        }
    }

    @Test public void mediaOwnershipWriteFailureDoesNotCancelANewRequestedLocalClip() throws Exception {
        try (Recorder recorder = new Recorder(EncodingMode.FAST, true)) {
            Files.writeString(recorder.root.resolve("sessions/local/captures"), "force media ownership write failure");
            AtomicInteger registrations = new AtomicInteger();
            CaptureResult local = recorder.value.snapshotForError("local-request", result -> registrations.incrementAndGet());
            Assert.assertEquals(registrations.get(), 1);
            Assert.assertNull(local.unavailableReason, "local capture remains usable");
            Assert.assertEquals(local.mediaFailure, "storageFailure");
            Assert.assertFalse(local.mediaManaged);
            recorder.release();
            Assert.assertEquals(local.completion.get(5, TimeUnit.SECONDS), local.video);
            recorder.value.close();
            Assert.assertTrue(Files.exists(local.video));
        }
    }

    @Test public void aFailedFirstOwnershipWriteIsRetriedByTheNextSameSecondRow() throws Exception {
        try (Recorder recorder = new Recorder(EncodingMode.FAST, true)) {
            Path blocker = recorder.root.resolve("sessions/local/captures");
            Files.writeString(blocker, "force media ownership write failure");
            RecorderTestSupport.awaitNextChunk();
            CaptureResult first = recorder.value.snapshotForError("first", result -> { });
            Assert.assertFalse(first.mediaManaged);
            Assert.assertEquals(first.mediaFailure, "storageFailure");
            Files.delete(blocker);
            CaptureResult second = recorder.value.snapshotForError("second", result -> { });
            Assert.assertSame(second, first, "the same-second row shares the capture");
            Assert.assertTrue(first.mediaManaged, "the retry saved the owner record");
            Assert.assertNull(first.mediaFailure);
            Assert.assertTrue(Files.isRegularFile(blocker.resolve(first.captureId + ".json")));
            Assert.assertTrue(Files.isRegularFile(first.scratch.resolve("capture.json")));
        }
    }

    private static final class Recorder implements AutoCloseable {
        final Path root;
        final ScreenRecorder.RealScreenRecorder value;
        final AutoCloseable outboxProperty;
        Recorder(EncodingMode mode, boolean mediaVideoEnabled) throws Exception {
            RecorderTestSupport.requireShell();
            root = Files.createTempDirectory("capture-ownership");
            Path log = Files.createDirectories(root.resolve("logs"));
            Path ffmpeg = root.resolve("fake-ffmpeg");
            String control = root.toString().replace("'", "'\\''");
            Files.writeString(ffmpeg, "#!/bin/sh\ncontrol='" + control + "'\nstage1=false\nfor arg; do [ \"$arg\" = gdigrab ] && stage1=true; last=$arg; done\n"
                    + "if $stage1; then\nring=${last%/*}\nprintf segment > \"$ring/0.mov\"\nsleep 0.02\nprintf tail > \"$ring/1.mov\"\nprintf '0.mov\\n' > \"$ring/list.txt.tmp\"\nmv \"$ring/list.txt.tmp\" \"$ring/list.txt\"\nwhile read line; do [ \"$line\" = q ] && exit 0; done\n"
                    + "else\nprintf '%s\\n' \"$@\" > \"$control/encode-args\"\nwhile [ ! -f \"$control/release\" ]; do sleep 0.02; done\nprintf full-original > \"$last\"\nfi\n");
            Files.setPosixFilePermissions(ffmpeg, PosixFilePermissions.fromString("rwx------"));
            outboxProperty = RecorderTestSupport.useOutbox(root.resolve("outbox"));
            value = new ScreenRecorder.RealScreenRecorder("local", log, 90, Set.of(Level.INFO, Level.WARN), root, ffmpeg, mode, mediaVideoEnabled);
        }
        void release() throws Exception { Files.writeString(root.resolve("release"), "finish"); }
        @Override public void close() throws Exception {
            release();
            value.close();
            RecorderTestSupport.deleteRecursivelyQuietly(root);
            outboxProperty.close();
        }
    }
}
