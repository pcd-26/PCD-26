package pcd.fsstat.cli;

import io.reactivex.rxjava3.core.Observable;
import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSReportListener;
import pcd.fsstat.common.SizeUnit;
import pcd.fsstat.eventloop.EventLoopFSStat;
import pcd.fsstat.reactive.ReactiveFSStat;
import pcd.fsstat.virtualthreads.VirtualThreadsFSStat;

import java.io.File;
import java.util.concurrent.CountDownLatch;

/**
 * Command-line interface runner for the FSStat library.
 * Allows running directory scans and outputting results to the console without a GUI.
 */
public class FSStatCLI {
    private static final String PARADIGM_VT = "vt";
    private static final String PARADIGM_RX = "rx";
    private static final String PARADIGM_LOOP = "loop";

    static final class ParsedArguments {
        final String directoryPath;
        final double maximumFileSizeInput;
        final int numberOfBands;
        final SizeUnit sizeUnit;
        final String paradigm;

        ParsedArguments(String directoryPath, double maximumFileSizeInput, int numberOfBands, SizeUnit sizeUnit, String paradigm) {
            this.directoryPath = directoryPath;
            this.maximumFileSizeInput = maximumFileSizeInput;
            this.numberOfBands = numberOfBands;
            this.sizeUnit = sizeUnit;
            this.paradigm = paradigm;
        }
    }

    /**
     * Entry point to launch the CLI directory scan.
     *
     * @param args Command-line arguments: [directory] [maxFS] [nb] [sizeUnit?] [paradigm?]
     */
    public static void main(String[] args) {
        ParsedArguments parsed = parseArguments(args);
        if (parsed == null) {
            System.exit(1);
            return;
        }

        final SizeUnit outputUnit = parsed.sizeUnit;
        long maximumFileSizeBytes = parsed.sizeUnit.toBytes(parsed.maximumFileSizeInput);

        File targetDirectory = new File(parsed.directoryPath);
        if (!targetDirectory.exists() || !targetDirectory.isDirectory()) {
            System.err.println("Error: Target path is not a valid directory: " + parsed.directoryPath);
            System.exit(1);
        }

        System.out.println("Starting CLI scan using paradigm: " + parsed.paradigm.toUpperCase());
        System.out.println("Directory: " + targetDirectory.getAbsolutePath());
        System.out.println("Max Size Threshold: " + outputUnit.format(maximumFileSizeBytes) + " (" + maximumFileSizeBytes + " bytes)");
        System.out.println("Number of bands: " + parsed.numberOfBands);
        System.out.println("----------------------------------------------");

        CountDownLatch completionSignal = new CountDownLatch(1);

        FSReportListener reportListener = new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {
                System.out.print(String.format("\rProgress: %d files scanned...", report.totalFiles()));
                System.out.flush();
            }

            @Override
            public void onCompleted(FSReport report) {
                System.out.print(String.format("\rProgress: %d files scanned... Done!%n", report.totalFiles()));
                printFinalReport(report, outputUnit);
                completionSignal.countDown();
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("\nScan failed with error: " + error.getMessage());
                completionSignal.countDown();
            }
        };

        dispatchScan(parsed.directoryPath, maximumFileSizeBytes, parsed.numberOfBands, parsed.paradigm, reportListener, completionSignal);

        try {
            completionSignal.await();
        } catch (InterruptedException e) {
            System.err.println("Execution interrupted.");
        }

        // Force shutdown RxJava schedulers if any were active
        io.reactivex.rxjava3.schedulers.Schedulers.shutdown();
        System.exit(0);
    }

    static ParsedArguments parseArguments(String[] args) {
        if (args == null || args.length < 3) {
            System.err.println("Usage: java -cp ... pcd.fsstat.cli.FSStatCLI <directory> <maxFS> <nb> [sizeUnit: B|KiB|MiB|GiB] [paradigm: vt|rx|loop]");
            System.err.println("Example: java -cp ... pcd.fsstat.cli.FSStatCLI . 10 5 MB vt");
            return null;
        }

        String directoryPath = args[0];
        double maximumFileSizeInput = Double.parseDouble(args[1]);
        int numberOfBands = Integer.parseInt(args[2]);
        SizeUnit sizeUnit = SizeUnit.BYTES;
        String paradigm = "vt";

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

    static void dispatchScan(
        String directoryPath,
        long maximumFileSizeBytes,
        int numberOfBands,
        String paradigm,
        FSReportListener reportListener,
        CountDownLatch completionSignal
    ) {
        if (PARADIGM_VT.equals(paradigm)) {
            runVirtualThreadsScan(directoryPath, maximumFileSizeBytes, numberOfBands, reportListener);
            return;
        }
        if (PARADIGM_LOOP.equals(paradigm)) {
            runEventLoopScan(directoryPath, maximumFileSizeBytes, numberOfBands, reportListener);
            return;
        }
        if (PARADIGM_RX.equals(paradigm)) {
            runReactiveScan(directoryPath, maximumFileSizeBytes, numberOfBands, reportListener, completionSignal);
            return;
        }

        System.err.println("Unknown paradigm: " + paradigm + ". Use: vt, loop, or rx.");
        System.exit(1);
    }

    static void runVirtualThreadsScan(String directoryPath, long maximumFileSizeBytes, int numberOfBands, FSReportListener reportListener) {
        VirtualThreadsFSStat.getFSReport(directoryPath, maximumFileSizeBytes, numberOfBands, reportListener);
    }

    static void runEventLoopScan(String directoryPath, long maximumFileSizeBytes, int numberOfBands, FSReportListener reportListener) {
        EventLoopFSStat.getFSReport(directoryPath, maximumFileSizeBytes, numberOfBands, reportListener);
    }

    static void runReactiveScan(
        String directoryPath,
        long maximumFileSizeBytes,
        int numberOfBands,
        FSReportListener reportListener,
        CountDownLatch completionSignal
    ) {
        subscribeReactiveScan(ReactiveFSStat.getFSReport(directoryPath, maximumFileSizeBytes, numberOfBands), reportListener, completionSignal);
    }

    static void subscribeReactiveScan(
        Observable<FSReport> reportStream,
        FSReportListener reportListener,
        CountDownLatch completionSignal
    ) {
        final FSReport[] latestReport = new FSReport[1];
        reportStream.subscribe(
            report -> {
                latestReport[0] = report;
                reportListener.onUpdate(report);
            },
            error -> {
                reportListener.onError(error);
                completionSignal.countDown();
            },
            () -> {
                if (latestReport[0] != null) {
                    reportListener.onCompleted(latestReport[0]);
                } else {
                    completionSignal.countDown();
                }
            }
        );
    }

    private static void printFinalReport(FSReport report, SizeUnit outputUnit) {
        System.out.println("\n==============================================");
        System.out.println("FINAL FILE SIZE DISTRIBUTION REPORT");
        System.out.println("==============================================");
        System.out.println("Directory Scanned: " + report.directory());
        System.out.println("Total Files Scanned: " + report.totalFiles());
        System.out.println("Duration: " + report.formatDuration());
        System.out.println("----------------------------------------------");
        System.out.printf("%-30s | %-10s%n", "Size Range Band", "File Count");
        System.out.println("----------------------------------------------");
        long[] bandCounts = report.bandsCount();
        for (int bandIndex = 0; bandIndex < bandCounts.length; bandIndex++) {
            System.out.printf("%-30s | %-10d%n", report.getBandLabel(bandIndex, outputUnit), bandCounts[bandIndex]);
        }
        System.out.println("==============================================");
    }
}
