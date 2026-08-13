package pcd.fsstat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSReportListener;
import pcd.fsstat.paradigm.eventloop.EventLoopFSStat;
import pcd.fsstat.paradigm.reactive.ReactiveFSStat;
import pcd.fsstat.paradigm.virtualthreads.VirtualThreadsFSStat;

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

/** Cross-checks correctness and robustness across all FSStat implementations. */
public class FSStatRobustnessCorrectnessTest {

    private static final long MAXIMUM_FILE_SIZE_BYTES = 100;
    private static final int NUMBER_OF_BANDS = 4;

    /** Writes a dummy file with the requested byte size. */
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

    /** Builds a deterministic multi-level test hierarchy. */
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

    /** Runs the virtual-thread scanner and waits for the final report. */
    private FSReport runVirtualThreads(String path, long maximumFileSizeBytes, int numberOfBands) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<FSReport> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        VirtualThreadsFSStat.getFSReport(path, maximumFileSizeBytes, numberOfBands, new FSReportListener() {
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

    /** Runs the event-loop scanner and waits for the final report. */
    private FSReport runEventLoop(String path, long maximumFileSizeBytes, int numberOfBands) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<FSReport> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        EventLoopFSStat.getFSReport(path, maximumFileSizeBytes, numberOfBands, new FSReportListener() {
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

    /** Runs the reactive scanner and returns the final report. */
    private FSReport runReactive(String path, long maximumFileSizeBytes, int numberOfBands) {
        return ReactiveFSStat.getFSReport(path, maximumFileSizeBytes, numberOfBands)
                .blockingLast();
    }

    /** Checks that the virtual-thread scanner reports failure. */
    private void assertFailureVirtualThreads(String path, long maximumFileSizeBytes, int numberOfBands) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        VirtualThreadsFSStat.getFSReport(path, maximumFileSizeBytes, numberOfBands, new FSReportListener() {
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

    /** Checks that the event-loop scanner reports failure. */
    private void assertFailureEventLoop(String path, long maximumFileSizeBytes, int numberOfBands) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        EventLoopFSStat.getFSReport(path, maximumFileSizeBytes, numberOfBands, new FSReportListener() {
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

    /** Checks that the reactive scanner reports failure. */
    private void assertFailureReactive(String path, long maximumFileSizeBytes, int numberOfBands) {
        try {
            ReactiveFSStat.getFSReport(path, maximumFileSizeBytes, numberOfBands).blockingLast();
            fail("Expected error for Reactive scan on path: " + path);
        } catch (Exception e) {
            // Expected path.
        }
    }

    /** Checks total-file and band counts match exactly. */
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

    /** Empty directories produce zero counts in every implementation. */
    @Test
    public void testEmptyDirectoryConsistency(@TempDir Path tempDir) throws Exception {
        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport rxReport = runReactive(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport evReport = runEventLoop(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        assertEquals(0, vtReport.totalFiles());
        for (long count : vtReport.bandsCount()) {
            assertEquals(0, count);
        }
    }

    /** Boundary-sized files land in the expected bands. */
    @Test
    public void testFlatDirectoryConsistency(@TempDir Path tempDir) throws Exception {
        long[] sizes = {0, 10, 25, 50, 75, 100, 101, 250};
        for (int i = 0; i < sizes.length; i++) {
            writeDummyFile(tempDir.resolve("file_" + i + ".dat").toFile(), sizes[i]);
        }

        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport rxReport = runReactive(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport evReport = runEventLoop(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);

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

    /** Nested directories are scanned recursively without losing files. */
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

        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport rxReport = runReactive(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport evReport = runEventLoop(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);

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

    /** A wider hierarchy produces identical reports across implementations. */
    @Test
    public void testWideDeepDirectoryConsistency(@TempDir Path tempDir) throws Exception {
        createWideDeepHierarchy(tempDir);

        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport rxReport = runReactive(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport evReport = runEventLoop(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);

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

    /** Invalid roots are reported as scan failures. */
    @Test
    public void testInvalidDirectoryPath() throws Exception {
        String invalidPath = "/non-existent-dir-fsstat-validation-xyz-" + System.currentTimeMillis();
        assertFailureVirtualThreads(invalidPath, MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        assertFailureReactive(invalidPath, MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        assertFailureEventLoop(invalidPath, MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
    }

    /** Regular files cannot be used as scan roots. */
    @Test
    public void testRegularFilePath(@TempDir Path tempDir) throws Exception {
        File regularFile = tempDir.resolve("regular.txt").toFile();
        writeDummyFile(regularFile, 50);

        assertFailureVirtualThreads(regularFile.getAbsolutePath(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        assertFailureReactive(regularFile.getAbsolutePath(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        assertFailureEventLoop(regularFile.getAbsolutePath(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
    }

    /** Unreadable subdirectories are skipped without crashing. */
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
            FSReport vtReport = runVirtualThreads(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
            FSReport rxReport = runReactive(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
            FSReport evReport = runEventLoop(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);

            assertReportsEqual(vtReport, rxReport);
            assertReportsEqual(vtReport, evReport);

            assertTrue(vtReport.totalFiles() == 1 || vtReport.totalFiles() == 2);
        } finally {
            restrictedFile.setReadable(true);
        }
    }

    /** Circular symlinks do not cause infinite traversal. */
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

        FSReport vtReport = runVirtualThreads(cycleParent.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport rxReport = runReactive(cycleParent.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport evReport = runEventLoop(cycleParent.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        // The cyclic link should not make the same file appear twice.
        assertEquals(1, vtReport.totalFiles());
    }

    /** Large files are classified without integer overflow. */
    @Test
    public void testLargeFileSizes(@TempDir Path tempDir) throws Exception {
        File largeSparseFile = tempDir.resolve("large_sparse.dat").toFile();
        long threeGigabytes = 3_000_000_000L;
        try (RandomAccessFile raf = new RandomAccessFile(largeSparseFile, "rw")) {
            raf.setLength(threeGigabytes);
        }

        // A 3 GB file belongs to the overflow band.
        long oneGigabyte = 1_000_000_000L;
        FSReport vtReport = runVirtualThreads(tempDir.toString(), oneGigabyte, NUMBER_OF_BANDS);
        FSReport rxReport = runReactive(tempDir.toString(), oneGigabyte, NUMBER_OF_BANDS);
        FSReport evReport = runEventLoop(tempDir.toString(), oneGigabyte, NUMBER_OF_BANDS);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        assertEquals(1, vtReport.totalFiles());
        long[] bands = vtReport.bandsCount();
        assertEquals(5, bands.length);
        assertEquals(1, bands[4]);
    }

    /** Concurrent file deletions do not crash running scans. */
    @Test
    public void testConcurrentFileMutation(@TempDir Path tempDir) throws Exception {
        // Create a batch large enough for deletion to race with traversal.
        int totalFiles = 20;
        File[] files = new File[totalFiles];
        for (int i = 0; i < totalFiles; i++) {
            files[i] = tempDir.resolve("mutation_file_" + i + ".dat").toFile();
            writeDummyFile(files[i], 10);
        }

        // Delete half the files while scans are starting.
        Thread deletionThread = new Thread(() -> {
            try {
                Thread.sleep(10);
                for (int i = 0; i < totalFiles; i += 2) {
                    Files.deleteIfExists(files[i].toPath());
                }
            } catch (Exception e) {
                // ignore
            }
        });
        deletionThread.start();

        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport rxReport = runReactive(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport evReport = runEventLoop(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);

        deletionThread.join();

        assertNotNull(vtReport);
        assertNotNull(rxReport);
        assertNotNull(evReport);
    }

    /** Non-regular filesystem entries are skipped. */
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

        FSReport vtReport = runVirtualThreads(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport rxReport = runReactive(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);
        FSReport evReport = runEventLoop(tempDir.toString(), MAXIMUM_FILE_SIZE_BYTES, NUMBER_OF_BANDS);

        assertReportsEqual(vtReport, rxReport);
        assertReportsEqual(vtReport, evReport);

        assertEquals(0, vtReport.totalFiles());
    }
}
