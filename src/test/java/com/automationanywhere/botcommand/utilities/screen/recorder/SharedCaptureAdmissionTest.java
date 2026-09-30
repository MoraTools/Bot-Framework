package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.json.JSONObject;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;
import org.apache.logging.log4j.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SharedCaptureAdmissionTest {
    @Test public void sharedCloudScratchUsesAdmissionAndLocalDedupOwnershipReleasesIt() throws Exception {
        try (Recorder recorder = new Recorder(EncodingMode.COMPACT)) {
            alignChunk();
            AtomicInteger registrations = new AtomicInteger();
            CaptureResult cloud = recorder.value.snapshotForError("cloud", false, result -> registrations.incrementAndGet());
            Assert.assertNull(cloud.unavailableReason);
            Assert.assertTrue(cloud.cloudScratchReserved);
            JSONObject reservation = recorder.reservation();
            Assert.assertEquals(reservation.optLong("ring"), 0L, "the requested local ring is not charged");
            Assert.assertEquals(reservation.getLong("capture-cloud"), Files.size(cloud.scratch.resolve("0.mov")));
            CaptureResult local = recorder.value.snapshotForError("local", true, result -> registrations.incrementAndGet());
            Assert.assertSame(local, cloud, "same-second rows share one encode");
            Assert.assertEquals(registrations.get(), 2);
            Assert.assertTrue(cloud.localRequired.get());
            Assert.assertFalse(cloud.cloudScratchReserved);
            Assert.assertFalse(recorder.reservation().has("capture-cloud"));
            Assert.assertTrue(new JSONObject(Files.readString(cloud.scratch.resolve("capture.json"))).getBoolean("localRequired"));
            Assert.assertFalse(new JSONObject(Files.readString(cloud.scratch.resolve("capture.json"))).getBoolean("cloudOnly"));
            recorder.release();
            Assert.assertEquals(cloud.completion.get(5, TimeUnit.SECONDS), cloud.video);
            String arguments = Files.readString(recorder.root.resolve("encode-args"));
            Assert.assertFalse(arguments.contains("-fs\n"), "a shared encode keeps the requested local duration and bytes");
            Assert.assertTrue(arguments.contains("libaom-av1\n"), "the existing COMPACT codec is retained");
            Assert.assertEquals(SessionMeta.read(recorder.root.resolve("sessions/local/session.meta")).bufferSeconds, 90);
            recorder.value.close();
            Assert.assertTrue(Files.exists(cloud.video), "cloud cleanup must not remove the upgraded local clip");
        }
    }

    @Test public void deniedCloudScratchLeavesTheLocalRingAndRequestedClipWorking() throws Exception {
        try (Recorder recorder = new Recorder(EncodingMode.FAST)) {
            RecorderStorage occupied = new RecorderStorage(recorder.root, "occupied", 0);
            try {
                Assert.assertTrue(occupied.reserve("other-capture", RecorderStorage.MAX_BYTES));
                AtomicInteger registrations = new AtomicInteger();
                CaptureResult cloud = recorder.value.snapshotForError("denied", false, result -> registrations.incrementAndGet());
                Assert.assertEquals(cloud.unavailableReason, "storageBudget");
                Assert.assertEquals(registrations.get(), 1);
                Assert.assertFalse(Files.exists(recorder.root.resolve("sessions/local/scratch/denied")));
                Assert.assertTrue(recorder.value.shouldRecordFor(Level.INFO), "cloud admission cannot stop the local ring");
                CaptureResult local = recorder.value.snapshotForError("requested", true, result -> registrations.incrementAndGet());
                Assert.assertNull(local.unavailableReason);
                Assert.assertFalse(local.cloudScratchReserved);
                Assert.assertEquals(registrations.get(), 2);
                recorder.release();
                Assert.assertEquals(local.completion.get(5, TimeUnit.SECONDS), local.video);
                Assert.assertFalse(Files.readString(recorder.root.resolve("encode-args")).contains("-fs\n"));
                recorder.value.close();
                Assert.assertTrue(Files.exists(local.video));
            } finally { occupied.close(); }
        }
    }

    @Test public void pureLocalRecordingNeedsNoMediaSidecarsOrOutboxPublisher() throws Exception {
        try (Recorder recorder = new Recorder(EncodingMode.FAST, false)) {
            Path captures = recorder.root.resolve("sessions/local/captures");
            Files.writeString(captures, "a media sidecar directory cannot be created here");
            Path poisoned = Files.createDirectories(recorder.root.resolve("outbox/media/partition")).resolve("damaged.json");
            Files.writeString(poisoned, "{invalid-media-state");
            CaptureResult local = recorder.value.snapshotForError("local-only", true, result -> { });
            Assert.assertNull(local.unavailableReason);
            Assert.assertFalse(local.mediaManaged);
            Assert.assertFalse(Files.exists(local.scratch.resolve("capture.json")));
            Assert.assertFalse(Files.exists(recorder.root.resolve("cloud-capture-admission")));
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
            CaptureResult local = recorder.value.snapshotForError("local-request", true, result -> registrations.incrementAndGet());
            Assert.assertEquals(registrations.get(), 1);
            Assert.assertNull(local.unavailableReason, "local capture remains usable");
            Assert.assertEquals(local.mediaFailure, "storageFailure");
            Assert.assertFalse(local.mediaManaged);
            Assert.assertTrue(local.localRequired.get());
            recorder.release();
            Assert.assertEquals(local.completion.get(5, TimeUnit.SECONDS), local.video);
            recorder.value.close();
            Assert.assertTrue(Files.exists(local.video));
        }
    }

    @Test public void failedCloudToLocalOwnershipUpgradeCannotDeleteTheLocalOriginalOnCrashCleanup() throws Exception {
        try (Recorder recorder = new Recorder(EncodingMode.FAST, true)) {
            alignChunk();
            CaptureResult cloud = recorder.value.snapshotForError("shared", false, result -> { });
            Path blocked = Files.createDirectories(cloud.scratch.resolve("capture.json.tmp"));
            Files.writeString(blocked.resolve("keep"), "force failure before ownership metadata changes");
            CaptureResult local = recorder.value.snapshotForError("local-upgrade", true, result -> { });
            Assert.assertSame(local, cloud);
            Assert.assertNull(local.unavailableReason);
            Assert.assertEquals(local.mediaFailure, "storageFailure");
            Assert.assertTrue(local.localRequired.get());
            Assert.assertFalse(new JSONObject(Files.readString(local.scratch.resolve("capture.json"))).getBoolean("localRequired"));
            Assert.assertTrue(recorder.reservation().has("capture-shared"), "failed ownership persistence retains admission until scratch removal");
            recorder.release();
            Assert.assertEquals(local.completion.get(5, TimeUnit.SECONDS), local.video);
            java.lang.reflect.Method cleanup = CrashSweep.class.getDeclaredMethod("removeCloudSources", Path.class);
            cleanup.setAccessible(true);
            cleanup.invoke(null, recorder.root.resolve("sessions/local"));
            Assert.assertTrue(Files.exists(local.video), "old cloud-owned metadata cannot delete a shared local original");
            recorder.value.close();
            Assert.assertTrue(Files.exists(local.video));
        }
    }

    private static void alignChunk() throws InterruptedException {
        long phase = System.currentTimeMillis() % 1000;
        if (phase > 100) Thread.sleep(1000 - phase + 20);
    }

    private static final class Recorder implements AutoCloseable {
        final Path root;
        final ScreenRecorder.RealScreenRecorder value;
        final String previousOutbox;
        Recorder(EncodingMode mode) throws Exception { this(mode, true); }
        Recorder(EncodingMode mode, boolean mediaVideoEnabled) throws Exception {
            if (!System.getProperty("os.name").equals("Linux")) throw new SkipException("Synthetic shared-capture process check uses a POSIX shell");
            root = Files.createTempDirectory("shared-capture-admission");
            Path log = Files.createDirectories(root.resolve("logs"));
            Path ffmpeg = root.resolve("fake-ffmpeg");
            String control = root.toString().replace("'", "'\\''");
            Files.writeString(ffmpeg, "#!/bin/sh\ncontrol='" + control + "'\nstage1=false\nfor arg; do [ \"$arg\" = gdigrab ] && stage1=true; last=$arg; done\n"
                    + "if $stage1; then\nring=${last%/*}\nprintf segment > \"$ring/0.mov\"\nsleep 0.02\nprintf tail > \"$ring/1.mov\"\nwhile read line; do [ \"$line\" = q ] && exit 0; done\n"
                    + "else\nprintf '%s\\n' \"$@\" > \"$control/encode-args\"\nwhile [ ! -f \"$control/release\" ]; do sleep 0.02; done\nprintf full-original > \"$last\"\nfi\n");
            Files.setPosixFilePermissions(ffmpeg, PosixFilePermissions.fromString("rwx------"));
            previousOutbox = System.getProperty("helios.outbox.directory");
            System.setProperty("helios.outbox.directory", root.resolve("outbox").toString());
            value = new ScreenRecorder.RealScreenRecorder("local", log, 90, Set.of(Level.INFO, Level.WARN), root, ffmpeg, mode, false, mediaVideoEnabled);
        }
        JSONObject reservation() throws Exception {
            return new JSONObject(Files.readString(root.resolve("cloud-capture-admission/local.json")));
        }
        void release() throws Exception { Files.writeString(root.resolve("release"), "finish"); }
        @Override public void close() throws Exception {
            release();
            value.close();
            if (previousOutbox == null) System.clearProperty("helios.outbox.directory");
            else System.setProperty("helios.outbox.directory", previousOutbox);
        }
    }
}
