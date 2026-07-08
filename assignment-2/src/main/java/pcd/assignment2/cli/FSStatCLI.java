package pcd.assignment2.cli;

import io.reactivex.rxjava3.core.Observable;
import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportListener;
import pcd.assignment2.eventloop.EventLoopFSStat;
import pcd.assignment2.reactive.ReactiveFSStat;
import pcd.assignment2.virtualthreads.VirtualThreadsFSStat;

import java.io.File;
import java.util.concurrent.CountDownLatch;

/**
 * Command-line interface runner for the FSStat library.
 * Allows running directory scans and outputting results to the console without a GUI.
 */
public class FSStatCLI {

    /**
     * Entry point to launch the CLI directory scan.
     *
     * @param args Command-line arguments: [directory] [maxFS] [nb] [paradigm (optional: vt|rx|loop)]
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: java -cp ... pcd.assignment2.cli.FSStatCLI <directory> <maxFS> <nb> [paradigm: vt|rx|loop]");
            System.err.println("Example: java -cp ... pcd.assignment2.cli.FSStatCLI . 10485760 5 vt");
            System.exit(1);
        }

        String directory = args[0];
        long maxFS = Long.parseLong(args[1]);
        int nb = Integer.parseInt(args[2]);
        String paradigm = args.length > 3 ? args[3].toLowerCase() : "vt";

        File dir = new File(directory);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("Error: Target path is not a valid directory: " + directory);
            System.exit(1);
        }

        System.out.println("Starting CLI scan using paradigm: " + paradigm.toUpperCase());
        System.out.println("Directory: " + dir.getAbsolutePath());
        System.out.println("Max Size Threshold: " + maxFS + " bytes");
        System.out.println("Number of bands: " + nb);
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
                printFinalReport(report);
                completionLatch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("\nScan failed with error: " + error.getMessage());
                completionLatch.countDown();
            }
        };

        if ("vt".equals(paradigm)) {
            VirtualThreadsFSStat.getFSReport(directory, maxFS, nb, listener);
        } else if ("loop".equals(paradigm)) {
            EventLoopFSStat.getFSReport(directory, maxFS, nb, listener);
        } else if ("rx".equals(paradigm)) {
            subscribeReactiveScan(ReactiveFSStat.getFSReport(directory, maxFS, nb), listener, completionLatch);
        } else {
            System.err.println("Unknown paradigm: " + paradigm + ". Use: vt, loop, or rx.");
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
        System.out.println("\n==============================================");
        System.out.println("FINAL FILE SIZE DISTRIBUTION REPORT");
        System.out.println("==============================================");
        System.out.println("Directory Scanned: " + report.directory());
        System.out.println("Total Files Scanned: " + report.totalFiles());
        System.out.println("Duration: " + report.durationMs() + " ms");
        System.out.println("----------------------------------------------");
        System.out.printf("%-30s | %-10s%n", "Size Range Band", "File Count");
        System.out.println("----------------------------------------------");
        long[] counts = report.bandsCount();
        for (int i = 0; i < counts.length; i++) {
            System.out.printf("%-30s | %-10d%n", report.getBandLabel(i), counts[i]);
        }
        System.out.println("==============================================");
    }
}
