package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.json.JSONObject;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class CrashSweepInterruptionTest {
    @Test
    public void interruptedScratchAndRingResumeFromTheClaimedFolder() throws Exception {
        requireShell();
        Path root = Files.createTempDirectory("sweep-interruption-");
        Thread sweep = null;
        try {
            Path logs = Files.createDirectories(root.resolve("logs"));
            Path session = stage(root, logs, "local");
            Files.createDirectories(session.resolve("ring"));
            Files.writeString(session.resolve("ring/0.mov"), "complete ring");
            Path claimed = session.resolveSibling("local.salvaging");
            Path ready = root.resolve("encoder-ready");
            Path encoder = encoder(root, "printf ready > " + quote(ready) + "\nexec sleep 30\n");
            AtomicBoolean interrupted = new AtomicBoolean();
            sweep = runSweep(root, encoder, interrupted);
            awaitReady(ready);
            sweep.interrupt();
            sweep.join(5000);
            Assert.assertFalse(sweep.isAlive());
            Assert.assertTrue(interrupted.get());
            Assert.assertTrue(Files.exists(claimed.resolve("scratch/error/0.mov")));
            Assert.assertTrue(Files.exists(claimed.resolve("ring/0.mov")));

            Files.delete(ready);
            encoder(root, "case \"$last\" in */ring/*) printf ready > " + quote(ready)
                    + "; exec sleep 30;; esac\nprintf encoded > \"$last\"\n");
            interrupted.set(false);
            sweep = runSweep(root, encoder, interrupted);
            awaitReady(ready);
            sweep.interrupt();
            sweep.join(5000);
            Assert.assertFalse(sweep.isAlive());
            Assert.assertTrue(interrupted.get());
            Assert.assertEquals(Files.readString(logs.resolve("clips/error.mp4")), "encoded");
            Assert.assertFalse(Files.exists(claimed.resolve("scratch/error")));
            Assert.assertTrue(Files.exists(claimed.resolve("ring/0.mov")));

            encoder(root, "printf encoded > \"$last\"\n");
            CrashSweep.sweepForTest(root, encoder);
            Assert.assertEquals(Files.readString(logs.resolve("clips/error.mp4")), "encoded");
            Assert.assertEquals(Files.readString(logs.resolve("clips/crash-recording-local.mp4")), "encoded");
            Assert.assertFalse(Files.exists(claimed));
        } finally {
            if (sweep != null && sweep.isAlive()) { sweep.interrupt(); sweep.join(5000); }
            RecorderTestSupport.deleteRecursivelyQuietly(root);
        }
    }

    @Test
    public void terminalFailuresCleanLocalSourcesAndPublishManagedFailure() throws Exception {
        requireShell();
        Path root = Files.createTempDirectory("sweep-terminal-");
        String previous = System.getProperty("helios.outbox.directory");
        try {
            Path logs = Files.createDirectories(root.resolve("logs"));
            Path encoder = encoder(root, "exit 1\n");
            Path outbox = Files.createDirectories(root.resolve("outbox/media/partition"));
            System.setProperty("helios.outbox.directory", root.resolve("outbox").toString());
            for (String name : Set.of("corrupt", "empty", "missing-executable", "managed")) {
                Path session = stage(root, logs, name);
                Path scratch = session.resolve("scratch/error");
                if (name.equals("empty")) Files.delete(scratch.resolve("0.mov"));
                if (name.equals("managed")) {
                    Files.writeString(scratch.resolve("capture.json"), new JSONObject().put("captureId", "managed-error")
                            .put("target", logs.resolve("clips/error.mp4").toString()).put("localRequired", true)
                            .put("cloudOnly", false).toString());
                    Files.writeString(outbox.resolve("managed-video.json"), new JSONObject().put("captureId", "managed-error")
                            .put("kind", "video").put("state", "pending").put("completed", false).put("reserved", 0).toString());
                }
                CrashSweep.sweepForTest(root, name.equals("missing-executable") ? root.resolve("missing") : encoder);
                Assert.assertFalse(Files.exists(session));
                Assert.assertFalse(Files.exists(session.resolveSibling(name + ".salvaging")));
                Assert.assertFalse(Files.exists(logs.resolve("clips/error.mp4")));
            }
            JSONObject result = new JSONObject(Files.readString(outbox.resolve("managed-video.json")));
            Assert.assertEquals(result.getString("state"), "unavailable");
            Assert.assertEquals(result.getString("reason"), "encodeFailed");
        } finally {
            if (previous == null) System.clearProperty("helios.outbox.directory");
            else System.setProperty("helios.outbox.directory", previous);
            RecorderTestSupport.deleteRecursivelyQuietly(root);
        }
    }

    private static Path stage(Path root, Path logs, String name) throws Exception {
        Path session = Files.createDirectories(root.resolve("sessions").resolve(name));
        Path scratch = Files.createDirectories(session.resolve("scratch/error"));
        Files.writeString(scratch.resolve("0.mov"), "synthetic segment");
        new SessionMeta(name, logs.toString(), 3, Set.of(), Instant.now(), "test", Long.MAX_VALUE,
                EncodingMode.FAST).write(session.resolve("session.meta"));
        Files.createFile(session.resolve("session.active"));
        return session;
    }

    private static Path encoder(Path root, String body) throws Exception {
        Path executable = root.resolve("encoder");
        Files.writeString(executable, "#!/bin/sh\nfor last; do :; done\n" + body);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }

    private static Thread runSweep(Path root, Path encoder, AtomicBoolean interrupted) {
        Thread thread = new Thread(() -> {
            CrashSweep.sweepForTest(root, encoder);
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        return thread;
    }

    private static void awaitReady(Path ready) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(10);
        Assert.assertTrue(Files.exists(ready), "encoder must start before interruption");
    }

    private static String quote(Path path) { return "'" + path.toString().replace("'", "'\\''") + "'"; }

    private static void requireShell() {
        if (!Files.isExecutable(Path.of("/bin/sh"))) throw new SkipException("Synthetic interruption fixture requires POSIX shell");
    }
}
