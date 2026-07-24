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

/** Computes directory file statistics asynchronously using Vert.x. */
public class EventLoopFSStat {

    private enum PathCheck {
        VALID,
        DUPLICATE,
        NOT_DIRECTORY,
        NOT_READABLE
    }

    private record PathCheckResult(PathCheck status) { }

    private static class ScanState {
        private volatile boolean cancelled = false;
        final AtomicInteger outstandingTasks = new AtomicInteger(0);
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicLong totalFiles = new AtomicLong(0);
        final AtomicLong[] bandsCount;
        final Set<String> seenDirectories = ConcurrentHashMap.newKeySet();
        long timerId = -1;

        ScanState(int nb) {
            bandsCount = FSUtils.initAtomicLongs(nb + 1);
        }

        void requestCancel() {
            this.cancelled = true;
        }

        boolean cancelled() {
            return cancelled;
        }
    }

    public static FSReportJob getFSReport(String directory, long maxFS, int nb, FSReportListener listener) {
        Vertx vertx = Vertx.vertx();
        FileSystem fs = vertx.fileSystem();
        ScanState state = new ScanState(nb);
        long startTime = System.currentTimeMillis();

        state.timerId = vertx.setPeriodic(100, id -> {
            if (state.cancelled() || state.closed.get()) {
                return;
            }
            listener.onUpdate(FSUtils.createReport(directory, maxFS, nb, state.bandsCount, state.totalFiles, startTime));
        });

        Runnable completeIfDone = () -> {
            if (state.outstandingTasks.get() == 0 && state.closed.compareAndSet(false, true)) {
                if (state.timerId != -1) {
                    vertx.cancelTimer(state.timerId);
                }
                if (!state.cancelled()) {
                    listener.onCompleted(FSUtils.createReport(directory, maxFS, nb, state.bandsCount, state.totalFiles, startTime));
                }
                vertx.close();
            }
        };

        state.outstandingTasks.incrementAndGet();
        vertx.runOnContext(v -> scanDirectoryAsync(directory, maxFS, nb, fs, state, completeIfDone, vertx, listener));

        return new FSReportJob() {
            @Override
            public void cancel() {
                state.requestCancel();
                if (state.timerId != -1) {
                    vertx.cancelTimer(state.timerId);
                }
                if (state.outstandingTasks.get() == 0 && state.closed.compareAndSet(false, true)) {
                    vertx.close();
                }
            }

            @Override
            public boolean isCancelled() {
                return state.cancelled();
            }
        };
    }

    private static void scanDirectoryAsync(
        String path,
        long maxFS,
        int nb,
        FileSystem fs,
        ScanState state,
        Runnable completeIfDone,
        Vertx vertx,
        FSReportListener listener
    ) {
        if (state.cancelled()) {
            completeTask(state, completeIfDone);
            return;
        }

        validatePathAsync(vertx, path, state).onComplete(pathCheckResult -> {
            if (state.cancelled()) {
                completeTask(state, completeIfDone);
                return;
            }

            if (pathCheckResult.failed()) {
                if (state.outstandingTasks.get() == 1) {
                    listener.onError(pathCheckResult.cause());
                    state.requestCancel();
                    if (state.timerId != -1) {
                        vertx.cancelTimer(state.timerId);
                    }
                }
                completeTask(state, completeIfDone);
                return;
            }

            PathCheckResult pathCheck = pathCheckResult.result();
            if (pathCheck.status() == PathCheck.DUPLICATE) {
                completeTask(state, completeIfDone);
                return;
            }
            if (pathCheck.status() == PathCheck.NOT_DIRECTORY || pathCheck.status() == PathCheck.NOT_READABLE) {
                if (state.outstandingTasks.get() == 1) {
                    listener.onError(new IllegalArgumentException("Target directory is not a readable directory: " + path));
                    state.requestCancel();
                    if (state.timerId != -1) {
                        vertx.cancelTimer(state.timerId);
                    }
                }
                completeTask(state, completeIfDone);
                return;
            }

            fs.readDir(path).onComplete(res -> {
                if (state.cancelled()) {
                    completeTask(state, completeIfDone);
                    return;
                }

                if (res.succeeded()) {
                    for (String childPath : res.result()) {
                        state.outstandingTasks.incrementAndGet();
                        fs.props(childPath).onComplete(propsRes -> {
                            if (state.cancelled()) {
                                completeTask(state, completeIfDone);
                                return;
                            }

                            if (propsRes.succeeded()) {
                                FileProps props = propsRes.result();
                                if (props.isDirectory()) {
                                    state.outstandingTasks.incrementAndGet();
                                    scanDirectoryAsync(childPath, maxFS, nb, fs, state, completeIfDone, vertx, listener);
                                } else if (props.isRegularFile()) {
                                    state.totalFiles.incrementAndGet();
                                    long size = props.size();
                                    int idx = FSReport.getBandIndex(size, maxFS, nb);
                                    state.bandsCount[idx].incrementAndGet();
                                }
                            }

                            completeTask(state, completeIfDone);
                        });
                    }
                } else {
                    if (state.outstandingTasks.get() == 1) {
                        listener.onError(res.cause());
                        state.requestCancel();
                        if (state.timerId != -1) {
                            vertx.cancelTimer(state.timerId);
                        }
                    }
                }

                completeTask(state, completeIfDone);
            });
        });
    }

    private static Future<PathCheckResult> validatePathAsync(Vertx vertx, String path, ScanState state) {
        return vertx.executeBlocking(() -> {
            File fileObj = new File(path);
            String canonicalPath = fileObj.getCanonicalPath();
            if (!fileObj.exists() || !fileObj.isDirectory()) {
                return new PathCheckResult(PathCheck.NOT_DIRECTORY);
            }
            if (!state.seenDirectories.add(canonicalPath)) {
                return new PathCheckResult(PathCheck.DUPLICATE);
            }
            if (!fileObj.canRead()) {
                return new PathCheckResult(PathCheck.NOT_READABLE);
            }
            return new PathCheckResult(PathCheck.VALID);
        });
    }

    private static void completeTask(ScanState state, Runnable completeIfDone) {
        if (state.outstandingTasks.decrementAndGet() == 0) {
            completeIfDone.run();
        }
    }
}
