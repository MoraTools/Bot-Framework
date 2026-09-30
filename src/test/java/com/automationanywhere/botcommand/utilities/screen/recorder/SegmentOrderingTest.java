package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.testng.Assert;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

public class SegmentOrderingTest {
    @Test
    public void segmentsUseModificationOrderAndIgnoreOtherFiles() throws Exception {
        Path directory = Files.createTempDirectory("segment-order");
        try {
            Assert.assertTrue(ClipFinalizer.listMovFilesByMtimeAsc(directory).isEmpty());
            Path newest = Files.createFile(directory.resolve("0.mov"));
            Path oldest = Files.createFile(directory.resolve("9.MOV"));
            Path middle = Files.createFile(directory.resolve("1.mov"));
            Files.createFile(directory.resolve("list.txt"));
            Files.setLastModifiedTime(oldest, FileTime.fromMillis(1000));
            Files.setLastModifiedTime(middle, FileTime.fromMillis(2000));
            Files.setLastModifiedTime(newest, FileTime.fromMillis(3000));
            Assert.assertEquals(ClipFinalizer.listMovFilesByMtimeAsc(directory),
                    List.of(oldest, middle, newest));
        } finally {
            RecorderTestSupport.deleteRecursivelyQuietly(directory);
        }
        Assert.assertFalse(Files.exists(directory));
    }
}
