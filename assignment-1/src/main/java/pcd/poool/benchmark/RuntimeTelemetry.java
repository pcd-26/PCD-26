package pcd.poool.benchmark;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime and environment metadata captured alongside benchmark results.
 *
 * @param availableProcessors logical CPU count reported by the JVM
 * @param cpuModel CPU model description when available
 * @param physicalCores physical CPU core count when available
 * @param logicalCpuCount logical CPU/thread count reported by the operating system
 * @param totalPhysicalMemoryBytes installed RAM in bytes when available
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
        String cpuModel,
        Integer physicalCores,
        Integer logicalCpuCount,
        Long totalPhysicalMemoryBytes,
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
        var osProfile = OperatingSystemProfile.detect();

        Long cpuTime = null;
        boolean cpuTimeSupported = false;
        Long totalPhysicalMemoryBytes = osProfile.totalPhysicalMemoryBytes();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean extendedBean) {
            long value = extendedBean.getProcessCpuTime();
            if (value >= 0L) {
                cpuTime = value;
                cpuTimeSupported = true;
            }
            long detectedMemory = extendedBean.getTotalMemorySize();
            if (detectedMemory > 0L) {
                totalPhysicalMemoryBytes = detectedMemory;
            }
        }

        return new RuntimeTelemetry(
                runtime.availableProcessors(),
                osProfile.cpuModel(),
                osProfile.physicalCores(),
                osProfile.logicalCpuCount(),
                totalPhysicalMemoryBytes,
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
        return "availableProcessors,cpuModel,physicalCores,logicalCpuCount,totalPhysicalMemoryBytes,jvmName,jvmVersion,osName,osVersion,osArch,maxMemoryBytes,totalMemoryBytes,freeMemoryBytes,processCpuTimeSupported,processCpuTimeNanos";
    }

    /**
     * Returns a stable CSV row for this telemetry snapshot.
     *
     * @return CSV row
     */
    public String toCsvRow() {
        return String.join(",",
                csv(availableProcessors),
                csv(cpuModel),
                csv(physicalCores),
                csv(logicalCpuCount),
                csv(totalPhysicalMemoryBytes),
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
                "RuntimeTelemetry{availableProcessors=%d, cpuModel=%s, physicalCores=%s, logicalCpuCount=%s, totalPhysicalMemoryBytes=%s, jvmName=%s, jvmVersion=%s, osName=%s, osVersion=%s, osArch=%s, maxMemoryBytes=%d, totalMemoryBytes=%d, freeMemoryBytes=%d, processCpuTimeSupported=%s, processCpuTimeNanos=%s}",
                availableProcessors,
                cpuModel,
                physicalCores,
                logicalCpuCount,
                totalPhysicalMemoryBytes,
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

    private record OperatingSystemProfile(
            String cpuModel,
            Integer physicalCores,
            Integer logicalCpuCount,
            Long totalPhysicalMemoryBytes) {

        private static final Pattern LINUX_MODEL_PATTERN = Pattern.compile("^model name\\s*:\\s*(.+)$", Pattern.MULTILINE);
        private static final Pattern LINUX_THREADS_PATTERN = Pattern.compile("^CPU\\(s\\)\\s*:\\s*(\\d+)\\s*$", Pattern.MULTILINE);
        private static final Pattern LINUX_CORES_PER_SOCKET_PATTERN = Pattern.compile("^Core\\(s\\) per socket\\s*:\\s*(\\d+)\\s*$", Pattern.MULTILINE);
        private static final Pattern LINUX_SOCKETS_PATTERN = Pattern.compile("^Socket\\(s\\)\\s*:\\s*(\\d+)\\s*$", Pattern.MULTILINE);
        private static final Pattern MEMINFO_TOTAL_PATTERN = Pattern.compile("^MemTotal:\\s*(\\d+)\\s+kB$", Pattern.MULTILINE);

        private static OperatingSystemProfile detect() {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (osName.contains("win")) {
                return detectWindows();
            }
            if (osName.contains("linux")) {
                return detectLinux();
            }
            return new OperatingSystemProfile(
                    fallbackCpuModel(),
                    null,
                    Runtime.getRuntime().availableProcessors(),
                    null);
        }

        private static OperatingSystemProfile detectWindows() {
            String cpuModel = firstNonBlank(
                    System.getenv("PROCESSOR_IDENTIFIER"),
                    runCommandAndReadFirstLine("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name)"));
            Integer physicalCores = parseInteger(runCommandAndReadFirstLine(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "((Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum)"));
            Integer logicalCores = parseInteger(runCommandAndReadFirstLine(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "((Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfLogicalProcessors -Sum).Sum)"));
            Long totalMemoryBytes = parseMemoryGigabytesFromWindows();
            return new OperatingSystemProfile(
                    cpuModel == null ? fallbackCpuModel() : cpuModel,
                    physicalCores,
                    logicalCores == null ? Runtime.getRuntime().availableProcessors() : logicalCores,
                    totalMemoryBytes);
        }

        private static OperatingSystemProfile detectLinux() {
            String cpuInfo = readFileIfExists(Path.of("/proc/cpuinfo")).orElse("");
            String memInfo = readFileIfExists(Path.of("/proc/meminfo")).orElse("");
            String lscpu = firstNonBlank(runCommandAndReadAll("lscpu"), "");

            String cpuModel = extractFirstGroup(LINUX_MODEL_PATTERN, cpuInfo);
            Integer logicalCores = parseInteger(extractFirstGroup(LINUX_THREADS_PATTERN, lscpu));
            Integer coresPerSocket = parseInteger(extractFirstGroup(LINUX_CORES_PER_SOCKET_PATTERN, lscpu));
            Integer sockets = parseInteger(extractFirstGroup(LINUX_SOCKETS_PATTERN, lscpu));
            Integer physicalCores = coresPerSocket != null && sockets != null ? coresPerSocket * sockets : null;
            Long totalMemoryBytes = parseMeminfoTotal(memInfo);

            return new OperatingSystemProfile(
                    cpuModel == null ? fallbackCpuModel() : cpuModel,
                    physicalCores,
                    logicalCores == null ? Runtime.getRuntime().availableProcessors() : logicalCores,
                    totalMemoryBytes);
        }

        private static Long parseMeminfoTotal(String memInfo) {
            String kb = extractFirstGroup(MEMINFO_TOTAL_PATTERN, memInfo);
            if (kb == null) {
                return null;
            }
            try {
                return Long.parseLong(kb) * 1024L;
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static Long parseMemoryGigabytesFromWindows() {
            String bytes = runCommandAndReadFirstLine(
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory)");
            if (bytes == null) {
                return null;
            }
            try {
                return Long.parseLong(bytes.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static String fallbackCpuModel() {
            return firstNonBlank(System.getenv("PROCESSOR_IDENTIFIER"), System.getProperty("os.arch"), "unknown-cpu");
        }

        private static String extractFirstGroup(Pattern pattern, String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            Matcher matcher = pattern.matcher(value);
            if (!matcher.find()) {
                return null;
            }
            return matcher.group(1).trim();
        }

        private static Optional<String> readFileIfExists(Path path) {
            if (!Files.exists(path)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                return Optional.empty();
            }
        }

        private static String runCommandAndReadFirstLine(String... command) {
            String output = runCommandAndReadAll(command);
            if (output == null || output.isBlank()) {
                return null;
            }
            return output.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .findFirst()
                    .orElse(null);
        }

        private static String runCommandAndReadAll(String... command) {
            try {
                Process process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();
                byte[] bytes = process.getInputStream().readAllBytes();
                int exit = process.waitFor();
                if (exit != 0) {
                    return null;
                }
                String output = new String(bytes, StandardCharsets.UTF_8).trim();
                return output.isEmpty() ? null : output;
            } catch (Exception ex) {
                return null;
            }
        }

        private static Integer parseInteger(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return null;
        }
    }
}
