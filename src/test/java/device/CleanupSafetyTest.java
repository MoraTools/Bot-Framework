package device;

import com.automationanywhere.botcommand.actions.device.DeleteFilesFolders;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class CleanupSafetyTest {
    private static void old(Path path) throws Exception {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));
    }

    @Test
    public void nonRecursiveCleanupPreservesExcludedAndYoungDescendants() throws Exception {
        Path root = Files.createTempDirectory("cleanup-safety");
        Path excluded = Files.createDirectory(root.resolve("excluded"));
        Path keep = Files.writeString(excluded.resolve("keep.log"), "protected");
        old(keep); old(excluded);
        Path young = Files.createDirectory(root.resolve("young"));
        Files.writeString(young.resolve("recent.txt"), "recent");
        old(young);
        Path eligible = Files.createDirectories(root.resolve("eligible/nested"));
        Path delete = Files.writeString(eligible.resolve("old.txt"), "old");
        old(delete); old(eligible); old(eligible.getParent());
        new DeleteFilesFolders().action(root.toString(), "ALL", false, 30, "DAY", "MODIFICATION",
                false, "", true, ".*\\.log$", "THROW");
        Assert.assertTrue(Files.exists(keep));
        Assert.assertTrue(Files.exists(young.resolve("recent.txt")));
        Assert.assertFalse(Files.exists(root.resolve("eligible")));
        Assert.assertTrue(Files.exists(root));
    }

    @Test
    public void recursiveCleanupKeepsYoungDirectoriesAndSkippedSubtrees() throws Exception {
        Path root = Files.createTempDirectory("cleanup-recursive-safety");
        Path parent = Files.createDirectory(root.resolve("parent"));
        Path emptyYoung = Files.createDirectory(parent.resolve("young-empty"));
        Path protectedDir = Files.createDirectory(parent.resolve("protected"));
        Path keep = Files.writeString(protectedDir.resolve("old.txt"), "protected");
        Path remove = Files.writeString(parent.resolve("remove.txt"), "old");
        old(keep); old(protectedDir); old(remove); old(parent);
        new DeleteFilesFolders().action(root.toString(), "ALL", true, 30, "DAY", "MODIFICATION",
                true, ".*protected$", false, "", "THROW");
        Assert.assertTrue(Files.exists(keep));
        Assert.assertTrue(Files.isDirectory(emptyYoung));
        Assert.assertFalse(Files.exists(remove));
        Assert.assertTrue(Files.exists(parent));
    }

    @Test
    public void filesOnlyNonRecursiveCleanupDoesNotTouchDescendants() throws Exception {
        Path root = Files.createTempDirectory("cleanup-files-only-safety");
        Path nested = Files.createDirectory(root.resolve("nested"));
        Path keep = Files.writeString(nested.resolve("old.txt"), "keep");
        Path remove = Files.writeString(root.resolve("old.txt"), "remove");
        old(keep); old(remove); old(nested);
        new DeleteFilesFolders().action(root.toString(), "FILE", false, 30, "DAY", "MODIFICATION",
                false, "", false, "", "THROW");
        Assert.assertTrue(Files.exists(keep));
        Assert.assertFalse(Files.exists(remove));
    }
}
