package pcd.fsstat.cli;

import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSReportListener;
import pcd.fsstat.common.SizeUnit;
import pcd.fsstat.paradigm.eventloop.EventLoopFSStat;
import pcd.fsstat.paradigm.reactive.ReactiveFSStat;
import pcd.fsstat.paradigm.virtualthreads.VirtualThreadsFSStat;

import io.reactivex.rxjava3.schedulers.Schedulers;

import java.io.File;
import java.util.concurrent.CountDownLatch;

/** Command-line runner for FSStat scans. */
public class FSStatCLI {
    private static final String PARADIGM_VT = "vt";
    private static final String PARADIGM_RX = "rx";
    private static final String PARADIGM_LOOP = "loop";

    /** Stores validated command-line inputs in normalized form. */
    static final class ParsedArguments {
        final String directoryPath;
        final double maximumFileSizeInput;
        final int numberOfBands;
        final SizeUnit sizeUnit;
        final String paradigm;

        /** Creates a parsed argument bundle for a CLI scan. */
        ParsedArguments(String directoryPath, double maximumFileSizeInput, int numberOfBands, SizeUnit sizeUnit, String paradigm) {
            this.directoryPath = directoryPath;
            this.maximumFileSizeInput = maximumFileSizeInput;
            this.numberOfBands = numberOfBands;
            this.sizeUnit = sizeUnit;
            this.paradigm = paradigm;
        }
    }

    /** Runs a scan from command-line arguments. */
    public static void main(String[] args) {
        // Parse and validate the CLI contract before touching the filesystem.
        ParsedArguments parsed = parseArguments(args);
        if (parsed == null) {
            System.exit(1);
            return;
        }

        // Convert the threshold once so all implementations receive bytes.
        final SizeUnit outputUnit = parsed.sizeUnit;
        long maximumFileSizeBytes = parsed.sizeUnit.toBytes(parsed.maximumFileSizeInput);

        // Fail early for invalid roots instead of starting an asynchronous scan.
        File targetDirectory = new File(parsed.directoryPath);
        if (!targetDirectory.exists() || !targetDirectory.isDirectory()) {
            System.err.println("Error: Target path is not a valid directory: " + parsed.directoryPath);
            System.exit(1);
        }

        // Print the resolved configuration before progress starts rewriting the line.
        System.out.println("Starting CLI scan using paradigm: " + parsed.paradigm.toUpperCase());
        System.out.println("Directory: " + targetDirectory.getAbsolutePath());
        System.out.println("Max Size Threshold: " + outputUnit.format(maximumFileSizeBytes) + " (" + maximumFileSizeBytes + " bytes)");
        System.out.println("Number of bands: " + parsed.numberOfBands);
        System.out.println("----------------------------------------------");

        // Use a latch so the CLI process waits for asynchronous completion.
        CountDownLatch completionSignal = new CountDownLatch(1);

        // Bridge the asynchronous scanner callbacks to console output.
        FSReportListener reportListener = new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {
                // Rewrite the same terminal line during progress updates.
                System.out.print(String.format("\rProgress: %d files scanned...", report.totalFiles()));
                System.out.flush();
            }

            @Override
            public void onCompleted(FSReport report) {
                // Print the final table and release the main thread.
                System.out.print(String.format("\rProgress: %d files scanned... Done!%n", report.totalFiles()));
                printFinalReport(report, outputUnit);
                completionSignal.countDown();
            }

            @Override
            public void onError(Throwable error) {
                // Errors are terminal for the CLI run.
                System.err.println("\nScan failed with error: " + error.getMessage());
                completionSignal.countDown();
            }
        };

        // Start the selected asynchronous implementation.
        dispatchScan(parsed.directoryPath, maximumFileSizeBytes, parsed.numberOfBands, parsed.paradigm, reportListener, completionSignal);

        try {
            // Keep the process alive until a terminal callback arrives.
            completionSignal.await();
        } catch (InterruptedException e) {
            System.err.println("Execution interrupted.");
        }

