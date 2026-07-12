package pcd.shas.runtime;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Parses node startup arguments and builds the clustered Pekko configuration.
 */
public final class NodeStartup {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_CONTROL_UNIT_PORT = 2551;
    public static final int DEFAULT_KEYPAD_PORT = 2552;
    public static final int DEFAULT_SENSOR_PORT = 2553;

    private NodeStartup() {
        // Utility class.
    }

    /**
     * Launch roles supported by the assignment.
     */
    public enum Role {
        CONTROL_UNIT,
        KEYPAD,
        SENSOR
    }

    /**
     * Parsed command-line arguments for a node process.
     *
     * @param role the selected role
     * @param host the configured network host
     * @param port the configured network port
     * @param seedNodes the configured seed node addresses in host:port form
     * @param sensorId the sensor identifier, if this is a sensor node
     * @param sensorType the sensor type, if this is a sensor node
     * @param zone the sensor zone, if this is a sensor node
     */
    public record NodeArguments(
            Role role,
            String host,
            int port,
            List<String> seedNodes,
            String sensorId,
            SensorType sensorType,
            Zone zone
    ) {
        public NodeArguments {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(seedNodes, "seedNodes");
            seedNodes = List.copyOf(seedNodes);
            if (host.isBlank()) {
                throw new IllegalArgumentException("host cannot be blank");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
            if (role == Role.SENSOR) {
                Objects.requireNonNull(sensorId, "sensorId");
                Objects.requireNonNull(sensorType, "sensorType");
                Objects.requireNonNull(zone, "zone");
            }
        }
    }

    public static NodeArguments parseNodeArguments(String[] args) {
        Objects.requireNonNull(args, "args");
        if (args.length == 0) {
            throw new IllegalArgumentException("missing node role");
        }

        Role role = parseRole(args[0]);
        Map<String, String> flags = parseFlags(Arrays.copyOfRange(args, 1, args.length));

        String host = flags.getOrDefault("--host", DEFAULT_HOST);
        int port = parsePort(flags.get("--port"), role);
        List<String> seedNodes = parseSeedNodes(flags.get("--seed-nodes"));
        if (seedNodes.isEmpty()) {
            seedNodes = List.of(host + ":" + port);
        }

        return switch (role) {
            case CONTROL_UNIT -> new NodeArguments(role, host, port, seedNodes, null, null, null);
            case KEYPAD -> new NodeArguments(role, host, port, seedNodes, null, null, null);
            case SENSOR -> new NodeArguments(
                    role,
                    host,
                    port,
                    seedNodes,
                    require(flags, "--sensor-id"),
                    parseSensorType(require(flags, "--sensor-type")),
                    parseZone(require(flags, "--zone"))
            );
        };
    }

    public static Config buildClusterConfig(String systemName, String host, int port, List<String> seedNodes) {
        Objects.requireNonNull(systemName, "systemName");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(seedNodes, "seedNodes");
        if (systemName.isBlank()) {
            throw new IllegalArgumentException("systemName cannot be blank");
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }

        List<String> resolvedSeedNodes = seedNodes.isEmpty()
                ? List.of(host + ":" + port)
                : List.copyOf(seedNodes);
        String seedNodeList = resolvedSeedNodes.stream()
                .map(seedNode -> "\"" + toSeedNodeUri(systemName, seedNode) + "\"")
                .collect(Collectors.joining(", "));
        String configText = """
                pekko.remote.artery.canonical.hostname = "%s"
                pekko.remote.artery.canonical.port = %d
                pekko.cluster.seed-nodes = [%s]
                """.formatted(host, port, seedNodeList);
        return ConfigFactory.parseString(configText).withFallback(ConfigFactory.load());
    }

    public static String toSeedNodeUri(String systemName, String hostPort) {
        Objects.requireNonNull(systemName, "systemName");
        Objects.requireNonNull(hostPort, "hostPort");
        String[] parts = hostPort.trim().split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("seed nodes must be in host:port form");
        }
        return toSeedNodeUri(systemName, parts[0].trim(), parsePort(parts[1].trim(), Role.CONTROL_UNIT));
    }

    public static String toSeedNodeUri(String systemName, String host, int port) {
        Objects.requireNonNull(systemName, "systemName");
        Objects.requireNonNull(host, "host");
        if (systemName.isBlank()) {
            throw new IllegalArgumentException("systemName cannot be blank");
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return "pekko://%s@%s:%d".formatted(systemName, host, port);
    }

    private static Role parseRole(String rawRole) {
        Objects.requireNonNull(rawRole, "rawRole");
        return switch (rawRole.toLowerCase(Locale.ROOT)) {
            case "control-unit", "control_unit" -> Role.CONTROL_UNIT;
            case "keypad" -> Role.KEYPAD;
            case "sensor" -> Role.SENSOR;
            default -> throw new IllegalArgumentException("unknown role: " + rawRole);
        };
    }

    private static Map<String, String> parseFlags(String[] args) {
        List<String> items = new ArrayList<>(Arrays.asList(args));
        java.util.LinkedHashMap<String, String> flags = new java.util.LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String token = items.get(i);
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + token);
            }
            if (i + 1 >= items.size()) {
                throw new IllegalArgumentException("missing value for " + token);
            }
            String value = items.get(++i);
            if (value.startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + token);
            }
            flags.put(token, value);
        }
        return flags;
    }

    private static String require(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required flag: " + key);
        }
        return value;
    }

    private static int parsePort(String rawPort, Role role) {
        if (rawPort == null || rawPort.isBlank()) {
            return switch (role) {
                case CONTROL_UNIT -> DEFAULT_CONTROL_UNIT_PORT;
                case KEYPAD -> DEFAULT_KEYPAD_PORT;
                case SENSOR -> DEFAULT_SENSOR_PORT;
            };
        }
        try {
            return Integer.parseInt(rawPort);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + rawPort, e);
        }
    }

    private static List<String> parseSeedNodes(String rawSeedNodes) {
        if (rawSeedNodes == null || rawSeedNodes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawSeedNodes.split(","))
                .map(String::trim)
                .filter(seed -> !seed.isEmpty())
                .toList();
    }

    private static SensorType parseSensorType(String rawSensorType) {
        try {
            return SensorType.valueOf(rawSensorType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid sensor type: " + rawSensorType, e);
        }
    }

    private static Zone parseZone(String rawZone) {
        try {
            return Zone.valueOf(rawZone.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid zone: " + rawZone, e);
        }
    }
}
