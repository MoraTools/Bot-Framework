package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.apache.logging.log4j.Level;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;
import support.TempDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** FFmpeg output goes to a file: an encode failure still names its first lines, and a lost stage 1 stops recording. */
public class FfmpegOutputTest {
    private final TempDirectories temp = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        temp.deleteAll();
    }

    @Test
    public void aFailedEncodeReportsTheFirstOutputLinesWithoutBlockingOnALargeOutput() throws Exception {
        RecorderTestSupport.requireShell();
        Path root = temp.create("ffmpeg-output-encode");
        Files.writeString(root.resolve("0.mov"), "segment");
        // 12 short lines on stdout, then more than a 64 KB pipe buffer on stderr.
        Path encoder = RecorderTestSupport.shellStub(root.resolve("encoder"),
                "i=1; while [ $i -le 12 ]; do echo \"line $i\"; i=$((i+1)); done\n"
                        + "head -c 200000 /dev/zero | tr '\\0' x >&2\nexit 1\n");
        IOException failure = Assert.expectThrows(IOException.class,
                () -> ClipFinalizer.encodeSegmentsToMp4(encoder, root, root.resolve("clip.mp4"), EncodingMode.FAST));
        Assert.assertTrue(failure.getMessage().contains("exit code 1"), failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains("; tail: line 1 | line 2 |"), failure.getMessage());
        Assert.assertTrue(failure.getMessage().contains("| line 7 | line 8"), failure.getMessage());
        Assert.assertFalse(failure.getMessage().contains("line 9"), "only the first 8 lines are kept");
        Assert.assertFalse(Files.exists(root.resolve("clip.mp4")));
    }

    @Test
    public void aStageOneThatExitsUnexpectedlyStopsRecording() throws Exception {
        RecorderTestSupport.requireShell();
        Path root = temp.create("ffmpeg-output-stage1");
        Path log = Files.createDirectories(root.resolve("logs"));
        Path ffmpeg = RecorderTestSupport.shellStub(root.resolve("fake-ffmpeg"),
                "ring=${last%/*}\nprintf a > \"$ring/0.mov\"\nprintf b > \"$ring/1.mov\"\n"
                        + "printf '0.mov\\n' > \"$ring/list.txt\"\nsleep 0.5\necho 'capture device lost' >&2\nexit 3\n");
        ScreenRecorder.RealScreenRecorder recorder = new ScreenRecorder.RealScreenRecorder("lost", log, 30,
                Set.of(Level.ERROR), root, ffmpeg, EncodingMode.FAST, false);
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (recorder.shouldRecordFor(Level.ERROR) && System.nanoTime() < deadline) Thread.sleep(20);
            Assert.assertFalse(recorder.shouldRecordFor(Level.ERROR), "the exit must mark the recorder failed");
            Assert.assertEquals(recorder.snapshotForError("after-exit", ignored -> { }).unavailableReason,
                    "videoUnavailable");
            Assert.assertTrue(Files.readString(root.resolve("sessions/lost/stage1.log")).contains("capture device lost"));
        } finally {
            recorder.close();
        }
    }
}
