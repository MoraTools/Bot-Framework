package support;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Builds the class path and java command line that tests use to start a child JVM. */
public final class ChildJvm {
    private ChildJvm() { }

    public static List<String> classPath(Class<?>... types) throws URISyntaxException {
        List<String> paths = new ArrayList<>();
        for (Class<?> type : types)
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return paths;
    }

    public static List<String> command(Class<?> main, Collection<String> classPath, List<String> options, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(java());
        command.addAll(options);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classPath));
        command.add(main.getName());
        java.util.Collections.addAll(command, arguments);
        return command;
    }

    /**
     * Starts {@code java -version} and waits for it to exit. Its PID names no live process: keep the returned
     * {@link Process} referenced so Windows keeps the handle open and cannot reuse that PID.
     */
    public static Process exited() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(java(), "-version").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        process.waitFor();
        return process;
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }
}
