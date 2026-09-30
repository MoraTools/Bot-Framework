package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class ClipFinalizerSizeTest {
    @Test public void nativeMp4LimitNeverPublishesATruncatedCloudClip() throws Exception {
        Path ffmpeg = Path.of("/usr/bin/ffmpeg");
        if (!Files.isExecutable(ffmpeg)) throw new SkipException("Linux synthetic muxer check needs /usr/bin/ffmpeg; Windows acceptance is separate");
        Path scratch = Files.createTempDirectory("cloud-clip-size");
        Process source = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc2=size=640x480:rate=5", "-t", "2", "-c:v", "ffvhuff", "-pix_fmt", "yuv420p",
                scratch.resolve("1.mov").toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        Assert.assertTrue(source.waitFor(10, TimeUnit.SECONDS));
        Assert.assertEquals(source.exitValue(), 0);
        Path target = scratch.resolve("completed.mp4");
        try {
            ClipFinalizer.encodeSegmentsToMp4(ffmpeg, scratch, target, EncodingMode.FAST, 1);
            Assert.fail("a truncated encode must report TooLarge");
        } catch (ClipFinalizer.TooLargeException expected) { Assert.assertFalse(Files.exists(target)); }
        ClipFinalizer.encodeSegmentsToMp4(ffmpeg, scratch, target, EncodingMode.FAST);
        Assert.assertTrue(Files.size(target) > 1);
    }
}
