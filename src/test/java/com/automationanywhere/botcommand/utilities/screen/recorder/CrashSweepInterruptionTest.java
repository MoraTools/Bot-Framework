package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static screen.process.RecorderTestSupport.shellQuote;
import static screen.process.RecorderTestSupport.shellStub;

public class CrashSweepInterruptionTest {
    @Test
    public void interruptedScratchAndRingResumeFromTheClaimedFolder() throws Exception {
        RecorderTestSupport.requireShell();
        Path root = Files.createTempDirectory("sweep-interruption-");
        Thread sweep = null;
        Path pidFile = root.resolve("encoder-pid");
        try {
            Path logs = Files.createDirectories(root.resolve("logs"));
            Path session = stage(root, logs, "local");
            Files.createDirectories(session.resolve("ring"));
            Files.writeString(session.resolve("ring/0.mov"), "complete ring");
            Path claimed = session.resolveSibling("local.salvaging");
            Path ready = root.resolve("encoder-ready");
            Path encoder = shellStub(root.resolve("encoder"), "echo $$ > " + shellQuote(pidFile)
                    + "\nprintf ready > " + shellQuote(ready) + "\nexec sleep 30\n");
            AtomicBoolean interrupted = new AtomicBoolean();
            sweep = runSweep(root, encoder, interrupted);
            awaitReady(ready);
            sweep.interrupt();
            sweep.join(5000);
            Assert.assertFalse(sweep.isAlive());
            Assert.assertTrue(interrupted.get());
            assertEncoderKilled(pidFile);
            Assert.assertTrue(Files.exists(claimed.resolve("scratch/error/0.mov")));
            Assert.assertTrue(Files.exists(claimed.resolve("ring/0.mov")));

            Files.delete(ready);
            Files.delete(pidFile);
            shellStub(encoder, "case \"$last\" in */ring/*) echo $$ > " + shellQuote(pidFile) + "; printf ready > "
                    + shellQuote(ready) + "; exec sleep 30;; esac\nprintf encoded > \"$last\"\n");
            interrupted.set(false);
            sweep = runSweep(root, encoder, interrupted);
            awaitReady(ready);
            sweep.interrupt();
            sweep.join(5000);
            Assert.assertFalse(sweep.isAlive());
            Assert.assertTrue(interrupted.get());
            assertEncoderKilled(pidFile);
            Assert.assertEquals(Files.readString(logs.resolve("clips/error.mp4")), "encoded");
            Assert.assertFalse(Files.exists(claimed.resolve("scratch/error")));
            Assert.assertTrue(Files.exists(claimed.resolve("ring/0.mov")));
            Assert.assertTrue(Files.exists(claimed.resolve("session.active")),
                    "an interrupted ring salvage keeps the marker so that the next sweep resumes it");

            shellStub(encoder, "printf encoded > \"$last\"\n");
            CrashSweep.sweep(root, encoder);
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
        RecorderTestSupport.requireShell();
        Path root = Files.createTempDirectory("sweep-terminal-");
        try (AutoCloseable outboxProperty = RecorderTestSupport.useOutbox(root.resolve("outbox"))) {
            Path logs = Files.createDirectories(root.resolve("logs"));
            Path encoder = shellStub(root.resolve("encoder"), "exit 1\n");
            Path outbox = Files.createDirectories(root.resolve("outbox/media/partition"));
            for (String name : Set.of("corrupt", "empty", "missing-executable", "managed")) {
                Path session = stage(root, logs, name);
                Path scratch = session.resolve("scratch/error");
                if (name.equals("empty")) Files.delete(scratch.resolve("0.mov"));
                if (name.equals("managed")) {
                    Files.writeString(scratch.resolve("capture.json"), new JSONObject().put("captureId", "managed-error")
                            .put("target", logs.resolve("clips/error.mp4").toString()).toString());
                    Files.writeString(outbox.resolve("managed-video.json"), new JSONObject().put("captureId", "managed-error")
                            .put("kind", "video").put("state", "pending").put("completed", false).put("reserved", 0).toString());
                }
                CrashSweep.sweep(root, name.equals("missing-executable") ? root.resolve("missing") : encoder);
                Assert.assertFalse(Files.exists(session));
                Assert.assertFalse(Files.exists(session.resolveSibling(name + ".salvaging")));
                Assert.assertFalse(Files.exists(logs.resolve("clips/error.mp4")));
            }
            JSONObject result = new JSONObject(Files.readString(outbox.resolve("managed-video.json")));
            Assert.assertEquals(result.getString("state"), "unavailable");
            Assert.assertEquals(result.getString("reason"), "encodeFailed");
        } finally {
            RecorderTestSupport.deleteRecursivelyQuietly(root);
        }
    }

    private static Path stage(Path root, Path logs, String name) throws Exception {
        Path session = RecorderTestSupport.deadSession(root, logs, name, 3);
        Files.writeString(Files.createDirectories(session.resolve("scratch/error")).resolve("0.mov"), "synthetic segment");
        return session;
    }

    private static Thread runSweep(Path root, Path encoder, AtomicBoolean interrupted) {
        Thread thread = new Thread(() -> {
            CrashSweep.sweep(root, encoder);
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        return thread;
    }

    private static void awaitReady(Path ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(10);
        Assert.assertTrue(Files.exists(ready), "encoder must start before interruption");
    }

    /** The stub sleeps for 30 s, so a process that is still alive after 10 s was not killed by the sweep. */
    private static void assertEncoderKilled(Path pidFile) throws Exception {
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (isAlive(pid) && System.nanoTime() < deadline) Thread.sleep(20);
        boolean alive = isAlive(pid);
        if (alive) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        Assert.assertFalse(alive, "the encoder process " + pid + " must die when the sweep is interrupted");
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