        // Stop RxJava worker threads when the reactive backend was used.
        Schedulers.shutdown();
        System.exit(0);
    }

    /** Parses directory, size, optional unit, and optional paradigm from CLI arguments. */
    static ParsedArguments parseArguments(String[] args) {
        // The directory, max size, and band count are mandatory.
        if (args == null || args.length < 3) {
            System.err.println("Usage: java -cp ... pcd.fsstat.cli.FSStatCLI <directory> <maxFS> <nb> [sizeUnit: B|KiB|MiB|GiB] [paradigm: vt|rx|loop]");
            System.err.println("Example: java -cp ... pcd.fsstat.cli.FSStatCLI . 10 5 MB vt");
            return null;
        }

        // Positional arguments keep the minimal invocation compact.
        String directoryPath = args[0];
        double maximumFileSizeInput = Double.parseDouble(args[1]);
        int numberOfBands = Integer.parseInt(args[2]);

        // Bytes and virtual threads are the default mode.
        SizeUnit sizeUnit = SizeUnit.BYTES;
        String paradigm = "vt";

        // Remaining arguments may appear as unit and/or paradigm.
        for (int i = 3; i < args.length; i++) {
            String value = args[i].toLowerCase();
            if ("vt".equals(value) || "rx".equals(value) || "loop".equals(value)) {
                paradigm = value;
            } else {
                sizeUnit = SizeUnit.parse(value);
            }
        }

        return new ParsedArguments(directoryPath, maximumFileSizeInput, numberOfBands, sizeUnit, paradigm);
    }

    /** Dispatches the scan request to the selected implementation. */
    static void dispatchScan(
        String directoryPath,
        long maximumFileSizeBytes,
        int numberOfBands,
        String paradigm,
        FSReportListener reportListener,
        CountDownLatch completionSignal
    ) {
        // Imperative implementations report completion through the listener.
        if (PARADIGM_VT.equals(paradigm)) {
            runVirtualThreadsScan(directoryPath, maximumFileSizeBytes, numberOfBands, reportListener);
            return;
        }
        if (PARADIGM_LOOP.equals(paradigm)) {
            runEventLoopScan(directoryPath, maximumFileSizeBytes, numberOfBands, reportListener);
            return;
        }

        // RxJava needs the latch so subscription completion can release the CLI.
        if (PARADIGM_RX.equals(paradigm)) {
            runReactiveScan(directoryPath, maximumFileSizeBytes, numberOfBands, reportListener, completionSignal);
            return;
        }

        System.err.println("Unknown paradigm: " + paradigm + ". Use: vt, loop, or rx.");
        System.exit(1);
    }

    /** Launches the virtual-thread implementation from the CLI. */
    static void runVirtualThreadsScan(String directoryPath, long maximumFileSizeBytes, int numberOfBands, FSReportListener reportListener) {
        VirtualThreadsFSStat.getFSReport(directoryPath, maximumFileSizeBytes, numberOfBands, reportListener);
    }

    /** Launches the Vert.x event-loop implementation from the CLI. */
    static void runEventLoopScan(String directoryPath, long maximumFileSizeBytes, int numberOfBands, FSReportListener reportListener) {
        EventLoopFSStat.getFSReport(directoryPath, maximumFileSizeBytes, numberOfBands, reportListener);
    }

    /** Launches the RxJava implementation from the CLI. */
    static void runReactiveScan(
        String directoryPath,
        long maximumFileSizeBytes,
        int numberOfBands,
        FSReportListener reportListener,
        CountDownLatch completionSignal
    ) {
        // Rx completion carries no value, so keep the latest emitted report.
        final FSReport[] latestReport = new FSReport[1];
        ReactiveFSStat.getFSReport(directoryPath, maximumFileSizeBytes, numberOfBands).subscribe(
            report -> {
                latestReport[0] = report;
                reportListener.onUpdate(report);
            },
            error -> {
                reportListener.onError(error);
                completionSignal.countDown();
            },
            () -> {
                // Empty streams still need to release the waiting CLI thread.
                if (latestReport[0] != null) {
                    reportListener.onCompleted(latestReport[0]);
                } else {
                    completionSignal.countDown();
                }
            }
        );
    }

    /** Prints the final report in a compact tabular console format. */
    private static void printFinalReport(FSReport report, SizeUnit outputUnit) {
        // Header and summary values identify the completed scan.
        System.out.println("\n==============================================");
        System.out.println("FINAL FILE SIZE DISTRIBUTION REPORT");
        System.out.println("==============================================");
        System.out.println("Directory Scanned: " + report.directory());
        System.out.println("Total Files Scanned: " + report.totalFiles());
        System.out.println("Duration: " + report.formatDuration());
        System.out.println("----------------------------------------------");
        System.out.printf("%-30s | %-10s%n", "Size Range Band", "File Count");
        System.out.println("----------------------------------------------");

        // Print one row for each normal band and the overflow band.
        long[] bandCounts = report.bandsCount();
        for (int bandIndex = 0; bandIndex < bandCounts.length; bandIndex++) {
            System.out.printf("%-30s | %-10d%n", report.getBandLabel(bandIndex, outputUnit), bandCounts[bandIndex]);
        }
        System.out.println("==============================================");
    }
}
