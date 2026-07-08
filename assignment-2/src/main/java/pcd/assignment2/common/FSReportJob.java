package pcd.assignment2.common;

/**
 * Represents a handle to a running filesystem statistics report job.
 * Provides methods to cancel the ongoing scan and query its cancellation status.
 */
public interface FSReportJob {
    /**
     * Cancels the directory scanning task.
     * This will stop the recursive traversal as soon as possible and release allocated resources.
     */
    void cancel();

    /**
     * Checks if the directory scanning task has been cancelled.
     *
     * @return true if the job was cancelled, false otherwise.
     */
    boolean isCancelled();
}
