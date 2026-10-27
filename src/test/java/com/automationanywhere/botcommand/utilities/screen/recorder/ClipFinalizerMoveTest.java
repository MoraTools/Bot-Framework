package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.testng.Assert;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ClipFinalizerMoveTest {
    @Test
    public void aClipThatCannotBeMovedAtomicallyIsPublishedFromAPartFileInTheTargetFolder() throws Exception {
        Path root = Files.createTempDirectory("clip-move-other-volume");
        try {
            Path source = Files.writeString(root.resolve("out.mp4.tmp"), "finished clip");
            // A zip file system stands in for a second volume: an atomic move into it is refused, as it is across drives.
            try (FileSystem other = FileSystems.newFileSystem(URI.create("jar:" + root.resolve("other.zip").toUri()), Map.of("create", "true"))) {
                Path clips = Files.createDirectories(other.getPath("/clips"));
                Path target = clips.resolve("error.mp4");
                Path part = clips.resolve("error.mp4.part");
                Files.writeString(part, "half of a clip from a killed attempt");

                ClipFinalizer.moveIntoPlace(source, target);

                Assert.assertEquals(Files.readString(target), "finished clip");
                Assert.assertFalse(Files.exists(part), "the copy grows under the .part name and is renamed when whole");
                Assert.assertFalse(Files.exists(source), "the finished file is moved, not copied");
            }
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(root); }
    }

    @Test
    public void aFailedCrossVolumeStagingCopyCannotReplaceThePublishedClip() throws Exception {
        Path root = Files.createTempDirectory("clip-move-staging-failure");
        try {
            Path source = Files.writeString(root.resolve("out.mp4.tmp"), "new clip");
            try (FileSystem other = FileSystems.newFileSystem(URI.create("jar:" + root.resolve("other.zip").toUri()), Map.of("create", "true"))) {
                Path target = Files.writeString(other.getPath("/error.mp4"), "old complete clip");
                Path part = Files.createDirectory(other.getPath("/error.mp4.part"));
                Files.writeString(part.resolve("held"), "staging copy cannot replace this directory");

                Assert.expectThrows(java.io.IOException.class, () -> ClipFinalizer.moveIntoPlace(source, target));

                Assert.assertEquals(Files.readString(target), "old complete clip");
                Assert.assertEquals(Files.readString(source), "new clip", "a failed staging copy keeps the source");
            }
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(root); }
    }

}
