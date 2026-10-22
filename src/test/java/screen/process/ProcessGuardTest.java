package screen.process;

import com.automationanywhere.botcommand.utilities.screen.recorder.ProcessGuard;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class ProcessGuardTest {

    @Test
    public void spawnReturnsAliveProcess() throws IOException, InterruptedException {
        try (ProcessGuard guard = new ProcessGuard()) {
            Process p = spawnWaitingChild(guard);
            Assert.assertTrue(p.isAlive(), "spawned process should be alive");
            // Cleanup
            p.destroyForcibly();
            p.waitFor(3, TimeUnit.SECONDS);
        }
    }

    @Test
    public void closeKillsStragglers() throws IOException, InterruptedException {
        ProcessGuard guard = new ProcessGuard();
        Process p = spawnWaitingChild(guard);
        Assert.assertTrue(p.isAlive(), "spawned process should start alive");

        guard.close();

        // close() does destroyForcibly() on any tracked-and-alive child
        boolean exited = p.waitFor(3, TimeUnit.SECONDS);
        Assert.assertTrue(exited, "child should be dead within 3s of guard.close()");
        Assert.assertFalse(p.isAlive());
    }

    @Test(expectedExceptions = IOException.class)
    public void spawnAfterCloseThrows() throws IOException {
        ProcessGuard guard = new ProcessGuard();
        guard.close();
        // Should fail-fast - guard is no longer accepting new spawns
        guard.spawn(buildWaitingChildArgs(), null);
    }

    @Test
    public void multipleGuardsAreIndependent() throws IOException, InterruptedException {
        ProcessGuard a = new ProcessGuard();
        ProcessGuard b = new ProcessGuard();
        Process pa = spawnWaitingChild(a);
        Process pb = spawnWaitingChild(b);
        try {
            Assert.assertTrue(pa.isAlive());
            Assert.assertTrue(pb.isAlive());

            a.close();
            Assert.assertTrue(pa.waitFor(3, TimeUnit.SECONDS),
                    "guard A's child should die when A closes");
            Assert.assertTrue(pb.isAlive(),
                    "guard B's child must remain alive when only A closes");
        } finally {
            a.close();
            b.close();
            pb.waitFor(3, TimeUnit.SECONDS);
        }
    }

    private static Process spawnWaitingChild(ProcessGuard guard) throws IOException {
        Process child = guard.spawn(buildWaitingChildArgs(), null);
        Assert.assertEquals(new BufferedReader(new InputStreamReader(child.getInputStream())).readLine(), "ready");
        return child;
    }

    private static String[] buildWaitingChildArgs() throws IOException {
        try {
            String classes = Path.of(ProcessGuardTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            String testng = Path.of(Assert.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            Path executable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            return new String[] { executable.toString(), "-cp", classes + java.io.File.pathSeparator + testng,
                    ProcessGuardTest.class.getName() };
        } catch (java.net.URISyntaxException invalid) { throw new IOException(invalid); }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("ready");
        System.out.flush();
        System.in.read();
    }
}
