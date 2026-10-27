package support;

import screen.process.RecorderTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The temporary folders of one test class: create them here, then delete them all after the class. */
public final class TempDirectories {
    private final List<Path> created = new CopyOnWriteArrayList<>();

    public Path create(String prefix) throws IOException {
        Path directory = Files.createTempDirectory(prefix);
        created.add(directory);
        return directory;
    }

    public void deleteAll() {
        created.forEach(RecorderTestSupport::deleteRecursivelyQuietly);
        created.clear();
    }
}
