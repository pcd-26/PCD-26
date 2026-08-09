package pcd.poool.benchmark.core;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Minimal runtime metadata captured alongside benchmark results.
 *
 * <p>The benchmark reports only the environment details that are useful for
 * comparing snapshots across machines: JVM, operating system, and the maximum
 * parallelism the JVM can see.
 *
 * @param maxThreads maximum worker count reported by the JVM
 * @param jvmName JVM name
 * @param jvmVersion JVM version
 * @param osName operating system name
 * @param osVersion operating system version
 * @param osArch operating system architecture
 */
public record RuntimeTelemetry(
        int maxThreads,
        String jvmName,
        String jvmVersion,
        String osName,
        String osVersion,
        String osArch) {

    /**
     * Captures the current runtime metadata snapshot.
     *
     * @return runtime telemetry snapshot
     */
    public static RuntimeTelemetry capture() {
        var runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        return new RuntimeTelemetry(
                Runtime.getRuntime().availableProcessors(),
                runtimeMxBean.getVmName(),
                runtimeMxBean.getVmVersion(),
                osBean.getName(),
                osBean.getVersion(),
                osBean.getArch());
    }

    /**
     * Returns a stable CSV header for telemetry export.
     *
     * @return CSV header row
     */
    public static String csvHeader() {
        return "maxThreads,jvmName,jvmVersion,osName,osVersion,osArch";
    }

    /**
     * Returns a stable CSV row for this telemetry snapshot.
     *
     * @return CSV row
     */
    public String toCsvRow() {
        return String.join(",",
                csv(maxThreads),
                csv(jvmName),
                csv(jvmVersion),
                csv(osName),
                csv(osVersion),
                csv(osArch));
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String stringValue = String.valueOf(value);
        if (stringValue.contains(",") || stringValue.contains("\"") || stringValue.contains("\n") || stringValue.contains("\r")) {
            return "\"" + stringValue.replace("\"", "\"\"") + "\"";
        }
        return stringValue;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "RuntimeTelemetry{maxThreads=%d, jvmName=%s, jvmVersion=%s, osName=%s, osVersion=%s, osArch=%s}",
                maxThreads,
                jvmName,
                jvmVersion,
                osName,
                osVersion,
                osArch);
    }
}
