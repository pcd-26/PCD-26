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

    static final class ParsedArguments {
        final String directory;
        final double maxFSInput;
        final int nb;
        final SizeUnit sizeUnit;
        final String paradigm;

        ParsedArguments(String directory, double maxFSInput, int nb, SizeUnit sizeUnit, String paradigm) {
            this.directory = directory;
            this.maxFSInput = maxFSInput;
            this.nb = nb;
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

        final SizeUnit displayUnit = parsed.sizeUnit;
        long maxFS = parsed.sizeUnit.toBytes(parsed.maxFSInput);

        File dir = new File(parsed.directory);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("Error: Target path is not a valid directory: " + parsed.directory);
            System.exit(1);
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
                System.out.print(String.format("\rProgress: %d files scanned...", report.totalFiles()));
                System.out.flush();
            }

            @Override
            public void onCompleted(FSReport report) {
                System.out.print(String.format("\rProgress: %d files scanned... Done!%n", report.totalFiles()));
                printFinalReport(report, displayUnit);
                completionLatch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("\nScan failed with error: " + error.getMessage());
                completionLatch.countDown();
            }
        };

        if ("vt".equals(parsed.paradigm)) {
            VirtualThreadsFSStat.getFSReport(parsed.directory, maxFS, parsed.nb, listener);
        } else if ("loop".equals(parsed.paradigm)) {
            EventLoopFSStat.getFSReport(parsed.directory, maxFS, parsed.nb, listener);
        } else if ("rx".equals(parsed.paradigm)) {
            subscribeReactiveScan(ReactiveFSStat.getFSReport(parsed.directory, maxFS, parsed.nb), listener, completionLatch);
        } else {
            System.err.println("Unknown paradigm: " + parsed.paradigm + ". Use: vt, loop, or rx.");
            System.exit(1);
        }

        try {
            completionLatch.await();
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

        String directory = args[0];
        double maxFSInput = Double.parseDouble(args[1]);
        int nb = Integer.parseInt(args[2]);
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

        return new ParsedArguments(directory, maxFSInput, nb, sizeUnit, paradigm);
    }

    static void subscribeReactiveScan(
        Observable<FSReport> reportStream,
        FSReportListener listener,
        CountDownLatch completionLatch
    ) {
        final FSReport[] lastReport = new FSReport[1];
        reportStream.subscribe(
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

    private static void printFinalReport(FSReport report) {
        printFinalReport(report, SizeUnit.BYTES);
    }

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
