package device;

import com.automationanywhere.botcommand.actions.device.CloseApplications;
import com.automationanywhere.botcommand.data.impl.StringValue;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import support.TempDirectories;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class CloseApplicationsTest {
    private final TempDirectories temp = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteTemporaryFolders() {
        temp.deleteAll();
    }

    @Test
    public void processThatIgnoresACloseRequestIsTerminatedAtOnce() throws Exception {
        Path shell = Path.of("/bin/sh");
        if (!Files.isExecutable(shell)) {
            throw new SkipException("No /bin/sh to copy");
        }
        // The command matches by base name: the unique name keeps every other process safe.
        String name = "bf-close-test-" + UUID.randomUUID();
        Path executable = Files.copy(shell, temp.create("close-applications").resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
        Process process;
        try {
            process = new ProcessBuilder(executable.toString(), "-c", "trap '' TERM; echo ready; read line").start();
        } catch (IOException e) {
            throw new SkipException("Cannot run a copied executable here", e);
        }
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Assert.assertEquals(output.readLine(), "ready");
            long start = System.nanoTime();

            new CloseApplications().action(List.of(new StringValue(name)));

            // A close request that waits for the process takes 30 s: half of it proves one hard kill.
            Assert.assertTrue(System.nanoTime() - start < 15_000_000_000L, "the command waited for a close request");
            Assert.assertTrue(process.waitFor(5, TimeUnit.SECONDS), "the process is still running");
        } finally {
            process.destroyForcibly();
        }
    }
}
