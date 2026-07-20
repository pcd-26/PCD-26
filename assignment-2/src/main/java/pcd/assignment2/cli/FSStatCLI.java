package pcd.assignment2.cli;

import io.reactivex.rxjava3.core.Observable;
import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportListener;
import pcd.assignment2.common.FSUtils;
import pcd.assignment2.common.SizeUnit;
import pcd.assignment2.eventloop.EventLoopFSStat;
import pcd.assignment2.reactive.ReactiveFSStat;
import pcd.assignment2.virtualthreads.VirtualThreadsFSStat;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Command-line interface runner for the FSStat library.
 * Allows running directory scans and outputting results to the console without a GUI.
 */
public class FSStatCLI {

    /**
     * Record holding parsed command-line parameters.
     *
     * @param directory   The directory path to scan.
     * @param maxFSInput  The user-specified maximum file size threshold.
     * @param nb          The number of size distribution bands.
     * @param sizeUnit    The parsed size unit (e.g. BYTES, KILOBYTES, MEGABYTES).
     * @param paradigm    The selected execution paradigm ("vt", "rx", "loop").
     */
    record ParsedArguments(
        String directory,
        double maxFSInput,
        int nb,
        SizeUnit sizeUnit,
        String paradigm
    ) { }

    /**
     * Entry point to launch the CLI directory scan.
     * Parses arguments, validates directory, instantiates appropriate paradigm runner,
     * and prints progress and final report.
     *
     * @param args Command-line arguments: [directory] [maxFS] [nb] [sizeUnit?] [paradigm?]
     */
    public static void main(String[] args) {
        ParsedArguments parsed = parseArguments(args);
        if (parsed == null) {
            System.exit(1);
            return;
        }

        final SizeUnit displayUnit = parsed.sizeUnit;
        long maxFS = parsed.sizeUnit.toBytes(parsed.maxFSInput);

        File dir;
        try {
            dir = FSUtils.validateDirectory(parsed.directory);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Starting CLI scan using paradigm: " + parsed.paradigm.toUpperCase());
        System.out.println("Directory: " + dir.getAbsolutePath());
        System.out.println("Max Size Threshold: " + displayUnit.format(maxFS) + " (" + maxFS + " bytes)");
        System.out.println("Number of bands: " + parsed.nb);
        System.out.println("----------------------------------------------");

        CountDownLatch completionLatch = new CountDownLatch(1);

        FSReportListener listener = new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {
                System.out.printf("\rProgress: %d files scanned...", report.totalFiles());
                System.out.flush();
            }

            @Override
            public void onCompleted(FSReport report) {
                System.out.printf("\rProgress: %d files scanned... Done!%n", report.totalFiles());
                printFinalReport(report, displayUnit);
                completionLatch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("\nScan failed with error: " + error.getMessage());
                completionLatch.countDown();
            }
        };

        switch (parsed.paradigm) {
            case "vt" -> VirtualThreadsFSStat.getFSReport(parsed.directory, maxFS, parsed.nb, listener);
            case "loop" -> EventLoopFSStat.getFSReport(parsed.directory, maxFS, parsed.nb, listener);
            case "rx" -> subscribeReactiveScan(ReactiveFSStat.getFSReport(parsed.directory, maxFS, parsed.nb), listener, completionLatch);
            default -> {
                System.err.println("Unknown paradigm: " + parsed.paradigm + ". Use: vt, loop, or rx.");
                System.exit(1);
            }
        }

        try {
            completionLatch.await();
        } catch (InterruptedException e) {
            System.err.println("Execution interrupted.");
        }

        // Force shutdown RxJava schedulers if any were active
        Schedulers.shutdown();
        System.exit(0);
    }

    /**
     * Parses raw command-line argument strings into a validated {@link ParsedArguments} object.
     *
     * @param args Raw string arguments passed to main.
     * @return ParsedArguments object or null if mandatory arguments are missing.
     */
    static ParsedArguments parseArguments(String[] args) {
        if (args == null || args.length < 3) {
            System.err.println("Usage: java -cp ... pcd.assignment2.cli.FSStatCLI <directory> <maxFS> <nb> [sizeUnit: B|KiB|MiB|GiB] [paradigm: vt|rx|loop]");
            System.err.println("Example: java -cp ... pcd.assignment2.cli.FSStatCLI . 10 5 MB vt");
            return null;
        }

        String directory = args[0];
        double maxFSInput = Double.parseDouble(args[1]);
        int nb = Integer.parseInt(args[2]);
        SizeUnit sizeUnit = SizeUnit.BYTES;
        String paradigm = "vt";

        for (int i = 3; i < args.length; i++) {
            String value = args[i].toLowerCase();
            switch (value) {
                case "vt", "rx", "loop" -> paradigm = value;
                default -> sizeUnit = SizeUnit.parse(value);
            }
        }

        return new ParsedArguments(directory, maxFSInput, nb, sizeUnit, paradigm);
    }

    /**
     * Subscribes an {@link FSReportListener} to an RxJava {@link Observable} stream of report snapshots.
     *
     * @param reportStream    The reactive stream emitting report updates.
     * @param listener        The listener callback to receive updates.
     * @param completionLatch CountDownLatch released when the stream completes or encounters an error.
     * @return Disposable handle to manage subscription lifecycle.
     */
    static Disposable subscribeReactiveScan(
        Observable<FSReport> reportStream,
        FSReportListener listener,
        CountDownLatch completionLatch
    ) {
        final FSReport[] lastReport = new FSReport[1];
        return reportStream.subscribe(
            report -> {
                lastReport[0] = report;
                listener.onUpdate(report);
            },
            error -> {
                listener.onError(error);
                completionLatch.countDown();
            },
            () -> {
                if (lastReport[0] != null) {
                    listener.onCompleted(lastReport[0]);
                } else {
                    completionLatch.countDown();
                }
            }
        );
    }

    /**
     * Prints the formatted summary table of file size band counts to standard output.
     *
     * @param report      The final FSReport instance containing scan metrics.
     * @param displayUnit The SizeUnit used to format band boundary labels.
     */
    private static void printFinalReport(FSReport report, SizeUnit displayUnit) {
        System.out.println("\n==============================================");
        System.out.println("FINAL FILE SIZE DISTRIBUTION REPORT");
        System.out.println("==============================================");
        System.out.println("Directory Scanned: " + report.directory());
        System.out.println("Total Files Scanned: " + report.totalFiles());
        System.out.println("Duration: " + report.formatDuration());
        System.out.println("----------------------------------------------");
        System.out.printf("%-30s | %-10s%n", "Size Range Band", "File Count");
        System.out.println("----------------------------------------------");
        long[] counts = report.bandsCount();
        for (int i = 0; i < counts.length; i++) {
            System.out.printf("%-30s | %-10d%n", report.getBandLabel(i, displayUnit), counts[i]);
        }
        System.out.println("==============================================");
    }
}
