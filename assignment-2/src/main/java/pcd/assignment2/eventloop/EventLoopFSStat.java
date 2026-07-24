package pcd.assignment2.eventloop;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileProps;
import io.vertx.core.file.FileSystem;
import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportJob;
import pcd.assignment2.common.FSReportListener;
import pcd.assignment2.common.FSUtils;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Computes directory file statistics asynchronously using Eclipse Vert.x non-blocking Event-Loop APIs.
 *
 * <p><strong>Shared State & Synchronization Strategy:</strong>
 * <ul>
 *   <li>The event-loop handlers use Vert.x async filesystem APIs for directory reads and file properties.</li>
 *   <li>Potentially blocking checks based on {@link File} are executed via {@link Vertx#executeBlocking(java.util.concurrent.Callable)}
 *       so the event-loop thread remains responsive.</li>
 *   <li>Intermediate counts are stored in {@link AtomicLong} and {@link AtomicInteger} wrappers because callbacks may
 *       cross event-loop and worker contexts.</li>
 *   <li>Traversals are tracked using a pending operations counter ({@code pendingOps}). Every async operation increments
 *       the counter and decrements it on completion. When it reaches 0, the scan completes.</li>
 *   <li>Cancellation is cooperative: a flag stops new work, while Vert.x is closed only after in-flight callbacks drain.</li>
 * </ul>
 */
public class EventLoopFSStat {

    private enum ValidationStatus {
        VALID,
        DUPLICATE,
        NOT_DIRECTORY,
        NOT_READABLE
    }

    private record ValidationResult(ValidationStatus status) { }

    /**
     * Holds the shared job execution and progress tracking state.
     */
    private static class JobState {
        /** Volatile boolean checked to abort processing quickly upon cancellation. */
        private volatile boolean cancelled = false;
        /** Counter of active asynchronous filesystem operations. */
        final AtomicInteger pendingOps = new AtomicInteger(0);
        /** Ensures Vert.x is closed at most once, after completion or cancellation drains. */
        final AtomicBoolean terminated = new AtomicBoolean(false);
        /** Total files successfully scanned. */
        final AtomicLong totalFiles = new AtomicLong(0);
        /** Array tracking the file count distribution per size band. */
        final AtomicLong[] bandsCount;
        /** Thread-safe set tracking already visited directory paths to avoid symlink loops. */
        final Set<String> visitedPaths = ConcurrentHashMap.newKeySet();
        /** Vert.x periodic timer ID for publishing updates. */
        long timerId = -1;

        /**
         * Creates a new JobState with the specified number of bands.
         *
         * @param nb The number of size bands.
         */
        JobState(int nb) {
            bandsCount = FSUtils.initAtomicLongs(nb + 1);
        }

        void cancel() {
            this.cancelled = true;
        }

        boolean isCancelled() {
            return cancelled;
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

        state.timerId = vertx.setPeriodic(100, id -> {
            if (state.isCancelled() || state.terminated.get()) {
                return;
            }
            listener.onUpdate(FSUtils.createReport(directory, maxFS, nb, state.bandsCount, state.totalFiles, startTime));
        });

        Runnable checkCompletion = () -> {
            if (state.pendingOps.get() == 0 && state.terminated.compareAndSet(false, true)) {
                if (state.timerId != -1) {
                    vertx.cancelTimer(state.timerId);
                }
                if (!state.isCancelled()) {
                    listener.onCompleted(FSUtils.createReport(directory, maxFS, nb, state.bandsCount, state.totalFiles, startTime));
                }
                vertx.close();
            }
        };

        state.pendingOps.incrementAndGet();
        vertx.runOnContext(v -> scanDirAsync(directory, maxFS, nb, fs, state, checkCompletion, vertx, listener));

        return new FSReportJob() {
            @Override
            public void cancel() {
                state.cancel();
                if (state.timerId != -1) {
                    vertx.cancelTimer(state.timerId);
                }
                if (state.pendingOps.get() == 0 && state.terminated.compareAndSet(false, true)) {
                    vertx.close();
                }
            }

            @Override
            public boolean isCancelled() {
                return state.isCancelled();
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
        if (state.isCancelled()) {
            decrementAndCheckCompletion(state, checkCompletion);
            return;
        }

        validatePathAsync(vertx, path, state).onComplete(validationRes -> {
            if (state.isCancelled()) {
                decrementAndCheckCompletion(state, checkCompletion);
                return;
            }

            if (validationRes.failed()) {
                if (state.pendingOps.get() == 1) {
                    listener.onError(validationRes.cause());
                    state.cancel();
                    if (state.timerId != -1) {
                        vertx.cancelTimer(state.timerId);
                    }
                }
                decrementAndCheckCompletion(state, checkCompletion);
                return;
            }

            ValidationResult validation = validationRes.result();
            if (validation.status() == ValidationStatus.DUPLICATE) {
                decrementAndCheckCompletion(state, checkCompletion);
                return;
            }
            if (validation.status() == ValidationStatus.NOT_DIRECTORY || validation.status() == ValidationStatus.NOT_READABLE) {
                if (state.pendingOps.get() == 1) {
                    listener.onError(new IllegalArgumentException("Target directory is not a readable directory: " + path));
                    state.cancel();
                    if (state.timerId != -1) {
                        vertx.cancelTimer(state.timerId);
                    }
                }
                decrementAndCheckCompletion(state, checkCompletion);
                return;
            }

            fs.readDir(path).onComplete(res -> {
                if (state.isCancelled()) {
                    decrementAndCheckCompletion(state, checkCompletion);
                    return;
                }

                if (res.succeeded()) {
                    for (String childPath : res.result()) {
                        state.pendingOps.incrementAndGet();
                        fs.props(childPath).onComplete(propsRes -> {
                            if (state.isCancelled()) {
                                decrementAndCheckCompletion(state, checkCompletion);
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

                            decrementAndCheckCompletion(state, checkCompletion);
                        });
                    }
                } else {
                    if (state.pendingOps.get() == 1) {
                        listener.onError(res.cause());
                        state.cancel();
                        if (state.timerId != -1) {
                            vertx.cancelTimer(state.timerId);
                        }
                    }
                }

                decrementAndCheckCompletion(state, checkCompletion);
            });
        });
    }

    private static Future<ValidationResult> validatePathAsync(Vertx vertx, String path, JobState state) {
        return vertx.executeBlocking(() -> {
            File fileObj = new File(path);
            String canonicalPath = fileObj.getCanonicalPath();
            if (!fileObj.exists() || !fileObj.isDirectory()) {
                return new ValidationResult(ValidationStatus.NOT_DIRECTORY);
            }
            if (!state.visitedPaths.add(canonicalPath)) {
                return new ValidationResult(ValidationStatus.DUPLICATE);
            }
            if (!fileObj.canRead()) {
                return new ValidationResult(ValidationStatus.NOT_READABLE);
            }
            return new ValidationResult(ValidationStatus.VALID);
        });
    }

    /**
     * Decrements pending operation count and triggers completion check if no active operations remain.
     *
     * @param state           The job execution state.
     * @param checkCompletion Completion callback to trigger when operations reach zero.
     */
    private static void decrementAndCheckCompletion(JobState state, Runnable checkCompletion) {
        if (state.pendingOps.decrementAndGet() == 0) {
            checkCompletion.run();
        }
    }
}
