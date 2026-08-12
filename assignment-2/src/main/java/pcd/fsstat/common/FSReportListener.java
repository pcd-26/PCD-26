package pcd.fsstat.common;

/** Receives scan updates, completion, and failures. */
public interface FSReportListener {
    /** Receives a partial progress report. */
    void onUpdate(FSReport report);

    /** Receives the final report. */
    void onCompleted(FSReport report);

    /** Receives a scan failure. */
    void onError(Throwable error);
}
