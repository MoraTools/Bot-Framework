package screen;

import com.automationanywhere.botcommand.utilities.screen.recorder.EncodingMode;
import com.automationanywhere.botcommand.utilities.screen.recorder.SessionMeta;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import support.TempDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class SessionMetaTest {

    private final TempDirectories temp = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        temp.deleteAll();
    }

    @Test
    public void roundTripPreservesAllFields() throws IOException {
        Instant started = Instant.parse("2026-05-10T10:00:00Z");
        SessionMeta original = new SessionMeta("session-uuid-abc", "C:\\bots\\bot-a\\logs", 30, started, 14728,
                EncodingMode.COMPACT);

        Path file = temp.create("session-meta-test-").resolve("session.meta");
        original.write(file);

        SessionMeta loaded = SessionMeta.read(file);
        Assert.assertEquals(loaded.sessionId, "session-uuid-abc");
        Assert.assertEquals(loaded.logDir, "C:\\bots\\bot-a\\logs");
        Assert.assertEquals(loaded.bufferSeconds, 30);
        Assert.assertEquals(loaded.startedAt, started);
        Assert.assertEquals(loaded.jvmPid, 14728L);
        Assert.assertEquals(loaded.encodingMode, EncodingMode.COMPACT);
    }

    @Test
    public void writeCreatesParentDirectories() throws IOException {
        SessionMeta meta = new SessionMeta("id", "C:\\logs", 30, Instant.now(), 1, EncodingMode.FAST);
        Path nested = temp.create("session-meta-test-").resolve("a/b/c/session.meta");
        meta.write(nested);

        Assert.assertTrue(Files.exists(nested));
    }
}
