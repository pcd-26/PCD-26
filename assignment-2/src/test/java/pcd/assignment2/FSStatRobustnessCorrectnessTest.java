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
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class FSStatRobustnessCorrectnessTest {

    private static final long MAX_FS = 100;
    private static final int NB = 4;

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

    private FSReport runReactive(String path, long maxFS, int nb) {
        return ReactiveFSStat.getFSReport(path, maxFS, nb)
                .blockingLast();
    }

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

    private void assertFailureReactive(String path, long maxFS, int nb) {
        try {
            ReactiveFSStat.getFSReport(path, maxFS, nb).blockingLast();
            fail("Expected error for Reactive scan on path: " + path);
        } catch (Exception e) {
            // Expected failure
        }
    }

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

    @Test
    public void testInvalidDirectoryPath() throws Exception {
        String invalidPath = "/non-existent-dir-fsstat-validation-xyz-" + System.currentTimeMillis();
        assertFailureVirtualThreads(invalidPath, MAX_FS, NB);
        assertFailureReactive(invalidPath, MAX_FS, NB);
        assertFailureEventLoop(invalidPath, MAX_FS, NB);
    }

    @Test
    public void testRegularFilePath(@TempDir Path tempDir) throws Exception {
        File regularFile = tempDir.resolve("regular.txt").toFile();
        writeDummyFile(regularFile, 50);

        assertFailureVirtualThreads(regularFile.getAbsolutePath(), MAX_FS, NB);
        assertFailureReactive(regularFile.getAbsolutePath(), MAX_FS, NB);
        assertFailureEventLoop(regularFile.getAbsolutePath(), MAX_FS, NB);
    }

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
            System.err.println("Warning: setReadable(false) failed or was ignored (e.g. running as root). Skipping verification of restricted counts, but testing stability.");
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
}
