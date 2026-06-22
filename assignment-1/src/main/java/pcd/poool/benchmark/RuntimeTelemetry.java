package pcd.poool.benchmark;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Locale;

/**
 * Runtime and environment metadata captured alongside benchmark results.
 *
 * @param availableProcessors logical CPU count reported by the JVM
 * @param jvmName JVM name
 * @param jvmVersion JVM version
 * @param osName operating system name
 * @param osVersion operating system version
 * @param osArch operating system architecture
 * @param maxMemoryBytes maximum heap memory
 * @param totalMemoryBytes currently allocated heap memory
 * @param freeMemoryBytes free heap memory
 * @param processCpuTimeSupported whether process CPU time was available
 * @param processCpuTimeNanos process CPU time in nanoseconds, or null if unsupported
 */
public record RuntimeTelemetry(
        int availableProcessors,
        String jvmName,
        String jvmVersion,
        String osName,
        String osVersion,
        String osArch,
        long maxMemoryBytes,
        long totalMemoryBytes,
        long freeMemoryBytes,
        boolean processCpuTimeSupported,
        Long processCpuTimeNanos) {

    /**
     * Captures the current runtime and environment metadata.
     *
     * @return runtime telemetry snapshot
     */
    public static RuntimeTelemetry capture() {
        var runtime = Runtime.getRuntime();
        var runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        Long cpuTime = null;
        boolean cpuTimeSupported = false;
        if (osBean instanceof com.sun.management.OperatingSystemMXBean extendedBean) {
            long value = extendedBean.getProcessCpuTime();
            if (value >= 0L) {
                cpuTime = value;
                cpuTimeSupported = true;
            }
        }

        return new RuntimeTelemetry(
                runtime.availableProcessors(),
                runtimeMxBean.getVmName(),
                runtimeMxBean.getVmVersion(),
                osBean.getName(),
                osBean.getVersion(),
                osBean.getArch(),
                runtime.maxMemory(),
                runtime.totalMemory(),
                runtime.freeMemory(),
                cpuTimeSupported,
                cpuTime);
    }

    /**
     * Returns a stable CSV header for telemetry export.
     *
     * @return CSV header row
     */
    public static String csvHeader() {
        return "availableProcessors,jvmName,jvmVersion,osName,osVersion,osArch,maxMemoryBytes,totalMemoryBytes,freeMemoryBytes,processCpuTimeSupported,processCpuTimeNanos";
    }

    /**
     * Returns a stable CSV row for this telemetry snapshot.
     *
     * @return CSV row
     */
    public String toCsvRow() {
        return String.join(",",
                csv(availableProcessors),
                csv(jvmName),
                csv(jvmVersion),
                csv(osName),
                csv(osVersion),
                csv(osArch),
                csv(maxMemoryBytes),
                csv(totalMemoryBytes),
                csv(freeMemoryBytes),
                csv(processCpuTimeSupported),
                csv(processCpuTimeNanos));
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
                "RuntimeTelemetry{availableProcessors=%d, jvmName=%s, jvmVersion=%s, osName=%s, osVersion=%s, osArch=%s, maxMemoryBytes=%d, totalMemoryBytes=%d, freeMemoryBytes=%d, processCpuTimeSupported=%s, processCpuTimeNanos=%s}",
                availableProcessors,
                jvmName,
                jvmVersion,
                osName,
                osVersion,
                osArch,
                maxMemoryBytes,
                totalMemoryBytes,
                freeMemoryBytes,
                processCpuTimeSupported,
                processCpuTimeNanos);
    }
}
