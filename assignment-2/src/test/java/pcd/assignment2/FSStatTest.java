package pcd.assignment2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportJob;
import pcd.assignment2.common.FSReportListener;
import pcd.assignment2.common.FSUtils;
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
        assertEquals("[0 B - 2,621,439 B]", report.getBandLabel(0));
    }

    @Test
    public void testJobIsCanceledFlag(@TempDir Path tempDir) {
        FSReportJob job = VirtualThreadsFSStat.getFSReport(tempDir.toString(), 1000, 2, new FSReportListener() {
            @Override public void onUpdate(FSReport report) {}
            @Override public void onCompleted(FSReport report) {}
            @Override public void onError(Throwable error) {}
        });
        assertFalse(job.isCanceled());
        job.cancel();
        assertTrue(job.isCanceled());
    }

    @Test
    public void testFormatBandLabelDirectly() {
        String label0 = FSReport.formatBandLabel(0, 100, 2, SizeUnit.BYTES);
        assertEquals("[0 B - 49 B]", label0);

        String labelOverflow = FSReport.formatBandLabel(2, 100, 2, SizeUnit.BYTES);
        assertEquals("> 100 B", labelOverflow);
    }

    @Test
    public void testListFilesSafelyDetectsCycles(@TempDir Path tempDir) throws IOException {
        createDummyFiles(tempDir);
        java.util.Set<String> visited = new java.util.HashSet<>();

        File[] files = FSUtils.listFilesSafely(tempDir.toFile(), visited);
        assertNotNull(files);

        // Second call with same visited set should return null due to cycle check
        File[] cycleFiles = FSUtils.listFilesSafely(tempDir.toFile(), visited);
        assertNull(cycleFiles);
    }

    @Test
    public void testCreateReportSnapshotInFSUtils() {
        java.util.concurrent.atomic.AtomicLong[] atomicBands = new java.util.concurrent.atomic.AtomicLong[] {
            new java.util.concurrent.atomic.AtomicLong(5),
            new java.util.concurrent.atomic.AtomicLong(3)
        };
        java.util.concurrent.atomic.AtomicLong atomicTotal = new java.util.concurrent.atomic.AtomicLong(8);
        FSReport atomicReport = FSUtils.createReportSnapshot("dir", 1000, 1, atomicBands, atomicTotal, System.currentTimeMillis() - 100);

        assertEquals("dir", atomicReport.directory());
        assertEquals(8, atomicReport.totalFiles());
        assertEquals(5, atomicReport.bandsCount()[0]);
        assertEquals(3, atomicReport.bandsCount()[1]);

        java.util.concurrent.atomic.LongAdder[] adderBands = new java.util.concurrent.atomic.LongAdder[] {
            new java.util.concurrent.atomic.LongAdder(),
            new java.util.concurrent.atomic.LongAdder()
        };
        adderBands[0].add(10);
        adderBands[1].add(2);
        java.util.concurrent.atomic.LongAdder adderTotal = new java.util.concurrent.atomic.LongAdder();
        adderTotal.add(12);
        FSReport adderReport = FSUtils.createReportSnapshot("dir2", 500, 1, adderBands, adderTotal, System.currentTimeMillis() - 50);

        assertEquals("dir2", adderReport.directory());
        assertEquals(12, adderReport.totalFiles());
        assertEquals(10, adderReport.bandsCount()[0]);
        assertEquals(2, adderReport.bandsCount()[1]);

        assertEquals("[0 B - 500 B]", FSUtils.formatBandLabel(0, 500, 1, SizeUnit.BYTES));
    }

    @Test
    public void testValidateDirectory(@TempDir Path tempDir) {
        assertNotNull(FSUtils.validateDirectory(tempDir.toString()));
        assertThrows(IllegalArgumentException.class, () -> FSUtils.validateDirectory(null));
        assertThrows(IllegalArgumentException.class, () -> FSUtils.validateDirectory(""));
        assertThrows(IllegalArgumentException.class, () -> FSUtils.validateDirectory(tempDir.resolve("non_existent").toString()));
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
                    finalReport::set,
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
}
