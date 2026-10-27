package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;
import support.ChildJvm;
import support.Polling;
import support.TempDirectories;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** The stage-1 shutdown hook stops FFmpeg when the JVM exits without close(). */
public class StageOneShutdownTest {
    private final TempDirectories temp = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        temp.deleteAll();
    }

    @Test
    public void theHookIsRegisteredWhileRecordingAndCloseRemovesIt() throws Exception {
        RecorderTestSupport.requireShell();
        ScreenRecorder.RealScreenRecorder recorder = stubRecorder(temp.create("stage1-hook"));
        try {
            Assert.assertThrows(IllegalArgumentException.class, () -> Runtime.getRuntime().addShutdownHook(recorder.stage1Hook));
        } finally {
            recorder.close();
        }
        Assert.assertFalse(Runtime.getRuntime().removeShutdownHook(recorder.stage1Hook), "close() must remove the hook");
    }

    @Test
    public void theHookStopsALiveStageOne() throws Exception {
        Process child = new ProcessBuilder(ChildJvm.command(StageOneShutdownTest.class,
                ChildJvm.classPath(StageOneShutdownTest.class, Assert.class), List.of(), "wait"))
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            Assert.assertEquals(new BufferedReader(new InputStreamReader(child.getInputStream())).readLine(), "ready");
            ScreenRecorder.RealScreenRecorder.stage1ShutdownHook(child).run();
            Assert.assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the hook must stop the process");
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    public void aJvmThatExitsWithoutCloseStopsStageOne() throws Exception {
        RecorderTestSupport.requireShell();
        Path root = temp.create("stage1-exit");
        Path pidFile = root.resolve("stage1.pid");
        Path ffmpeg = RecorderTestSupport.shellStub(root.resolve("fake-ffmpeg"), "echo $$ > "
                + RecorderTestSupport.shellQuote(pidFile) + "\n" + WARM_RING + "exec sleep 60\n");
        Process child = new ProcessBuilder(ChildJvm.command(StageOneShutdownTest.class,
                ChildJvm.classPath(StageOneShutdownTest.class, ScreenRecorder.class, LogManager.class,
                        org.apache.logging.log4j.core.LoggerContext.class, JSONObject.class, Assert.class),
                List.of(), "exit", root.toString(), ffmpeg.toString()))
                .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
        try {
            Assert.assertTrue(child.waitFor(30, TimeUnit.SECONDS), "the child JVM must exit");
            Assert.assertEquals(child.exitValue(), 0, Files.readString(root.resolve("child.log")));
            Polling.await(() -> stageOne(pidFile).map(handle -> !handle.isAlive()).orElse(true));
        } finally {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
            stageOne(pidFile).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static Optional<ProcessHandle> stageOne(Path pidFile) {
        try { return ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())); }
        catch (java.io.IOException | NumberFormatException notStarted) { return Optional.empty(); }
    }

    /** Writes two ring segments so the warm-up wait ends at once. */
    private static final String WARM_RING = "ring=${last%/*}\nprintf a > \"$ring/0.mov\"\nprintf b > \"$ring/1.mov\"\n";

    /** A recorder whose stand-in stage 1 warms the ring, then waits for stdin to close. */
    private static ScreenRecorder.RealScreenRecorder stubRecorder(Path root) throws Exception {
        Path log = Files.createDirectories(root.resolve("logs"));
        Path ffmpeg = RecorderTestSupport.shellStub(root.resolve("fake-ffmpeg"), WARM_RING + "read line\n");
        return new ScreenRecorder.RealScreenRecorder("stub", log, 30, Set.of(Level.ERROR), root, ffmpeg,
                EncodingMode.FAST, false);
    }

    /** Child JVM: {@code wait} waits for stdin to close; {@code exit} starts a recorder and exits without close(). */
    public static void main(String[] args) throws Exception {
        if (args[0].equals("wait")) {
            System.out.println("ready");
            System.out.flush();
            System.in.read();
            return;
        }
        Path root = Path.of(args[1]);
        new ScreenRecorder.RealScreenRecorder("exit", Files.createDirectories(root.resolve("logs")), 30,
                Set.of(Level.ERROR), root, Path.of(args[2]), EncodingMode.FAST, false);
        System.exit(0);
    }
}
