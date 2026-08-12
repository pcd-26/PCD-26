package pcd.fsstat.common;

/** Handle for a running scan. */
public interface FSReportJob {
    /** Requests scan cancellation. */
    void cancel();

    /** Returns whether cancellation was requested. */
    boolean isCancelled();
}
