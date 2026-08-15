package pcd.fsstat.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSReportListener;
import pcd.fsstat.common.SizeUnit;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Tests CLI argument parsing, dispatch, and Rx subscription behavior. */
public class FSStatCLITest {

    /** The Rx CLI runner completes with the latest report. */
    @Test
    public void runReactiveScanCompletesWithLatestReport(@TempDir Path tempDir) throws Exception {
        AtomicReference<FSReport> completedReport = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        FSStatCLI.runReactiveScan(tempDir.toString(), 100, 2, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {
                // ignore
            }

            @Override
            public void onCompleted(FSReport report) {
                completedReport.set(report);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                fail(error.getMessage());
            }
        }, latch);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(completedReport.get());
        assertEquals(0, completedReport.get().totalFiles());
    }

    /** Missing optional CLI arguments default to bytes and virtual threads. */
    @Test
    public void parseArgumentsUsesDefaultSizeUnitAndParadigm() {
        var parsed = FSStatCLI.parseArguments(new String[] {".", "10", "5"});

        assertNotNull(parsed);
        assertEquals(".", parsed.directoryPath);
        assertEquals(10.0, parsed.maximumFileSizeInput);
        assertEquals(5, parsed.numberOfBands);
        assertEquals(SizeUnit.BYTES, parsed.sizeUnit);
        assertEquals("vt", parsed.paradigm);
    }

    /** CLI parsing accepts an explicit unit and paradigm. */
    @Test
    public void parseArgumentsAcceptsBinaryAliasesAndParadigm() {
        var parsed = FSStatCLI.parseArguments(new String[] {".", "10", "5", "MiB", "rx"});

        assertNotNull(parsed);
        assertEquals(SizeUnit.MEGABYTES, parsed.sizeUnit);
        assertEquals("rx", parsed.paradigm);
    }

    /** Unit parsing accepts binary aliases. */
    @Test
    public void parseArgumentsAcceptsOtherBinaryAliases() {
        assertEquals(SizeUnit.KILOBYTES, SizeUnit.parse("KiB"));
        assertEquals(SizeUnit.GIGABYTES, SizeUnit.parse("GiB"));
    }

    /** CLI parsing rejects missing mandatory arguments. */
    @Test
    public void parseArgumentsRejectsMissingMandatoryValues() {
        assertNull(FSStatCLI.parseArguments(new String[] {".", "10"}));
    }

    /** CLI dispatch reaches every supported implementation. */
    @Test
    public void dispatchScanRoutesToAllSupportedParadigms(@TempDir Path tempDir) throws Exception {
        assertDispatchCompletes(tempDir, "vt");
        assertDispatchCompletes(tempDir, "loop");
        assertDispatchCompletes(tempDir, "rx");
    }

    /** Runs one CLI dispatch and asserts that it completes successfully. */
    private void assertDispatchCompletes(Path tempDir, String paradigm) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<FSReport> completedReport = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        FSStatCLI.dispatchScan(
            tempDir.toString(),
            100,
            4,
            paradigm,
            new FSReportListener() {
                @Override
                public void onUpdate(FSReport report) {
                    // ignore
                }

                @Override
                public void onCompleted(FSReport report) {
                    completedReport.set(report);
                    latch.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                    error.set(throwable);
                    latch.countDown();
                }
            },
            latch
        );

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNull(error.get());
        assertNotNull(completedReport.get());
        assertEquals(0, completedReport.get().totalFiles());
    }
}
