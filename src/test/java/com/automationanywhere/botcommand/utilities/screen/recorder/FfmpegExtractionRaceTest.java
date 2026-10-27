package com.automationanywhere.botcommand.utilities.screen.recorder;

import org.apache.logging.log4j.LogManager;
import org.testng.Assert;
import org.testng.annotations.Test;
import screen.process.RecorderTestSupport;
import support.ChildJvm;
import support.Sha256;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
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
            List<String> classpath = ChildJvm.classPath(FfmpegExtractionRaceTest.class, FfmpegBinary.class, LogManager.class, Assert.class);
            java.net.URL resource = FfmpegBinary.class.getResource("/ffmpeg/ffmpeg.exe");
            if (resource.getProtocol().equals("file")) classpath.add(Path.of(resource.toURI()).getParent().getParent().toString());
            for (int i = 0; i < 4; i++) {
                Process child = new ProcessBuilder(ChildJvm.command(FfmpegExtractionRaceTest.class, classpath, List.of(), root.toString()))
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

    @Test
    public void aWrongCachedHashIsReplacedAndAFixedTemporaryCopyIsReclaimed() throws Exception {
        Path root = Files.createTempDirectory("ffmpeg-stale-copy-");
        try {
            Path cache = Files.createDirectories(root.resolve("ffmpeg").resolve(FfmpegBinary.BUNDLED_HASH_PREFIX));
            Path target = Files.writeString(cache.resolve("ffmpeg.exe"), "wrong binary");
            Path temporary = Files.writeString(cache.resolve("ffmpeg.exe.tmp"), "copy left by a killed process");

            Assert.assertEquals(locateAt(root), target);

            assertHash(target);
            Assert.assertFalse(Files.exists(temporary), "the stale extraction copy must be replaced, not left in the cache");
        } finally { RecorderTestSupport.deleteRecursivelyQuietly(root); }
    }

    @Test
    public void aFailedTemporaryDeleteDoesNotHideTheExtractionFailure() throws Exception {
        Path root = Files.createTempDirectory("ffmpeg-delete-failure-");
        SecurityManager previous = System.getSecurityManager();
        Thread testThread = Thread.currentThread();
        try {
            System.setSecurityManager(new SecurityManager() {
                boolean failed;
                boolean replacing;
                @Override public void checkPermission(java.security.Permission permission) { }
                @Override public void checkRead(String file) {
                    if (Thread.currentThread() == testThread && file.startsWith(root.toString()) && file.endsWith(".tmp") && !replacing) {
                        failed = true;
                        throw new SecurityException("primary extraction failure");
                    }
                }
                @Override public void checkDelete(String file) {
                    if (Thread.currentThread() != testThread || !failed || replacing || !file.endsWith(".tmp")) return;
                    replacing = true;
                    try {
                        // Simulate a delete that fails with IOException, without file-lock semantics specific to Windows.
                        Path temporary = Path.of(file);
                        Files.deleteIfExists(temporary);
                        Files.createDirectory(temporary);
                        Files.writeString(temporary.resolve("held"), "cannot delete a nonempty directory");
                    } catch (java.io.IOException error) { throw new AssertionError(error); }
                }
            });
            java.lang.reflect.InvocationTargetException failure = Assert.expectThrows(
                    java.lang.reflect.InvocationTargetException.class, () -> locateAt(root));
            Assert.assertTrue(failure.getCause() instanceof SecurityException, failure.toString());
            Assert.assertEquals(failure.getCause().getMessage(), "primary extraction failure");
        } finally {
            System.setSecurityManager(previous);
            RecorderTestSupport.deleteRecursivelyQuietly(root);
        }
    }

    private static Path locateAt(Path root) throws Exception {
        Method method = FfmpegBinary.class.getDeclaredMethod("locateAt", Path.class);
        method.setAccessible(true);
        return (Path) method.invoke(null, root);
    }

    private static void assertHash(Path target) throws Exception {
        Assert.assertEquals(Sha256.hex(target), FfmpegBinary.BUNDLED_SHA256);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("ready");
        System.out.flush();
        System.in.read();
        assertHash(locateAt(Path.of(args[0])));
    }
}
