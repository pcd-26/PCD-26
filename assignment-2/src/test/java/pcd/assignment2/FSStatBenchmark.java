package pcd.assignment2;

import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportListener;
import pcd.assignment2.eventloop.EventLoopFSStat;
import pcd.assignment2.reactive.ReactiveFSStat;
import pcd.assignment2.virtualthreads.VirtualThreadsFSStat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Benchmark runner to measure and compare execution performance of the three filesystem analysis paradigms.
 */
public class FSStatBenchmark {

    private static final int WARMUP_RUNS = 2;
    private static final int MEASURE_RUNS = 5;
    private static final long MAX_FS = 10 * 1024 * 1024; // 10MB
    private static final int NB = 5;

    /**
     * Executes the benchmark scanning the specified directory path (defaults to root workspace directory).
     *
     * @param args Custom target directory (optional).
     * @throws Exception If thread waiting or execution fails.
     */
    public static void main(String[] args) throws Exception {
        String targetDir = args.length > 0 ? args[0] : ".";
        File dir = new File(targetDir);
        System.out.println("=================================================");
        System.out.println("FSStat Concurrency Benchmark");
        System.out.println("Target Directory: " + dir.getAbsolutePath());
        System.out.println("=================================================");

        // Warmup
        System.out.println("Running warmup runs...");
        for (int i = 0; i < WARMUP_RUNS; i++) {
            runVirtualThreads(targetDir);
            runReactive(targetDir);
            runEventLoop(targetDir);
        }
        System.out.println("Warmup complete. Running measurements...");

        // Measure Virtual Threads
        List<Long> vtTimes = new ArrayList<>();
        long vtFiles = 0;
        for (int i = 0; i < MEASURE_RUNS; i++) {
            System.out.print("Virtual Threads run " + (i + 1) + "/" + MEASURE_RUNS + "... ");
            FSReport r = runVirtualThreads(targetDir);
            vtTimes.add(r.durationMs());
            vtFiles = r.totalFiles();
            System.out.println(r.durationMs() + " ms (" + r.totalFiles() + " files)");
        }

        // Measure Reactive
        List<Long> rxTimes = new ArrayList<>();
        long rxFiles = 0;
        for (int i = 0; i < MEASURE_RUNS; i++) {
            System.out.print("Reactive (RxJava) run " + (i + 1) + "/" + MEASURE_RUNS + "... ");
            FSReport r = runReactive(targetDir);
            rxTimes.add(r.durationMs());
            rxFiles = r.totalFiles();
            System.out.println(r.durationMs() + " ms (" + r.totalFiles() + " files)");
        }

        // Measure Event-Loop
        List<Long> evTimes = new ArrayList<>();
        long evFiles = 0;
        for (int i = 0; i < MEASURE_RUNS; i++) {
            System.out.print("Event-Loop (Vert.x) run " + (i + 1) + "/" + MEASURE_RUNS + "... ");
            FSReport r = runEventLoop(targetDir);
            evTimes.add(r.durationMs());
            evFiles = r.totalFiles();
            System.out.println(r.durationMs() + " ms (" + r.totalFiles() + " files)");
        }

        // Report comparisons
        printComparisonTable("Virtual Threads", vtTimes, vtFiles,
                             "Reactive (RxJava)", rxTimes, rxFiles,
                             "Event-Loop (Vert.x)", evTimes, evFiles);

        // Shutdown RxJava schedulers to let lingering threads terminate immediately
        io.reactivex.rxjava3.schedulers.Schedulers.shutdown();
    }

    private static FSReport runVirtualThreads(String directory) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        FSReport[] result = new FSReport[1];
        VirtualThreadsFSStat.getFSReport(directory, MAX_FS, NB, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {}

            @Override
            public void onCompleted(FSReport report) {
                result[0] = report;
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                latch.countDown();
            }
        });
        latch.await();
        return result[0];
    }

    private static FSReport runReactive(String directory) {
        return ReactiveFSStat.getFSReport(directory, MAX_FS, NB)
            .blockingLast();
    }

    private static FSReport runEventLoop(String directory) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        FSReport[] result = new FSReport[1];
        EventLoopFSStat.getFSReport(directory, MAX_FS, NB, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {}

            @Override
            public void onCompleted(FSReport report) {
                result[0] = report;
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                latch.countDown();
            }
        });
        latch.await();
        return result[0];
    }

    private static void printComparisonTable(
        String label1, List<Long> times1, long files1,
        String label2, List<Long> times2, long files2,
        String label3, List<Long> times3, long files3
    ) {
        System.out.println("\n=================================================");
        System.out.println("BENCHMARK RESULTS COMPARISON");
        System.out.println("=================================================");
        System.out.printf("%-25s | %-12s | %-12s | %-12s%n", "Paradigm", "Median (ms)", "Average (ms)", "Files Scanned");
        System.out.println("-------------------------------------------------------------------------");
        printRow(label1, times1, files1);
        printRow(label2, times2, files2);
        printRow(label3, times3, files3);
        System.out.println("=================================================\n");
    }

    private static void printRow(String label, List<Long> times, long files) {
        double avg = times.stream().mapToLong(Long::longValue).average().orElse(0.0);
        Collections.sort(times);
        long median = times.get(times.size() / 2);
        System.out.printf("%-25s | %-12d | %-12.1f | %-12d%n", label, median, avg, files);
    }
}
