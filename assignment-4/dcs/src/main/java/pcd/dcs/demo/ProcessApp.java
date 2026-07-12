package pcd.dcs.demo;

import pcd.dcs.DistributedCriticalSection;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * A demo application representing a process participating in distributed critical sections.
 * Multiple instances of this application can be started concurrently to demonstrate
 * distributed mutual exclusion.
 * <p>
 * Each process will attempt to enter the critical section multiple times, perform some simulated
 * work (sleeping and appending to a shared log file), and then release the lock.
 * </p>
 */
public class ProcessApp {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String SHARED_LOG_FILE = "dcs_shared.log";

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

                // Acquire the lock
                dcs.enter();

                String enterTime = LocalTime.now().format(TIME_FORMATTER);
                System.out.printf("[%s] [%s] ENTERED critical section.\n", enterTime, processId);

                // Write to the shared log file to verify mutual exclusion
                logToFile(SHARED_LOG_FILE, String.format("[%s] %s ENTER\n", enterTime, processId));

                // Simulate critical section work
                try {
                    Thread.sleep(1500 + random.nextInt(1000));
                } catch (InterruptedException e) {
                    System.err.printf("[%s] [%s] Interrupted during critical section work\n",
                            LocalTime.now().format(TIME_FORMATTER), processId);
                    Thread.currentThread().interrupt();
                }

                String exitTime = LocalTime.now().format(TIME_FORMATTER);
                logToFile(SHARED_LOG_FILE, String.format("[%s] %s EXIT\n", exitTime, processId));
                System.out.printf("[%s] [%s] Exiting critical section.\n", exitTime, processId);

                // Release the lock
                dcs.exit();

                // Wait a bit before requesting access again
                long sleepTime = 500 + random.nextInt(1500);
                Thread.sleep(sleepTime);
            }

            System.out.printf("[%s] [%s] Completed all iterations successfully.\n",
                    LocalTime.now().format(TIME_FORMATTER), processId);

        } catch (Exception e) {
            System.err.printf("[%s] [%s] Error in process application: %s\n",
                    LocalTime.now().format(TIME_FORMATTER), processId, e.getMessage());
            e.printStackTrace();
        }
    }

    private static synchronized void logToFile(String filename, String message) {
        try (FileWriter fw = new FileWriter(filename, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.print(message);
            pw.flush();
        } catch (IOException e) {
            System.err.println("Error writing to shared file: " + e.getMessage());
        }
    }
}
