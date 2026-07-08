package pcd.assignment2.common;

/**
 * Callback listener for receiving updates, completion events, and errors
 * during asynchronous filesystem statistics report generation.
 */
public interface FSReportListener {
    /**
     * Called periodically during execution to provide intermediate/partial progress updates.
     *
     * @param report The current state of the filesystem statistics report.
     */
    void onUpdate(FSReport report);

    /**
     * Called when the directory traversal and analysis successfully complete.
     *
     * @param report The final, complete filesystem statistics report.
     */
    void onCompleted(FSReport report);

    /**
     * Called when the scanning fails due to an unexpected error (e.g. invalid directory, permission error).
     *
     * @param error The exception that caused the scan to fail.
     */
    void onError(Throwable error);
}
