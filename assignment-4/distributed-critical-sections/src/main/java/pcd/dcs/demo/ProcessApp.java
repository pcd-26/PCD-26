package pcd.dcs.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pcd.dcs.DistributedCriticalSection;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

// Multi-iteration demo process that competes for the same distributed critical section.
public class ProcessApp {

    private static final Logger logger = LoggerFactory.getLogger(ProcessApp.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String SHARED_LOG_FILE = "dcs_shared.log";

    // Run a demo client that repeatedly enters and exits the shared critical section.
    public static void main(String[] args) {
        String processId = args.length > 0 ? args[0] : "Process-" + new Random().nextInt(1000);
        String host = args.length > 1 ? args[1] : "localhost";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 5672;

        System.out.printf("[%s] [%s] Starting process application using RabbitMQ at %s:%d\n",
                LocalTime.now().format(TIME_FORMATTER), processId, host, port);

        try (DistributedCriticalSection dcs = new DistributedCriticalSection(host, port, "demo-cs")) {
            Random random = new Random();

            for (int i = 1; i <= 5; i++) {
                String waitTime = LocalTime.now().format(TIME_FORMATTER);
                System.out.printf("[%s] [%s] (Iteration %d/5) Requesting entry to critical section...\n",
                        waitTime, processId, i);

                // Wait until this process consumes the shared token.
                dcs.enter();

                String enterTime = LocalTime.now().format(TIME_FORMATTER);
                System.out.printf("[%s] [%s] ENTERED critical section.\n", enterTime, processId);

                // Record the entry in a shared file so concurrent runs can be inspected later.
                logToSharedFile(String.format("[%s] %s ENTER\n", enterTime, processId));

                // Simulate protected work while holding the token.
                try {
                    Thread.sleep(1500 + random.nextInt(1000));
                } catch (InterruptedException e) {
                    logger.warn("[{}] Interrupted during critical section work", processId, e);
                    Thread.currentThread().interrupt();
                }

                String exitTime = LocalTime.now().format(TIME_FORMATTER);
                logToSharedFile(String.format("[%s] %s EXIT\n", exitTime, processId));
                System.out.printf("[%s] [%s] Exiting critical section.\n", exitTime, processId);

                // Return the token to RabbitMQ so another process can enter.
                dcs.exit();

                // Pause outside the critical section before the next request.
                long sleepTime = 500 + random.nextInt(1500);
                Thread.sleep(sleepTime);
            }

            System.out.printf("[%s] [%s] Completed all iterations successfully.\n",
                    LocalTime.now().format(TIME_FORMATTER), processId);

        } catch (Exception e) {
            logger.error("[{}] Error in process application", processId, e);
        }
    }

    // Append a log line atomically inside the local JVM.
    private static synchronized void logToSharedFile(String message) {
        try (FileWriter fw = new FileWriter(SHARED_LOG_FILE, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.print(message);
            pw.flush();
        } catch (IOException e) {
            logger.error("Error writing to shared file '{}'", SHARED_LOG_FILE, e);
        }
    }
}
