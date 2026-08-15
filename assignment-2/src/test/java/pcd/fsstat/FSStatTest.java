package pcd.fsstat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSReportJob;
import pcd.fsstat.common.FSReportListener;
import pcd.fsstat.common.SizeUnit;
import pcd.fsstat.paradigm.eventloop.EventLoopFSStat;
import pcd.fsstat.paradigm.reactive.ReactiveFSStat;
import pcd.fsstat.paradigm.virtualthreads.VirtualThreadsFSStat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Basic unit and integration tests for FSStat shared utilities and implementations. */
public class FSStatTest {

    /** File sizes map to the expected bands. */
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

    /** Durations are formatted for display. */
    @Test
    public void testDurationFormatting() {
        assertEquals("1.234 s (1234 ms)", FSReport.formatDuration(1234));
        assertEquals("0.000 s (0 ms)", FSReport.formatDuration(0));
    }

    /** Size units format values and parse aliases. */
    @Test
    public void testSizeUnitFormattingAndParsing() {
        assertEquals(SizeUnit.MEGABYTES, SizeUnit.parse("mb"));
        assertEquals(SizeUnit.BYTES, SizeUnit.parse("bytes"));
        assertEquals("10.0 MiB", SizeUnit.MEGABYTES.format(10 * 1024 * 1024L));
        assertEquals("1,024 B", SizeUnit.BYTES.format(1024));
    }

    /** Band labels use the selected display unit. */
    @Test
    public void testBandLabelsCanUseSelectedUnit() {
        FSReport report = new FSReport("root", 10 * 1024 * 1024L, 4, new long[] {0, 0, 0, 0, 0}, 0, 0);
        assertTrue(report.getBandLabel(0, SizeUnit.MEGABYTES).contains("MiB"));
        assertTrue(report.getBandLabel(4, SizeUnit.MEGABYTES).startsWith("> "));
        assertEquals(
            report.getBandLabel(0, SizeUnit.MEGABYTES),
            FSReport.formatBandLabel(report.maximumFileSizeBytes(), report.numberOfBands(), 0, SizeUnit.MEGABYTES)
        );
        assertEquals(
            report.getBandLabel(4, SizeUnit.MEGABYTES),
            FSReport.formatBandLabel(report.maximumFileSizeBytes(), report.numberOfBands(), 4, SizeUnit.MEGABYTES)
        );
    }

    /** Creates a deterministic fixture with nested files of known sizes. */
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

    /** Writes a file containing a fixed number of bytes. */
    private void writeDummyContent(File file, int size) throws IOException {
        try (FileWriter fw = new FileWriter(file)) {
            for (int i = 0; i < size; i++) {
                fw.write('A');
            }
        }
    }

    /** Creates a large directory tree used by cancellation tests. */
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

    /** Virtual threads scan the deterministic fixture correctly. */
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

    /** RxJava scans the deterministic fixture correctly. */
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

    /** RxJava emits a zero-file report for an empty root. */
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

    /** Vert.x scans the deterministic fixture correctly. */
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

    /** Cancelling an event-loop scan before it starts suppresses terminal callbacks. */
    @Test
    public void testEventLoopCancellationStopsRunningScan(@TempDir Path tempDir) throws Exception {
        createLargeDirectoryTree(tempDir, 20, 20);

        CountDownLatch terminalLatch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean errored = new AtomicBoolean(false);

        FSReportJob job = EventLoopFSStat.getFSReport(tempDir.toString(), 100, 4, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {
                // ignore
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

        job.cancel();

        assertTrue(job.isCancelled());
        assertFalse(terminalLatch.await(500, TimeUnit.MILLISECONDS), "A cancelled scan should not emit terminal callbacks.");
        assertFalse(completed.get());
        assertFalse(errored.get());
    }
}
