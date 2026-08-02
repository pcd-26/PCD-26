package pcd.assignment2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportListener;
import pcd.assignment2.eventloop.EventLoopFSStat;
import pcd.assignment2.reactive.ReactiveFSStat;
import pcd.assignment2.virtualthreads.VirtualThreadsFSStat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Robustness and correctness test suite for checking consistency, handling edge cases,
 * and error resilience across the VirtualThreads, RxJava, and EventLoop implementations of FSStatLib.
 */
public class FSStatRobustnessCorrectnessTest {

    private static final long MAX_FS = 100;
    private static final int NB = 4;

    /**
     * Helper to write a dummy file of a specific size.
     *
     * @param file The file to write.
     * @param size The size of the file in bytes.
     * @throws IOException If write fails.
     */
    private void writeDummyFile(File file, long size) throws IOException {
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            assertTrue(file.getParentFile().mkdirs());
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            if (size > 0) {
                fos.write(new byte[(int) size]);
            }
        }
    }

    /**
     * Builds a wider and deeper deterministic hierarchy used to compare the
     * three implementations on a non-trivial dataset.
     *
     * @param root The root directory.
     * @throws IOException If hierarchy creation fails.
     */
    private void createWideDeepHierarchy(Path root) throws IOException {
        writeDummyFile(root.resolve("root_10.dat").toFile(), 10);

        Path dirA = root.resolve("dirA");
        Path dirB = root.resolve("dirB");
        assertTrue(dirA.toFile().mkdir());
        assertTrue(dirB.toFile().mkdir());

        writeDummyFile(dirA.resolve("a_0.dat").toFile(), 0);
        writeDummyFile(dirA.resolve("a_25.dat").toFile(), 25);
        writeDummyFile(dirA.resolve("a_101.dat").toFile(), 101);

        writeDummyFile(dirB.resolve("b_50.dat").toFile(), 50);
        writeDummyFile(dirB.resolve("b_75.dat").toFile(), 75);
        writeDummyFile(dirB.resolve("b_250.dat").toFile(), 250);

        Path dirA1 = dirA.resolve("dirA1");
        Path dirA2 = dirA.resolve("dirA2");
        Path dirB1 = dirB.resolve("dirB1");
        Path dirB2 = dirB.resolve("dirB2");
        assertTrue(dirA1.toFile().mkdir());
        assertTrue(dirA2.toFile().mkdir());
        assertTrue(dirB1.toFile().mkdir());
        assertTrue(dirB2.toFile().mkdir());

        writeDummyFile(dirA1.resolve("a1_10.dat").toFile(), 10);
        writeDummyFile(dirA1.resolve("a1_25.dat").toFile(), 25);
        writeDummyFile(dirA1.resolve("a1_50.dat").toFile(), 50);

        writeDummyFile(dirA2.resolve("a2_0.dat").toFile(), 0);
        writeDummyFile(dirA2.resolve("a2_75.dat").toFile(), 75);
        writeDummyFile(dirA2.resolve("a2_101.dat").toFile(), 101);

        writeDummyFile(dirB1.resolve("b1_25.dat").toFile(), 25);
        writeDummyFile(dirB1.resolve("b1_75.dat").toFile(), 75);
        writeDummyFile(dirB1.resolve("b1_150.dat").toFile(), 150);

        writeDummyFile(dirB2.resolve("b2_0.dat").toFile(), 0);
        writeDummyFile(dirB2.resolve("b2_50.dat").toFile(), 50);
        writeDummyFile(dirB2.resolve("b2_101.dat").toFile(), 101);
    }

    /**
     * Runs the Virtual Threads scanner synchronously and returns the report.
     *
     * @param path  The root path to scan.
     * @param maxFS The max file size.
     * @param nb    The number of bands.
     * @return The final FSReport.
     * @throws Exception If scan fails or times out.
     */
    private FSReport runVirtualThreads(String path, long maxFS, int nb) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<FSReport> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        VirtualThreadsFSStat.getFSReport(path, maxFS, nb, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {}

            @Override
            public void onCompleted(FSReport report) {
                result.set(report);
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }
        });
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("Virtual Threads scan timed out for path: " + path);
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
        return result.get();
    }

    /**
     * Runs the Event Loop scanner synchronously and returns the report.
     *
     * @param path  The root path to scan.
     * @param maxFS The max file size.
     * @param nb    The number of bands.
     * @return The final FSReport.
     * @throws Exception If scan fails or times out.
     */
    private FSReport runEventLoop(String path, long maxFS, int nb) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<FSReport> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        EventLoopFSStat.getFSReport(path, maxFS, nb, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {}

            @Override
            public void onCompleted(FSReport report) {
                result.set(report);
                latch.countDown();
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }
        });
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new RuntimeException("Event Loop scan timed out for path: " + path);
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
        return result.get();
    }

    /**
     * Runs the RxJava scanner synchronously and returns the report.
     *
     * @param path  The root path to scan.
     * @param maxFS The max file size.
     * @param nb    The number of bands.
     * @return The final FSReport.
     */
    private FSReport runReactive(String path, long maxFS, int nb) {
        return ReactiveFSStat.getFSReport(path, maxFS, nb)
                .blockingLast();
    }

    /**
     * Asserts that Virtual Threads scanner fails with an exception.
     *
     * @param path  The path.
     * @param maxFS The max file size.
     * @param nb    The number of bands.
     * @throws Exception If thread wait fails.
     */
    private void assertFailureVirtualThreads(String path, long maxFS, int nb) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        VirtualThreadsFSStat.getFSReport(path, maxFS, nb, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {}
            @Override
            public void onCompleted(FSReport report) { latch.countDown(); }
            @Override
            public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNotNull(error.get(), "Expected error for VirtualThreads scan on path: " + path);
    }

    /**
     * Asserts that Event Loop scanner fails with an exception.
     *
     * @param path  The path.
     * @param maxFS The max file size.
     * @param nb    The number of bands.
     * @throws Exception If thread wait fails.
     */
    private void assertFailureEventLoop(String path, long maxFS, int nb) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        EventLoopFSStat.getFSReport(path, maxFS, nb, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {}
            @Override
            public void onCompleted(FSReport report) { latch.countDown(); }
            @Override
            public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNotNull(error.get(), "Expected error for EventLoop scan on path: " + path);
    }

    /**
     * Asserts that Reactive scanner fails with an exception.
     *
     * @param path  The path.
     * @param maxFS The max file size.
     * @param nb    The number of bands.
     */
    private void assertFailureReactive(String path, long maxFS, int nb) {
        try {
            ReactiveFSStat.getFSReport(path, maxFS, nb).blockingLast();
            fail("Expected error for Reactive scan on path: " + path);
        } catch (Exception e) {
            // Expected failure
        }
    }

    /**
     * Asserts that two reports match exactly in counts and bands.
     *
     * @param expected The expected report.
     * @param actual   The actual report.
     */
    private void assertReportsEqual(FSReport expected, FSReport actual) {
        assertNotNull(expected);
        assertNotNull(actual);
        assertEquals(expected.totalFiles(), actual.totalFiles(), "Total files count mismatch");
        long[] expectedBands = expected.bandsCount();
        long[] actualBands = actual.bandsCount();
        assertEquals(expectedBands.length, actualBands.length, "Bands length mismatch");
        for (int i = 0; i < expectedBands.length; i++) {
            assertEquals(expectedBands[i], actualBands[i], "Band " + i + " count mismatch");
        }
    }

    /**
     * Verifies that scanning an empty directory yields consistent results of 0 files.
     *
     * @param tempDir Path injected by Junit.
     * @throws Exception If execution fails.
     */
    @Test
    public void testEmptyDirectoryConsistency(@TempDir Path tempDir) throws Exception {
        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAX_FS, NB);
        FSReport rxReport = runReactive(tempDir.toString(), MAX_FS, NB);
        FSReport evReport = runEventLoop(tempDir.toString(), MAX_FS, NB);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        assertEquals(0, vtReport.totalFiles());
        for (long count : vtReport.bandsCount()) {
            assertEquals(0, count);
        }
    }

    /**
     * Verifies that flat directory scans catalog files correctly based on exact boundary values.
     *
     * @param tempDir Path injected by Junit.
     * @throws Exception If execution fails.
     */
    @Test
    public void testFlatDirectoryConsistency(@TempDir Path tempDir) throws Exception {
        long[] sizes = {0, 10, 25, 50, 75, 100, 101, 250};
        for (int i = 0; i < sizes.length; i++) {
            writeDummyFile(tempDir.resolve("file_" + i + ".dat").toFile(), sizes[i]);
        }

        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAX_FS, NB);
        FSReport rxReport = runReactive(tempDir.toString(), MAX_FS, NB);
        FSReport evReport = runEventLoop(tempDir.toString(), MAX_FS, NB);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        assertEquals(8, vtReport.totalFiles());
        long[] bands = vtReport.bandsCount();
        assertEquals(5, bands.length);
        assertEquals(2, bands[0]);
        assertEquals(1, bands[1]);
        assertEquals(1, bands[2]);
        assertEquals(2, bands[3]);
        assertEquals(2, bands[4]);
    }

    /**
     * Verifies that deeply nested directory scans count all files correctly without dropouts.
     *
     * @param tempDir Path injected by Junit.
     * @throws Exception If execution fails.
     */
    @Test
    public void testNestedDirectoryConsistency(@TempDir Path tempDir) throws Exception {
        writeDummyFile(tempDir.resolve("l1_f1.dat").toFile(), 10);
        Path l2DirA = tempDir.resolve("l2DirA");
        Path l2DirB = tempDir.resolve("l2DirB");
        assertTrue(l2DirA.toFile().mkdir());
        assertTrue(l2DirB.toFile().mkdir());

        writeDummyFile(l2DirA.resolve("l2_f1.dat").toFile(), 30);
        writeDummyFile(l2DirB.resolve("l2_f2.dat").toFile(), 60);
        Path l3Dir = l2DirA.resolve("l3Dir");
        assertTrue(l3Dir.toFile().mkdir());

        writeDummyFile(l3Dir.resolve("l3_f1.dat").toFile(), 90);
        Path l4Dir = l3Dir.resolve("l4Dir");
        assertTrue(l4Dir.toFile().mkdir());

        writeDummyFile(l4Dir.resolve("l4_f1.dat").toFile(), 120);

        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAX_FS, NB);
        FSReport rxReport = runReactive(tempDir.toString(), MAX_FS, NB);
        FSReport evReport = runEventLoop(tempDir.toString(), MAX_FS, NB);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        assertEquals(5, vtReport.totalFiles());
        long[] bands = vtReport.bandsCount();
        assertEquals(5, bands.length);
        assertEquals(1, bands[0]);
        assertEquals(1, bands[1]);
        assertEquals(1, bands[2]);
        assertEquals(1, bands[3]);
        assertEquals(1, bands[4]);
    }

    /**
     * Verifies that a larger and wider hierarchy produces identical results
     * across all implementations.
     *
     * @param tempDir Path injected by JUnit.
     * @throws Exception If execution fails.
     */
    @Test
    public void testWideDeepDirectoryConsistency(@TempDir Path tempDir) throws Exception {
        createWideDeepHierarchy(tempDir);

        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAX_FS, NB);
        FSReport rxReport = runReactive(tempDir.toString(), MAX_FS, NB);
        FSReport evReport = runEventLoop(tempDir.toString(), MAX_FS, NB);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        assertEquals(19, vtReport.totalFiles());
        long[] bands = vtReport.bandsCount();
        assertEquals(5, bands.length);
        assertEquals(5, bands[0]);
        assertEquals(3, bands[1]);
        assertEquals(3, bands[2]);
        assertEquals(3, bands[3]);
        assertEquals(5, bands[4]);
    }

    /**
     * Verifies that invalid paths trigger the error callback without throwing unhandled exceptions.
     *
     * @throws Exception If execution fails.
     */
    @Test
    public void testInvalidDirectoryPath() throws Exception {
        String invalidPath = "/non-existent-dir-fsstat-validation-xyz-" + System.currentTimeMillis();
        assertFailureVirtualThreads(invalidPath, MAX_FS, NB);
        assertFailureReactive(invalidPath, MAX_FS, NB);
        assertFailureEventLoop(invalidPath, MAX_FS, NB);
    }

    /**
     * Verifies that providing a regular file triggers the error callback.
     *
     * @param tempDir Path injected by Junit.
     * @throws Exception If execution fails.
     */
    @Test
    public void testRegularFilePath(@TempDir Path tempDir) throws Exception {
        File regularFile = tempDir.resolve("regular.txt").toFile();
        writeDummyFile(regularFile, 50);

        assertFailureVirtualThreads(regularFile.getAbsolutePath(), MAX_FS, NB);
        assertFailureReactive(regularFile.getAbsolutePath(), MAX_FS, NB);
        assertFailureEventLoop(regularFile.getAbsolutePath(), MAX_FS, NB);
    }

    /**
     * Verifies that unreadable subdirectories do not cause crashes, skipping unreadable areas.
     *
     * @param tempDir Path injected by Junit.
     * @throws Exception If execution fails.
     */
    @Test
    public void testRestrictedPermissions(@TempDir Path tempDir) throws Exception {
        Path allowedDir = tempDir.resolve("allowed");
        Path restrictedDir = tempDir.resolve("restricted");
        assertTrue(allowedDir.toFile().mkdir());
        assertTrue(restrictedDir.toFile().mkdir());

        writeDummyFile(allowedDir.resolve("f1.dat").toFile(), 10);
        writeDummyFile(restrictedDir.resolve("f2.dat").toFile(), 20);

        File restrictedFile = restrictedDir.toFile();
        boolean permissionApplied = restrictedFile.setReadable(false);
        if (!permissionApplied) {
            System.err.println("Warning: setReadable(false) failed or was ignored. Skipping verification of restricted counts, but testing stability.");
        }

        try {
            FSReport vtReport = runVirtualThreads(tempDir.toString(), MAX_FS, NB);
            FSReport rxReport = runReactive(tempDir.toString(), MAX_FS, NB);
            FSReport evReport = runEventLoop(tempDir.toString(), MAX_FS, NB);

            assertReportsEqual(vtReport, rxReport);
            assertReportsEqual(vtReport, evReport);

            assertTrue(vtReport.totalFiles() == 1 || vtReport.totalFiles() == 2);
        } finally {
            restrictedFile.setReadable(true);
        }
    }

    /**
     * Verifies that directories containing circular symlinks (loops) do not trigger infinite loops or hangs.
     *
     * @param tempDir Path injected by Junit.
     * @throws Exception If execution fails.
     */
    @Test
    public void testCircularSymbolicLinks(@TempDir Path tempDir) throws Exception {
        Path cycleParent = tempDir.resolve("cycleParent");
        assertTrue(cycleParent.toFile().mkdir());

        writeDummyFile(cycleParent.resolve("f1.dat").toFile(), 10);

        Path cycleLink = cycleParent.resolve("cycleLink");
        try {
            Files.createSymbolicLink(cycleLink, cycleParent);
        } catch (UnsupportedOperationException | IOException e) {
            System.err.println("Warning: Symbolic links are not supported by the environment. Skipping circular symlink test.");
            return;
        }

        FSReport vtReport = runVirtualThreads(cycleParent.toString(), MAX_FS, NB);
        FSReport rxReport = runReactive(cycleParent.toString(), MAX_FS, NB);
        FSReport evReport = runEventLoop(cycleParent.toString(), MAX_FS, NB);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        // The scanners should only count f1.dat once (since the cyclic link is detected and skipped)
        assertEquals(1, vtReport.totalFiles());
    }

    /**
     * Verifies that large files (> 2 GB) are handled correctly without type overflow.
     *
     * @param tempDir Path injected by Junit.
     * @throws Exception If execution fails.
     */
    @Test
    public void testLargeFileSizes(@TempDir Path tempDir) throws Exception {
        File largeSparseFile = tempDir.resolve("large_sparse.dat").toFile();
        long threeGigabytes = 3_000_000_000L;
        try (RandomAccessFile raf = new RandomAccessFile(largeSparseFile, "rw")) {
            raf.setLength(threeGigabytes);
        }

        // maxFS = 1 GB, 4 bands (each band width = 250 MB).
        // 3 GB file should be categorized in Band 4 (> 1 GB)
        long oneGigabyte = 1_000_000_000L;
        FSReport vtReport = runVirtualThreads(tempDir.toString(), oneGigabyte, NB);
        FSReport rxReport = runReactive(tempDir.toString(), oneGigabyte, NB);
        FSReport evReport = runEventLoop(tempDir.toString(), oneGigabyte, NB);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        assertEquals(1, vtReport.totalFiles());
        long[] bands = vtReport.bandsCount();
        assertEquals(5, bands.length);
        assertEquals(1, bands[4]); // should be in the highest band
    }

    /**
     * Verifies that file modifications and deletions during a running scan do not crash the engines.
     *
     * @param tempDir Path injected by Junit.
     * @throws Exception If execution fails.
     */
    @Test
    public void testConcurrentFileMutation(@TempDir Path tempDir) throws Exception {
        // Create 20 files
        int totalFiles = 20;
        File[] files = new File[totalFiles];
        for (int i = 0; i < totalFiles; i++) {
            files[i] = tempDir.resolve("mutation_file_" + i + ".dat").toFile();
            writeDummyFile(files[i], 10);
        }

        // Spawn a thread to delete files concurrent with the scan
        Thread deletionThread = new Thread(() -> {
            try {
                Thread.sleep(10); // Wait for the scan to start
                for (int i = 0; i < totalFiles; i += 2) {
                    Files.deleteIfExists(files[i].toPath());
                }
            } catch (Exception e) {
                // ignore
            }
        });
        deletionThread.start();

        // Run scans. They should complete successfully without throwing uncaught exceptions.
        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAX_FS, NB);
        FSReport rxReport = runReactive(tempDir.toString(), MAX_FS, NB);
        FSReport evReport = runEventLoop(tempDir.toString(), MAX_FS, NB);

        deletionThread.join();

        // Scans should not crash
        assertNotNull(vtReport);
        assertNotNull(rxReport);
        assertNotNull(evReport);
    }

    /**
     * Verifies that non-regular device files (like a symbolic link to /dev/null) are gracefully skipped.
     *
     * @param tempDir Path injected by Junit.
     * @throws Exception If execution fails.
     */
    @Test
    public void testSpecialFiles(@TempDir Path tempDir) throws Exception {
        File devNull = new File("/dev/null");
        if (!devNull.exists()) {
            System.err.println("Warning: /dev/null does not exist (not a Unix system). Skipping special file test.");
            return;
        }

        Path symlinkToDevNull = tempDir.resolve("null_link");
        try {
            Files.createSymbolicLink(symlinkToDevNull, devNull.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            System.err.println("Warning: Symbolic links not supported. Skipping special file test.");
            return;
        }

        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAX_FS, NB);
        FSReport rxReport = runReactive(tempDir.toString(), MAX_FS, NB);
        FSReport evReport = runEventLoop(tempDir.toString(), MAX_FS, NB);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        // Since /dev/null is a character special device, not a regular file, it should not be cataloged
        assertEquals(0, vtReport.totalFiles());
    }
}
