package pcd.dcs.demo;

import pcd.dcs.DistributedCriticalSection;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

// Minimal demo for observing two concurrent clients competing for the same critical section.
public final class SingleRequestDemoApp {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String DEFAULT_PROCESS_ID = "Process";
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5672;
    private static final String DEFAULT_CS_NAME = "demo-cs";
    private static final long DEFAULT_WORK_MILLIS = 5000L;

    private SingleRequestDemoApp() {
    }

    // Run one critical-section request with clear console output.
    public static void main(String[] args) {
        String processId = args.length > 0 ? args[0] : DEFAULT_PROCESS_ID;
        String host = args.length > 1 ? args[1] : DEFAULT_HOST;
        int port = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_PORT;
        String csName = args.length > 3 ? args[3] : DEFAULT_CS_NAME;
        long workMillis = args.length > 4 ? Long.parseLong(args[4]) : DEFAULT_WORK_MILLIS;

        log(processId, "Starting demo on %s:%d for critical section '%s'".formatted(host, port, csName));

        try (DistributedCriticalSection dcs = new DistributedCriticalSection(host, port, csName)) {
            log(processId, "Requesting access to the critical section");

            long waitStart = System.currentTimeMillis();
            dcs.enter();
            long waitedMillis = System.currentTimeMillis() - waitStart;

            log(processId, "ENTERED critical section after waiting %d ms".formatted(waitedMillis));

            // Simulate work while holding the distributed token.
            log(processId, "Simulating protected work for %d ms".formatted(workMillis));
            Thread.sleep(workMillis);

            log(processId, "Releasing critical section");
            dcs.exit();
            log(processId, "Completed successfully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log(processId, "Interrupted: %s".formatted(e.getMessage()));
        } catch (Exception e) {
            log(processId, "Failed: %s".formatted(e.getMessage()));
            e.printStackTrace(System.err);
        }
    }

    // Print compact timestamps so concurrent terminals are easy to compare.
    private static void log(String processId, String message) {
        System.out.printf("[%s] [%s] %s%n", LocalTime.now().format(TIME_FORMATTER), processId, message);
    }
}
