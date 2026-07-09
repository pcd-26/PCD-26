package pcd.assignment2.eventloop;

import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.FileProps;
import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportJob;
import pcd.assignment2.common.FSReportListener;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Computes directory file statistics asynchronously using Eclipse Vert.x non-blocking Event-Loop APIs.
 *
 * <p><strong>Shared State & Synchronization Strategy:</strong>
 * <ul>
 *   <li>The Event-Loop model operates on the principle that code executing on a specific context is single-threaded,
 *       avoiding traditional synchronization blockages.</li>
 *   <li>However, since updates and cancel triggers can cross different Vert.x or caller thread contexts,
 *       we store the intermediate counts in {@link AtomicLong} and {@link AtomicInteger} wrappers to ensure thread safety
 *       between the reader callbacks, updater timer callbacks, and user cancel actions.</li>
 *   <li>Traversals are tracked using a pending operations counter ({@code pendingOps}).
 *       Every async operation ({@code readDir}, {@code props}) increments {@code pendingOps}.
 *       Upon callback receipt, the counter is decremented. When it reaches 0, the scan completes.</li>
 *   <li>Cancellation is handled by checking a {@code volatile boolean} flag before launching subsequent operations
 *       and immediately closing the {@link Vertx} instance to shut down underlying threads.</li>
 * </ul>
 */
public class EventLoopFSStat {

    /**
     * Holds the shared job execution and progress tracking state.
     */
    private static class JobState {
        /** Volatile boolean checked to abort processing quickly upon cancellation. */
        volatile boolean cancelled = false;
        /** Counter of active asynchronous filesystem operations. */
        final AtomicInteger pendingOps = new AtomicInteger(0);
        /** Total files successfully scanned. */
        final AtomicLong totalFiles = new AtomicLong(0);
        /** Array tracking the file count distribution per size band. */
        final AtomicLong[] bandsCount;
        /** Thread-safe set tracking already visited directory paths to avoid symlink loops. */
        final java.util.Set<String> visitedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Vert.x periodic timer ID for publishing updates. */
        long timerId = -1;

        /**
         * Creates a new JobState with the specified number of bands.
         *
         * @param nb The number of size bands.
         */
        JobState(int nb) {
            bandsCount = new AtomicLong[nb + 1];
            for (int i = 0; i <= nb; i++) {
                bandsCount[i] = new AtomicLong(0);
            }
        }
    }

    /**
     * Starts an asynchronous filesystem statistics scan using Vert.x non-blocking APIs.
     *
     * @param directory The root directory path to scan.
     * @param maxFS     The maximum file size threshold.
     * @param nb        The number of file size bands.
     * @param listener  The listener to notify of intermediate progress, completion, or errors.
     * @return An FSReportJob instance to control the running scan.
     */
    public static FSReportJob getFSReport(String directory, long maxFS, int nb, FSReportListener listener) {
        Vertx vertx = Vertx.vertx();
        FileSystem fs = vertx.fileSystem();
        JobState state = new JobState(nb);
        long startTime = System.currentTimeMillis();

        // Setup periodic updater
        state.timerId = vertx.setPeriodic(100, id -> {
            if (state.cancelled) {
                return;
            }
            long[] currentBands = new long[nb + 1];
            for (int i = 0; i <= nb; i++) {
                currentBands[i] = state.bandsCount[i].get();
            }
            FSReport report = new FSReport(
                directory,
                maxFS,
                nb,
                currentBands,
                state.totalFiles.get(),
                System.currentTimeMillis() - startTime
            );
            listener.onUpdate(report);
        });

        Runnable checkCompletion = () -> {
            if (state.pendingOps.get() == 0 && !state.cancelled) {
                if (state.timerId != -1) {
                    vertx.cancelTimer(state.timerId);
                }
                long[] finalBands = new long[nb + 1];
                for (int i = 0; i <= nb; i++) {
                    finalBands[i] = state.bandsCount[i].get();
                }
                FSReport report = new FSReport(
                    directory,
                    maxFS,
                    nb,
                    finalBands,
                    state.totalFiles.get(),
                    System.currentTimeMillis() - startTime
                );
                listener.onCompleted(report);
                vertx.close();
            }
        };

        // Start traversing
        state.pendingOps.incrementAndGet(); // for the initial read
        vertx.runOnContext(v -> {
            scanDirAsync(directory, maxFS, nb, fs, state, checkCompletion, vertx, listener);
        });

        return new FSReportJob() {
            @Override
            public void cancel() {
                state.cancelled = true;
                if (state.timerId != -1) {
                    vertx.cancelTimer(state.timerId);
                }
                vertx.close();
            }

            @Override
            public boolean isCancelled() {
                return state.cancelled;
            }
        };
    }

    /**
     * Performs a non-blocking asynchronous directory traversal.
     * Uses Vert.x FileSystem APIs to read directory items and query file properties.
     *
     * @param path            The directory path to read.
     * @param maxFS           The maximum file size threshold.
     * @param nb              The number of size bands.
     * @param fs              The Vert.x FileSystem instance.
     * @param state           The shared job status state.
     * @param checkCompletion Callback to execute when all operations are finished.
     * @param vertx           The Vert.x instance.
     * @param listener        The progress listener.
     */
    private static void scanDirAsync(
        String path,
        long maxFS,
        int nb,
        FileSystem fs,
        JobState state,
        Runnable checkCompletion,
        Vertx vertx,
        FSReportListener listener
    ) {
        if (state.cancelled) {
            if (state.pendingOps.decrementAndGet() == 0) {
                checkCompletion.run();
            }
            return;
        }

        try {
            java.io.File fileObj = new java.io.File(path);
            String canonicalPath = fileObj.getCanonicalPath();
            if (!state.visitedPaths.add(canonicalPath)) {
                if (state.pendingOps.decrementAndGet() == 0) {
                    checkCompletion.run();
                }
                return; // Cycle detected: skip this directory
            }
        } catch (java.io.IOException e) {
            if (state.pendingOps.decrementAndGet() == 0) {
                checkCompletion.run();
            }
            return; // Skip on path resolution failure
        }

        fs.readDir(path, res -> {
            if (state.cancelled) {
                if (state.pendingOps.decrementAndGet() == 0) {
                    checkCompletion.run();
                }
                return;
            }

            if (res.succeeded()) {
                for (String childPath : res.result()) {
                    state.pendingOps.incrementAndGet();
                    fs.props(childPath, propsRes -> {
                        if (state.cancelled) {
                            if (state.pendingOps.decrementAndGet() == 0) {
                                checkCompletion.run();
                            }
                            return;
                        }

                        if (propsRes.succeeded()) {
                            FileProps props = propsRes.result();
                            if (props.isDirectory()) {
                                state.pendingOps.incrementAndGet();
                                scanDirAsync(childPath, maxFS, nb, fs, state, checkCompletion, vertx, listener);
                            } else if (props.isRegularFile()) {
                                state.totalFiles.incrementAndGet();
                                long size = props.size();
                                int idx = FSReport.getBandIndex(size, maxFS, nb);
                                state.bandsCount[idx].incrementAndGet();
                            }
                        }

                        if (state.pendingOps.decrementAndGet() == 0) {
                            checkCompletion.run();
                        }
                    });
                }
            } else {
                if (state.pendingOps.get() == 1) { // Root directory read failed
                    listener.onError(res.cause());
                    state.cancelled = true;
                    if (state.timerId != -1) {
                        vertx.cancelTimer(state.timerId);
                    }
                    vertx.close();
                    return;
                }
            }

            if (state.pendingOps.decrementAndGet() == 0) {
                checkCompletion.run();
            }
        });
    }
}
