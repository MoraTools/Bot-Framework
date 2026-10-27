package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.testng.Assert;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.Permission;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static screen.process.RecorderTestSupport.deadSession;
import static screen.process.RecorderTestSupport.shellStub;

public class CrashSweepRegressionTest {
    @Test
    public void ringSalvageUsesThePublishedListAndDoesNotRepeatWhileMediaIsPending() throws Exception {
        RecorderTestSupport.requireShell();
        Path root = Files.createTempDirectory("sweep-ring-once");
        try {
            Path logs = Files.createDirectories(root.resolve("logs"));
            Path session = deadSession(root, logs, "once", 3);
            Path ring = Files.createDirectories(session.resolve("ring"));
            for (int slot = 0; slot < 4; slot++) Files.writeString(ring.resolve(slot + ".mov"), "segment");
            RecorderTestSupport.publishSegmentList(ring, "3.mov", "0.mov", "1.mov", "2.mov");
            // This damaged managed scratch retains the folder and makes the next sweep enter trySalvage again.
            Path pending = Files.createDirectories(session.resolve("scratch/pending"));
            Files.writeString(pending.resolve("capture.json"), "{damaged");
            Path encoder = shellStub(root.resolve("encoder"),
                    "for arg; do [ \"$previous\" = -i ] && input=$arg; previous=$arg; done\ncat \"$input\" > \"$last\"\n");

            CrashSweep.sweep(root, encoder);

            Path claimed = session.resolveSibling("once.salvaging");
            Assert.assertTrue(Files.isDirectory(claimed));
            Assert.assertFalse(Files.exists(claimed.resolve("session.active")), "completed salvage clears the marker");
            Path video = logs.resolve("clips/crash-recording-once.mp4");
            String concat = Files.readString(video);
            Assert.assertTrue(concat.contains("0.mov"));
            Assert.assertTrue(concat.contains("1.mov"));
            Assert.assertTrue(concat.contains("2.mov"));
            Assert.assertFalse(concat.contains("3.mov"), "the next ring slot can already be open");
            String summary = Files.readString(logs.resolve("clips/crash-log.txt"));
            Files.writeString(video, "do not replace");

            CrashSweep.sweep(root, encoder);

            Assert.assertEquals(Files.readString(video), "do not replace", "pending media must not re-encode the ring");
            Assert.assertEquals(Files.readString(logs.resolve("clips/crash-log.txt")), summary);
            Assert.assertTrue(Files.exists(claimed.resolve("scratch/pending/capture.json")));
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(root); }
    }

    @Test
    public void anInterruptedSweepKeepsTheActiveMarkerEvenWhenThereIsNoEncoderToWaitFor() throws Exception {
        Path root = Files.createTempDirectory("sweep-interrupted-marker");
        SecurityManager previous = System.getSecurityManager();
        Thread testThread = Thread.currentThread();
        try {
            Path session = deadSession(root, root, "interrupted", 3);
            Path claimed = session.resolveSibling("interrupted.salvaging");
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(Permission permission) { }
                @Override public void checkRead(String file) {
                    if (Thread.currentThread() == testThread && file.equals(claimed.resolve("ring").toString()))
                        testThread.interrupt();
                }
            });
            try { CrashSweep.sweep(root, root.resolve("unused")); }
            finally { Thread.interrupted(); }
            Assert.assertTrue(Files.exists(claimed.resolve("session.active")));
        } finally {
            System.setSecurityManager(previous);
            Thread.interrupted();
            RecorderTestSupport.deleteRecursivelyQuietly(root);
        }
    }

    @Test
    public void partialDeleteOrphansAreClaimed() throws Exception {
        Path root = Files.createTempDirectory("sweep-missing-meta");
        try {
            for (String name : List.of("old", "claimed.salvaging", "starting")) {
                Path session = Files.createDirectories(root.resolve("sessions").resolve(name));
                Files.writeString(session.resolve("0.mov"), "partial-delete residue");
                if (name.equals("old")) Files.setLastModifiedTime(session,
                        FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));
            }

            CrashSweep.sweep(root, root.resolve("unused"));

            for (String name : List.of("old", "claimed")) {
                Assert.assertFalse(Files.exists(root.resolve("sessions").resolve(name)));
                Assert.assertFalse(Files.exists(root.resolve("sessions").resolve(name + ".salvaging")));
            }
            Assert.assertTrue(Files.exists(root.resolve("sessions/starting/0.mov")), "a recorder can still be starting");
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(root); }
    }

    @Test
    public void failedSegmentDeleteKeepsTheMetaUntilTheOtherFilesAreGone() throws Exception {
        Path root = Files.createTempDirectory("sweep-delete-order");
        SecurityManager previous = System.getSecurityManager();
        Thread testThread = Thread.currentThread();
        Path segment = Files.writeString(root.resolve("0.mov"), "held open on Windows");
        Path meta = Files.writeString(root.resolve("session.meta"), "owner metadata");
        try {
            System.setSecurityManager(new SecurityManager() {
                @Override public void checkPermission(Permission permission) { }
                @Override public void checkDelete(String file) {
                    if (Thread.currentThread() == testThread && file.equals(segment.toString()))
                        throw new SecurityException("synthetic delete denial");
                }
            });
            // SecurityException is not an IOException. The test checks metadata even if the denial propagates.
            try { ScreenRecorder.deleteRecursivelyQuietly(root); } catch (SecurityException expected) { }
            Assert.assertTrue(Files.exists(meta), "owner metadata must survive a failed segment delete");
        } finally { System.setSecurityManager(previous); }
        try {
            ScreenRecorder.deleteRecursivelyQuietly(root);
            Assert.assertFalse(Files.exists(root));
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(root); }
    }

    @Test
    public void aRuntimeFailureInRingSalvageDoesNotStopTheSweep() throws Exception {
        Path root = Files.createTempDirectory("sweep-runtime");
        SecurityManager previous = System.getSecurityManager();
        Thread testThread = Thread.currentThread();
        try {
            Path session = deadSession(root, root, "runtime", 3);
            Path ring = Files.createDirectories(session.resolve("ring"));
            Files.writeString(ring.resolve("0.mov"), "segment");
            RecorderTestSupport.publishSegmentList(ring, "0.mov");
            String list = session.resolveSibling("runtime.salvaging").resolve("ring/list.txt").toString();
            System.setSecurityManager(new SecurityManager() {
                boolean failed;
                @Override public void checkPermission(Permission permission) { }
                @Override public void checkRead(String file) {
                    if (!failed && Thread.currentThread() == testThread && file.equals(list)) {
                        failed = true;
                        throw new SecurityException("synthetic list failure");
                    }
                }
            });

            CrashSweep.sweep(root, root.resolve("unused"));

            Assert.assertFalse(Files.exists(session.resolveSibling("runtime.salvaging")),
                    "a failed ring encode is terminal, not a failed sweep thread");
        } finally {
            System.setSecurityManager(previous);
            RecorderTestSupport.deleteRecursivelyQuietly(root);
        }
    }
}
