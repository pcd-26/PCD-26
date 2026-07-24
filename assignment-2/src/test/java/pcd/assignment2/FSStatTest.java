package pcd.assignment2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportJob;
import pcd.assignment2.common.FSReportListener;
import pcd.assignment2.common.SizeUnit;
import pcd.assignment2.eventloop.EventLoopFSStat;
import pcd.assignment2.reactive.ReactiveFSStat;
import pcd.assignment2.virtualthreads.VirtualThreadsFSStat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class FSStatTest {

    @Test
    public void testBandIndex() {
        assertEquals(0, FSReport.getBandIndex(0, 100, 4));
        assertEquals(0, FSReport.getBandIndex(24, 100, 4));
        assertEquals(1, FSReport.getBandIndex(25, 100, 4));
        assertEquals(1, FSReport.getBandIndex(49, 100, 4));
        assertEquals(2, FSReport.getBandIndex(50, 100, 4));
        assertEquals(2, FSReport.getBandIndex(74, 100, 4));
        assertEquals(3, FSReport.getBandIndex(75, 100, 4));
        assertEquals(3, FSReport.getBandIndex(100, 100, 4));
        assertEquals(4, FSReport.getBandIndex(101, 100, 4));
    }

    @Test
    public void testDurationFormatting() {
        assertEquals("1.234 s (1234 ms)", FSReport.formatDuration(1234));
        assertEquals("0.000 s (0 ms)", FSReport.formatDuration(0));
    }

    @Test
    public void testSizeUnitFormattingAndParsing() {
        assertEquals(SizeUnit.MEGABYTES, SizeUnit.parse("mb"));
        assertEquals(SizeUnit.BYTES, SizeUnit.parse("bytes"));
        assertEquals("10.0 MiB", SizeUnit.MEGABYTES.format(10 * 1024 * 1024L));
        assertEquals("1,024 B", SizeUnit.BYTES.format(1024));
    }

    @Test
    public void testBandLabelsCanUseSelectedUnit() {
        FSReport report = new FSReport("root", 10 * 1024 * 1024L, 4, new long[] {0, 0, 0, 0, 0}, 0, 0);
        assertTrue(report.getBandLabel(0, SizeUnit.MEGABYTES).contains("MiB"));
        assertTrue(report.getBandLabel(4, SizeUnit.MEGABYTES).startsWith("> "));
    }

    private void createDummyFiles(Path tempDir) throws IOException {
        File file1 = tempDir.resolve("file1.txt").toFile();
        writeDummyContent(file1, 10);

        File file2 = tempDir.resolve("file2.txt").toFile();
        writeDummyContent(file2, 40);

        Path sub = tempDir.resolve("subdir");
        assertTrue(sub.toFile().mkdir());

        File file3 = sub.resolve("file3.txt").toFile();
        writeDummyContent(file3, 80);

        File file4 = sub.resolve("file4.txt").toFile();
        writeDummyContent(file4, 150);
    }

    private void writeDummyContent(File file, int size) throws IOException {
        try (FileWriter fw = new FileWriter(file)) {
            for (int i = 0; i < size; i++) {
                fw.write('A');
            }
        }
    }

    private void createLargeDirectoryTree(Path root, int subdirectories, int filesPerDirectory) throws IOException {
        for (int dirIndex = 0; dirIndex < subdirectories; dirIndex++) {
            Path subdir = root.resolve("subdir_" + dirIndex);
            assertTrue(subdir.toFile().mkdir());
            for (int fileIndex = 0; fileIndex < filesPerDirectory; fileIndex++) {
                File file = subdir.resolve("file_" + fileIndex + ".txt").toFile();
                writeDummyContent(file, 16);
            }
        }
    }

    @Test
    public void testVirtualThreadsFSStat(@TempDir Path tempDir) throws Exception {
        createDummyFiles(tempDir);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<FSReport> finalReport = new AtomicReference<>();
        AtomicReference<Throwable> finalError = new AtomicReference<>();

        VirtualThreadsFSStat.getFSReport(tempDir.toString(), 100, 4, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {
                // ignore
            }

            @Override
            public void onCompleted(FSReport report) {
                finalReport.set(report);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                finalError.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNull(finalError.get());
        assertNotNull(finalReport.get());

        FSReport report = finalReport.get();
        assertEquals(4, report.totalFiles());
        long[] bands = report.bandsCount();
        assertEquals(5, bands.length);
        assertEquals(1, bands[0]);
        assertEquals(1, bands[1]);
        assertEquals(0, bands[2]);
        assertEquals(1, bands[3]);
        assertEquals(1, bands[4]);
    }

    @Test
    public void testReactiveFSStat(@TempDir Path tempDir) throws Exception {
        createDummyFiles(tempDir);
        AtomicReference<FSReport> finalReport = new AtomicReference<>();

        ReactiveFSStat.getFSReport(tempDir.toString(), 100, 4)
            .blockingSubscribe(
                report -> finalReport.set(report),
                error -> fail(error.getMessage())
            );

        assertNotNull(finalReport.get());
        FSReport report = finalReport.get();
        assertEquals(4, report.totalFiles());
        long[] bands = report.bandsCount();
        assertEquals(5, bands.length);
        assertEquals(1, bands[0]);
        assertEquals(1, bands[1]);
        assertEquals(0, bands[2]);
        assertEquals(1, bands[3]);
        assertEquals(1, bands[4]);
    }

    @Test
    public void testReactiveFSStatEmptyDirectory(@TempDir Path tempDir) {
        AtomicReference<FSReport> finalReport = new AtomicReference<>();

        ReactiveFSStat.getFSReport(tempDir.toString(), 100, 4)
            .blockingSubscribe(finalReport::set, error -> fail(error.getMessage()));

        assertNotNull(finalReport.get());
        FSReport report = finalReport.get();
        assertEquals(0, report.totalFiles());
        long[] bands = report.bandsCount();
        assertEquals(5, bands.length);
        for (long band : bands) {
            assertEquals(0, band);
        }
    }

    @Test
    public void testEventLoopFSStat(@TempDir Path tempDir) throws Exception {
        createDummyFiles(tempDir);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<FSReport> finalReport = new AtomicReference<>();
        AtomicReference<Throwable> finalError = new AtomicReference<>();

        EventLoopFSStat.getFSReport(tempDir.toString(), 100, 4, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {
                // ignore
            }

            @Override
            public void onCompleted(FSReport report) {
                finalReport.set(report);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                finalError.set(error);
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNull(finalError.get());
        assertNotNull(finalReport.get());

        FSReport report = finalReport.get();
        assertEquals(4, report.totalFiles());
        long[] bands = report.bandsCount();
        assertEquals(5, bands.length);
        assertEquals(1, bands[0]);
        assertEquals(1, bands[1]);
        assertEquals(0, bands[2]);
        assertEquals(1, bands[3]);
        assertEquals(1, bands[4]);
    }

    @Test
    public void testEventLoopCancellationStopsRunningScan(@TempDir Path tempDir) throws Exception {
        createLargeDirectoryTree(tempDir, 40, 50);

        CountDownLatch firstUpdateLatch = new CountDownLatch(1);
        CountDownLatch terminalLatch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean errored = new AtomicBoolean(false);

        FSReportJob job = EventLoopFSStat.getFSReport(tempDir.toString(), 100, 4, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {
                firstUpdateLatch.countDown();
            }

            @Override
            public void onCompleted(FSReport report) {
                completed.set(true);
                terminalLatch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                errored.set(true);
                terminalLatch.countDown();
            }
        });

        assertTrue(firstUpdateLatch.await(5, TimeUnit.SECONDS), "The scan should still be running when the first update arrives.");
        job.cancel();

        assertTrue(job.isCancelled());
        assertFalse(terminalLatch.await(300, TimeUnit.MILLISECONDS), "The scan should not complete right after cancellation.");
        assertFalse(completed.get());
        assertFalse(errored.get());
    }
}
