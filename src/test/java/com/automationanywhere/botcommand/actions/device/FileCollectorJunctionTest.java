package com.automationanywhere.botcommand.actions.device;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import support.TempDirectories;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

public class FileCollectorJunctionTest {
    private static final FileTime OLD = FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS));
    private final TempDirectories temp = new TempDirectories();

    @AfterClass(alwaysRun = true)
    public void deleteTemporaryFolders() {
        temp.deleteAll();
    }

    private static BasicFileAttributes oldDirectory(boolean other) {
        return new BasicFileAttributes() {
            public FileTime lastModifiedTime() { return OLD; }
            public FileTime lastAccessTime() { return OLD; }
            public FileTime creationTime() { return OLD; }
            public boolean isRegularFile() { return false; }
            public boolean isDirectory() { return true; }
            public boolean isSymbolicLink() { return false; }
            public boolean isOther() { return other; }
            public long size() { return 0; }
            public Object fileKey() { return null; }
        };
    }

    private static DeleteFilesFolders.FileCollector collector(Path base) {
        return new DeleteFilesFolders.FileCollector(base, true, "MODIFICATION",
                Instant.now().minus(30, ChronoUnit.DAYS), false, "", false, "");
    }

    @Test
    public void junctionBelowTheBaseFolderIsSkippedAndNotEntered() {
        Path base = Path.of("base");
        Path junction = base.resolve("junction");
        DeleteFilesFolders.FileCollector collector = collector(base);
        Assert.assertEquals(collector.preVisitDirectory(junction, oldDirectory(true)), FileVisitResult.SKIP_SUBTREE);
        Assert.assertTrue(collector.directoriesToSkip.contains(junction));
        Assert.assertFalse(collector.directoriesToDelete.contains(junction));
    }

    @Test
    public void junctionGivenAsTheBaseFolderIsEntered() {
        Path base = Path.of("base");
        DeleteFilesFolders.FileCollector collector = collector(base);
        Assert.assertEquals(collector.preVisitDirectory(base, oldDirectory(true)), FileVisitResult.CONTINUE);
        Assert.assertTrue(collector.directoriesToSkip.isEmpty());
        Assert.assertTrue(collector.directoriesToDelete.isEmpty());
    }

    @Test
    public void oldNormalDirectoryIsStillMarkedForDeletion() {
        Path base = Path.of("base");
        Path directory = base.resolve("old");
        DeleteFilesFolders.FileCollector collector = collector(base);
        Assert.assertEquals(collector.preVisitDirectory(directory, oldDirectory(false)), FileVisitResult.CONTINUE);
        Assert.assertTrue(collector.directoriesToDelete.contains(directory));
        Assert.assertFalse(collector.directoriesToSkip.contains(directory));
    }

    @Test
    public void realJunctionTargetSurvivesRecursiveCleanup() throws Exception {
        cleanBaseWithJunction(true);
    }

    @Test
    public void realJunctionTargetSurvivesNonRecursiveCleanup() throws Exception {
        cleanBaseWithJunction(false);
    }

    private void cleanBaseWithJunction(boolean recursive) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            throw new SkipException("Junctions exist only on Windows");
        }
        Path base = temp.create("clean-junction-base");
        Path target = temp.create("clean-junction-target");
        Path targetFile = Files.writeString(target.resolve("outside.txt"), "keep");
        Files.setLastModifiedTime(targetFile, OLD);
        Path baseFile = Files.writeString(base.resolve("inside.txt"), "delete");
        Files.setLastModifiedTime(baseFile, OLD);
        Path link = base.resolve("link");
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .inheritIO().start();
        Assert.assertTrue(mklink.waitFor(30, TimeUnit.SECONDS), "mklink did not finish");
        Assert.assertEquals(mklink.exitValue(), 0, "mklink /J failed");
        try {
            // The junction itself must be old: non-recursive mode inspects only an old folder's tree
            Files.getFileAttributeView(link, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setTimes(OLD, null, null);
            new DeleteFilesFolders().action(base.toString(), "ALL", recursive, 30, "DAY", "MODIFICATION",
                    false, "", false, "", "THROW");
            Assert.assertTrue(Files.exists(targetFile), "a file in the junction target was deleted");
            Assert.assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "the junction was deleted");
            Assert.assertFalse(Files.exists(baseFile), "an old file in the base folder was kept");
            Assert.assertTrue(Files.isDirectory(base));
        } finally {
            // Remove the link only, before the folder clean-up can walk into the target through it
            Files.deleteIfExists(link);
        }
    }
}
