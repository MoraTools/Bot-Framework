package screen.process;

import com.automationanywhere.botcommand.utilities.screen.recorder.ClipFinalizer;
import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;

public class ClipFinalizerCommandTest {
    @Test
    public void preservesExactArgumentsForBothModesAndNullFallback() throws Exception {
        Path ffmpeg = Path.of("encoder folder", "ffmpeg.exe");
        Path input = Path.of("segment folder", "concat-list.txt");
        Path output = Path.of("segment folder", "out.mp4.tmp");
        Method build = ClipFinalizer.class.getDeclaredMethod("buildEncodeCommand",
                Path.class, Path.class, Path.class, EncodingMode.class);
        build.setAccessible(true);
        String[] fast = {
                ffmpeg.toAbsolutePath().toString(), "-hide_banner", "-loglevel", "error",
                "-f", "concat", "-safe", "0", "-fflags", "+igndts+genpts",
                "-i", input.toAbsolutePath().toString(), "-c:v", "libx264",
                "-preset", "ultrafast", "-crf", "23", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", "-f", "mp4", output.toAbsolutePath().toString()
        };
        String[] compact = {
                ffmpeg.toAbsolutePath().toString(), "-hide_banner", "-loglevel", "error",
                "-f", "concat", "-safe", "0", "-fflags", "+igndts+genpts",
                "-i", input.toAbsolutePath().toString(), "-c:v", "libaom-av1",
                "-b:v", "1500k", "-cpu-used", "8", "-row-mt", "1",
                "-tile-columns", "2", "-g", "60", "-pix_fmt", "yuv420p",
                "-movflags", "+faststart", "-f", "mp4", output.toAbsolutePath().toString()
        };
        Assert.assertEquals((String[]) build.invoke(null, ffmpeg, input, output, EncodingMode.FAST), fast);
        Assert.assertEquals((String[]) build.invoke(null, ffmpeg, input, output, EncodingMode.COMPACT), compact);
        Assert.assertEquals((String[]) build.invoke(null, ffmpeg, input, output, null), compact);
    }
}
