package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * FFmpeg reopens (truncates) the oldest ring slot once per second, 0 to 1 s after it published the list. A
 * snapshot reads the list first and copies afterwards, so the ring can move under it. Each test builds a ring,
 * reads the list like the snapshot does, lets the ring move, and then runs the copy with the list it read earlier.
 * Four slots (buffer of 3 s): 0.mov to 3.mov.
 */
public class SnapshotRaceTest {
    private static final int BUFFER_SECONDS = 3;

    private Path root;
    private Path ring;
    private Path scratch;

    @BeforeMethod
    public void setUp() throws IOException {
        root = Files.createTempDirectory("snapshot-race-");
        ring = Files.createDirectory(root.resolve("ring"));
        scratch = Files.createDirectory(root.resolve("scratch"));
    }

    @AfterMethod
    public void tearDown() {
        RecorderTestSupport.deleteRecursivelyQuietly(root);
    }

    @Test
    public void aSlotThatFfmpegReopensBeforeTheCopyIsDropped() throws Exception {
        List<Path> read = readThreeCompleteSlots();
        // Slot 3 completes: the list gains it and FFmpeg reopens slot 0, which is still the oldest entry.
        write(3, "old-3");
        RecorderTestSupport.publishSegmentList(ring, "0.mov", "1.mov", "2.mov", "3.mov");
        write(0, "new-0");

        copy(read);

        Assert.assertEquals(ClipFinalizer.segmentsInOrder(scratch), List.of(scratch.resolve("1.mov"), scratch.resolve("2.mov")));
        Assert.assertFalse(Files.exists(scratch.resolve("0.mov")), "the reopened slot must not stay in the scratch folder");
        Assert.assertEquals(Files.readString(scratch.resolve("1.mov")), "old-1");
        Assert.assertEquals(Files.readString(scratch.resolve("2.mov")), "old-2");
    }

    @Test
    public void whenNothingUsableIsLeftTheSnapshotFailsAndLeavesNoCopies() throws Exception {
        List<Path> read = readThreeCompleteSlots();
        Files.delete(ring.resolve("list.txt"));

        Assert.expectThrows(IOException.class, () -> copy(read));

        for (String name : List.of("0.mov", "1.mov", "2.mov", "list.txt"))
            Assert.assertFalse(Files.exists(scratch.resolve(name)), name);
    }

    private List<Path> readThreeCompleteSlots() throws IOException {
        for (int slot = 0; slot < 3; slot++) write(slot, "old-" + slot);
        RecorderTestSupport.publishSegmentList(ring, "0.mov", "1.mov", "2.mov");
        List<Path> read = ClipFinalizer.completedRingSegments(ring, BUFFER_SECONDS);
        Assert.assertEquals(read, List.of(ring.resolve("0.mov"), ring.resolve("1.mov"), ring.resolve("2.mov")));
        return read;
    }

    private void write(int slot, String content) throws IOException {
        Files.writeString(ring.resolve(slot + ".mov"), content);
    }

    private void copy(List<Path> read) throws IOException {
        ScreenRecorder.RealScreenRecorder.copyStableSegments(ring, BUFFER_SECONDS, read, scratch);
    }
}
