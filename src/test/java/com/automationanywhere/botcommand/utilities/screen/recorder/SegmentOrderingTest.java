package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.testng.Assert;
import org.testng.SkipException;
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
    public void anEmptyOrTornScratchListFallsBackToFileTimeButAGoodListWins() throws Exception {
        Path directory = Files.createTempDirectory("scratch-list-torn");
        try {
            Path older = Files.createFile(directory.resolve("b.mov"));
            Path newer = Files.createFile(directory.resolve("a.mov"));
            Files.setLastModifiedTime(older, FileTime.fromMillis(1000));
            Files.setLastModifiedTime(newer, FileTime.fromMillis(2000));
            // A kill between create and write leaves an empty file; a torn write misses the final newline.
            for (String unusable : List.of("", "a.mov\nb.m")) {
                Files.writeString(directory.resolve("list.txt"), unusable);
                Assert.assertEquals(ClipFinalizer.segmentsInOrder(directory), List.of(older, newer), unusable);
            }
            Files.writeString(directory.resolve("list.txt"), "a.mov\nb.mov\n");
            Assert.assertEquals(ClipFinalizer.segmentsInOrder(directory), List.of(newer, older));
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(directory); }
    }

    @Test
    public void aRingWithoutAListHasNoCompletedSegmentsAndNeverFallsBackToFileTime() throws Exception {
        Path directory = Files.createTempDirectory("ring-without-list");
        try {
            Files.createFile(directory.resolve("0.mov"));
            Files.createFile(directory.resolve("1.mov"));
            Assert.assertTrue(ClipFinalizer.completedRingSegments(directory, 3).isEmpty());
            Files.writeString(directory.resolve("list.txt"), "");
            Assert.assertTrue(ClipFinalizer.completedRingSegments(directory, 3).isEmpty());
            Assert.expectThrows(java.io.IOException.class, () -> ClipFinalizer.completedRingSegments(directory, 0));
            // The ring size is buffer + 1: the largest int must not wrap around into a size that accepts the list.
            Files.writeString(directory.resolve("list.txt"), "0.mov\n1.mov\n");
            Assert.assertEquals(ClipFinalizer.completedRingSegments(directory, 3).size(), 2);
            Assert.assertTrue(ClipFinalizer.completedRingSegments(directory, Integer.MAX_VALUE).isEmpty());
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(directory); }
    }

    @Test
    public void theScratchListIsReplacedInOnePieceNeverRewrittenInPlace() throws Exception {
        Path directory = Files.createTempDirectory("scratch-list-atomic");
        try {
            Path segment = Files.createFile(directory.resolve("0.mov"));
            Path list = directory.resolve("list.txt");
            Files.writeString(list, "old\n");
            // A second name for the old file shows an in-place rewrite: it would read the new text, or a torn one.
            Path reader = directory.resolve("reader");
            try { Files.createLink(reader, list); }
            catch (UnsupportedOperationException | java.io.IOException noLinks) { throw new SkipException("This file system has no hard links"); }
            ClipFinalizer.writeSegmentOrder(directory, List.of(segment));
            Assert.assertEquals(Files.readString(reader), "old\n");
            Assert.assertEquals(Files.readString(list), "0.mov\n");
            Assert.assertFalse(Files.exists(directory.resolve("list.txt.tmp")));
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
