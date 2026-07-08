package pcd.assignment2.cli;

import io.reactivex.rxjava3.core.Observable;
import org.junit.jupiter.api.Test;
import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class FSStatCLITest {

    @Test
    public void subscribeReactiveScanUsesSingleSubscription() throws Exception {
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicReference<FSReport> completedReport = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Observable<FSReport> stream = Observable.defer(() -> {
            subscriptions.incrementAndGet();
            return Observable.just(new FSReport("tmp", 100, 2, new long[] {1, 0, 0}, 1, 12));
        });

        FSStatCLI.subscribeReactiveScan(stream, new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {
                // ignore
            }

            @Override
            public void onCompleted(FSReport report) {
                completedReport.set(report);
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                fail(error.getMessage());
            }
        }, latch);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, subscriptions.get());
        assertNotNull(completedReport.get());
        assertEquals(1, completedReport.get().totalFiles());
    }
}
