package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CrashSweepSafetyTest {
    @Test
    public void anotherLiveJvmKeepsItsNormalAndClaimedSessions() throws Exception {
        Path root = Files.createTempDirectory("sweep-live-safety");
        Process child = child("owner");
        try {
            Path normal = session(root, "live", child.pid());
            Path claimed = session(root, "live.salvaging", child.pid());
            CrashSweep.sweepForTest(root, Path.of("unused-ffmpeg"));
            Assert.assertTrue(child.isAlive());
            Assert.assertTrue(Files.exists(normal.resolve("data")));
            Assert.assertTrue(Files.exists(claimed.resolve("data")));
        } finally { stop(child); }
    }

    @Test
    public void competingSweepCannotDeleteAClaimedFolder() throws Exception {
        Path root = Files.createTempDirectory("sweep-lock-safety");
        Process owner = child("owner");
        long deadPid = owner.pid();
        stop(owner);
        Path claimed = session(root, "old.salvaging", deadPid);
        Process sweeper = child(root.resolve("sessions/.sweep.lock").toString());
        try {
            CrashSweep.sweepForTest(root, Path.of("unused-ffmpeg"));
            Assert.assertTrue(Files.exists(claimed.resolve("data")));
        } finally { stop(sweeper); }
        CrashSweep.sweepForTest(root, Path.of("unused-ffmpeg"));
        Assert.assertFalse(Files.exists(claimed));
    }

    @Test
    public void deadOwnersAreCleanedAndUnknownOwnersArePreserved() throws Exception {
        Path root = Files.createTempDirectory("sweep-dead-safety");
        Process owner = child("owner");
        long deadPid = owner.pid();
        stop(owner);
        Path dead = session(root, "dead", deadPid);
        Path unknown = Files.createDirectory(root.resolve("sessions/unknown"));
        Files.writeString(unknown.resolve("data"), "keep");
        CrashSweep.sweepForTest(root, Path.of("unused-ffmpeg"));
        Assert.assertFalse(Files.exists(dead));
        Assert.assertTrue(Files.exists(unknown.resolve("data")));
    }

    private static Path session(Path root, String name, long pid) throws Exception {
        Path folder = Files.createDirectories(root.resolve("sessions").resolve(name));
        new SessionMeta(name, root.toString(), 5, Collections.emptySet(), Instant.now(), "test", pid,
                EncodingMode.FAST).write(folder.resolve("session.meta"));
        Files.writeString(folder.resolve("data"), "synthetic recording data");
        return folder;
    }

    private static Process child(String mode) throws Exception {
        List<String> paths = new ArrayList<>();
        for (Class<?> type : new Class<?>[] { CrashSweepSafetyTest.class, SessionMeta.class, JSONObject.class, Assert.class })
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(executable, "-cp", String.join(java.io.File.pathSeparator, paths),
                CrashSweepSafetyTest.class.getName(), mode).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        Assert.assertEquals(new BufferedReader(new InputStreamReader(process.getInputStream())).readLine(), "ready");
        return process;
    }

    private static void stop(Process process) throws Exception {
        process.getOutputStream().close();
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("owner")) {
            System.out.println("ready"); System.out.flush(); System.in.read();
        } else {
            try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                System.out.println("ready"); System.out.flush(); System.in.read();
            }
        }
    }
}
