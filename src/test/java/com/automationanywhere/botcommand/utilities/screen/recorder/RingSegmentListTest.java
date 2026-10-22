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
            capture = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-re",
                    "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=5", "-t", "12", "-c:v", "ffvhuff",
                    "-pix_fmt", "yuv420p", "-f", "segment", "-segment_time", "1", "-reset_timestamps", "1",
                    "-segment_wrap", "4", "-segment_list", ring.resolve("list.txt").toString(),
                    "-segment_list_size", "4", ring.resolve("%01d.mov").toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                if (Files.exists(ring.resolve("list.txt")) && Files.readAllLines(ring.resolve("list.txt")).size() == 4) break;
                Thread.sleep(10);
            }
            Assert.assertTrue(capture.isAlive(), "capture must still be running when the ring wraps");
            capture.destroyForcibly();
            Assert.assertTrue(capture.waitFor(5, TimeUnit.SECONDS));
            Assert.assertEquals(Files.readAllLines(ring.resolve("list.txt")), List.of("0.mov", "1.mov", "2.mov", "3.mov"));
            for (int slot = 0; slot < 4; slot++) Files.setLastModifiedTime(ring.resolve(slot + ".mov"), FileTime.fromMillis(1000));
            List<Path> completed = ClipFinalizer.completedRingSegments(ring, 3);
            Assert.assertEquals(completed, List.of(ring.resolve("1.mov"), ring.resolve("2.mov"), ring.resolve("3.mov")));
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
