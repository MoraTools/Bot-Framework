package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.apache.logging.log4j.LogManager;
import org.testng.Assert;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class FfmpegExtractionRaceTest {
    @Test
    public void threadsShareAColdCacheAndLeaveNoPrivateTemporaryFiles() throws Exception {
        Path root = Files.createTempDirectory("ffmpeg-thread-race-");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Path>> jobs = new ArrayList<>();
            for (int i = 0; i < 12; i++) jobs.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return locateAt(root);
            }));
            Assert.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Path target = null;
            for (Future<Path> job : jobs) {
                Path extracted = job.get(20, TimeUnit.SECONDS);
                if (target != null) Assert.assertEquals(extracted, target);
                target = extracted;
                assertHash(extracted);
            }
            try (var paths = Files.list(target.getParent())) {
                Assert.assertFalse(paths.anyMatch(path -> path.toString().endsWith(".tmp")));
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            RecorderTestSupport.deleteRecursivelyQuietly(root);
        }
    }

    @Test
    public void independentJvmsShareAColdCacheAndKeepAnOpenVerifiedWinner() throws Exception {
        Path root = Files.createTempDirectory("ffmpeg-process-race-");
        List<Process> children = new ArrayList<>();
        try {
            List<String> classpath = new ArrayList<>();
            for (Class<?> type : new Class<?>[] { FfmpegExtractionRaceTest.class, FfmpegBinary.class, LogManager.class, Assert.class })
                classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            java.net.URL resource = FfmpegBinary.class.getResource("/ffmpeg/ffmpeg.exe");
            if (resource.getProtocol().equals("file")) classpath.add(Path.of(resource.toURI()).getParent().getParent().toString());
            Path executable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            for (int i = 0; i < 4; i++) {
                Process child = new ProcessBuilder(executable.toString(), "-cp", String.join(java.io.File.pathSeparator, classpath),
                        FfmpegExtractionRaceTest.class.getName(), root.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                children.add(child);
                Assert.assertEquals(new BufferedReader(new InputStreamReader(child.getInputStream())).readLine(), "ready");
            }
            for (Process child : children) { child.getOutputStream().write(1); child.getOutputStream().flush(); }
            for (Process child : children) {
                Assert.assertTrue(child.waitFor(20, TimeUnit.SECONDS));
                Assert.assertEquals(child.exitValue(), 0);
            }
            Path target = locateAt(root);
            try (var open = Files.newInputStream(target)) {
                Assert.assertEquals(locateAt(root), target);
                Assert.assertEquals(open.read(), 'M');
            }
            assertHash(target);
            try (var paths = Files.list(target.getParent())) {
                Assert.assertFalse(paths.anyMatch(path -> path.toString().endsWith(".tmp")));
            }
        } finally {
            for (Process child : children) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            RecorderTestSupport.deleteRecursivelyQuietly(root);
        }
    }

    private static Path locateAt(Path root) throws Exception {
        Method method = FfmpegBinary.class.getDeclaredMethod("locateAt", Path.class);
        method.setAccessible(true);
        return (Path) method.invoke(null, root);
    }

    private static void assertHash(Path target) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target));
        StringBuilder hash = new StringBuilder();
        for (byte value : bytes) hash.append(String.format(java.util.Locale.ROOT, "%02x", value));
        Assert.assertEquals(hash.toString(), FfmpegBinary.BUNDLED_SHA256);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("ready");
        System.out.flush();
        System.in.read();
        assertHash(locateAt(Path.of(args[0])));
    }
}
