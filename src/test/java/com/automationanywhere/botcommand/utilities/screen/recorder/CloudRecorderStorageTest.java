package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.testng.Assert;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CloudRecorderStorageTest {
    @Test public void allCloudRecordersShareOneByteAdmissionAndScratchReservesBeforeCopy() throws Exception {
        Path root = Files.createTempDirectory("cloud-recorder-budget");
        List<RecorderStorage> recorders = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) recorders.add(new RecorderStorage(root, "session" + i));
            Assert.assertFalse(recorders.get(0).reserve("capture", 1));
            recorders.remove(3).close();
            Assert.assertTrue(recorders.get(0).reserve("capture", RecorderStorage.RING_BYTES));
            Assert.assertFalse(recorders.get(1).reserve("capture", 1));
            recorders.get(0).release("capture");
            Assert.assertTrue(recorders.get(1).reserve("capture", 1));
        } finally { for (RecorderStorage recorder : recorders) recorder.close(); }
    }

    @Test public void cloudScratchCopiesOnlyAdmittedBytesAndRejectsAShrinkingSegment() throws Exception {
        Path root = Files.createTempDirectory("cloud-admitted-copy");
        Path source = root.resolve("source.mov");
        Path target = root.resolve("target.mov");
        Files.write(source, new byte[100]);
        java.lang.reflect.Method copy = ScreenRecorder.RealScreenRecorder.class.getDeclaredMethod(
                "copyAdmittedSegment", Path.class, Path.class, long.class);
        copy.setAccessible(true);
        copy.invoke(null, source, target, 40L);
        Assert.assertEquals(Files.size(target), 40L);
        Files.write(source, new byte[30]);
        try {
            copy.invoke(null, source, target, 40L);
            Assert.fail("a shrinking segment is incomplete");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            Assert.assertTrue(expected.getCause() instanceof java.io.IOException);
        }
    }

    @Test public void disabledRecorderRegistersEachCallExactlyOnceAndDoesNotInventCompletion() {
        java.util.concurrent.atomic.AtomicInteger registrations = new java.util.concurrent.atomic.AtomicInteger();
        CaptureResult first = ScreenRecorder.DISABLED.snapshotForError("first", false, capture -> registrations.incrementAndGet());
        CaptureResult second = ScreenRecorder.DISABLED.snapshotForError("second", false, capture -> registrations.incrementAndGet());
        Assert.assertEquals(registrations.get(), 2);
        Assert.assertNull(first.video);
        Assert.assertNull(second.completion.join());
        Assert.assertEquals(first.unavailableReason, "videoUnavailable");
        CaptureResult pending = new CaptureResult("capture", Path.of("clip.mp4"), Path.of("scratch"), null);
        Assert.assertFalse(pending.completion.isDone(), "a future path is never a completed clip");
    }
}
