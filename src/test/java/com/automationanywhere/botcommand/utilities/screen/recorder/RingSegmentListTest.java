package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RingSegmentListTest {
    @Test
    public void nativeWrappedRingKeepsThreeCompleteSecondsWithTiedMtimesAndEscapedPaths() throws Exception {
        Path ffmpeg = Path.of("/usr/bin/ffmpeg");
        Path ffprobe = Path.of("/usr/bin/ffprobe");
        if (!Files.isExecutable(ffmpeg) || !Files.isExecutable(ffprobe))
            throw new SkipException("Synthetic segment-list check requires host FFmpeg and FFprobe");
        Path root = Files.createTempDirectory("segment list's native-");
        Process capture = null;
        try {
            Path ring = Files.createDirectory(root.resolve("ring"));
            List<String> command = new java.util.ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-re",
                    "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=5", "-t", "30"));
            command.addAll(ScreenRecorder.ringOutputArguments(ring, 4));
            capture = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            List<String> wrapped = List.of("1.mov", "2.mov", "3.mov", "0.mov");
            while (System.nanoTime() < deadline) {
                if (Files.exists(ring.resolve(ClipFinalizer.MANIFEST))
                        && Files.readAllLines(ring.resolve(ClipFinalizer.MANIFEST)).equals(wrapped)) break;
                Thread.sleep(10);
            }
            Assert.assertTrue(capture.isAlive(), "capture must still be running when the ring wraps");
            capture.destroyForcibly();
            Assert.assertTrue(capture.waitFor(5, TimeUnit.SECONDS));
            Assert.assertEquals(Files.readAllLines(ring.resolve(ClipFinalizer.MANIFEST)), wrapped,
                    "slot 0 must have been reused, not only opened for the first time");
            for (int slot = 0; slot < 4; slot++) Files.setLastModifiedTime(ring.resolve(slot + ".mov"), FileTime.fromMillis(1000));
            List<Path> completed = ClipFinalizer.completedRingSegments(ring, 3);
            Assert.assertEquals(completed, List.of(ring.resolve("2.mov"), ring.resolve("3.mov"), ring.resolve("0.mov")));
            Path scratch = Files.createDirectory(root.resolve("scratch"));
            for (Path segment : completed) Files.copy(segment, scratch.resolve(segment.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
            ClipFinalizer.writeSegmentOrder(scratch, completed);
            Path target = root.resolve("complete.mp4");
            ClipFinalizer.encodeSegmentsToMp4(ffmpeg, scratch, target, EncodingMode.FAST);
            Process probe = new ProcessBuilder(ffprobe.toString(), "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", target.toString()).start();
            Assert.assertTrue(probe.waitFor(5, TimeUnit.SECONDS));
            Assert.assertEquals(probe.exitValue(), 0);
            double duration = Double.parseDouble(new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim());
            Assert.assertTrue(duration >= 2.8 && duration <= 3.2, "all three completed seconds must survive: " + duration);
        } finally {
            if (capture != null) { capture.destroyForcibly(); capture.waitFor(5, TimeUnit.SECONDS); }
            RecorderTestSupport.deleteRecursivelyQuietly(root);
        }
    }
}
