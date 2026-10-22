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
    public void completedListOrdersTiedWrappedSlotsAndExcludesTheActiveSlot() throws Exception {
        Path directory = Files.createTempDirectory("segment-list-order");
        try {
            for (int i = 0; i < 4; i++) {
                Path segment = Files.writeString(directory.resolve(i + ".mov"), i == 3 ? "partial" : "complete");
                Files.setLastModifiedTime(segment, FileTime.fromMillis(1000));
            }
            Files.writeString(directory.resolve("list.txt"), "3.mov\n0.mov\n1.mov\n2.mov\n");
            Files.writeString(directory.resolve("list.txt.tmp"), "1.mov\n2.mov");
            List<Path> expected = List.of(directory.resolve("0.mov"), directory.resolve("1.mov"), directory.resolve("2.mov"));
            Assert.assertEquals(ClipFinalizer.completedRingSegments(directory, 3), expected);
            Path scratch = Files.createDirectory(directory.resolve("scratch's files"));
            for (Path segment : expected) Files.copy(segment, scratch.resolve(segment.getFileName()));
            ClipFinalizer.writeSegmentOrder(scratch, expected);
            Assert.assertEquals(ClipFinalizer.segmentsInOrder(scratch),
                    List.of(scratch.resolve("0.mov"), scratch.resolve("1.mov"), scratch.resolve("2.mov")));
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(directory); }
    }

    @Test
    public void incompleteInvalidAndMissingListEntriesDoNotFallBackToActiveFiles() throws Exception {
        Path directory = Files.createTempDirectory("segment-list-invalid");
        try {
            Files.createFile(directory.resolve("0.mov"));
            Files.createFile(directory.resolve("1.mov"));
            for (String invalid : List.of("0.mov\n1.mov", "0.mov\n2.mov\n", "../0.mov\n", "1.mov\n1.mov\n")) {
                Files.writeString(directory.resolve("list.txt"), invalid);
                Assert.assertTrue(ClipFinalizer.completedRingSegments(directory, 3).isEmpty(), invalid);
            }
            Files.writeString(directory.resolve("list.txt"), "1.mov\n0.mov\n");
            Assert.assertTrue(ClipFinalizer.completedRingSegments(directory, 3).isEmpty(), "out-of-order slots are unsafe");
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(directory); }
    }

    @Test
    public void flatScratchListKeepsRawSpacesAndQuotesAndLegacyFilesStillUseMtime() throws Exception {
        Path directory = Files.createTempDirectory("segment-list-escaping");
        try {
            Path first = Files.createFile(directory.resolve("first's clip.mov"));
            Path second = Files.createFile(directory.resolve("second clip.mov"));
            Files.setLastModifiedTime(first, FileTime.fromMillis(2000));
            Files.setLastModifiedTime(second, FileTime.fromMillis(1000));
            Assert.assertEquals(ClipFinalizer.segmentsInOrder(directory), List.of(second, first));
            ClipFinalizer.writeSegmentOrder(directory, List.of(first, second));
            Assert.assertEquals(ClipFinalizer.segmentsInOrder(directory), List.of(first, second));
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(directory); }
    }

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
