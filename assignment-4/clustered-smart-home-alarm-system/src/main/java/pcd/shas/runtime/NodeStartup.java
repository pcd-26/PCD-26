package pcd.shas.runtime;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class NodeStartup {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_CONTROL_UNIT_PORT = 2551;
    public static final int DEFAULT_KEYPAD_PORT = 2552;
    public static final int DEFAULT_SENSOR_PORT = 2553;

    private NodeStartup() {}    // Utility class

    // Launch roles supported by the clustered setup.
    public enum Role {
        CONTROL_UNIT,
        KEYPAD,
        SENSOR
    }

    // Parsed startup data used to create one clustered node.
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

    // Parses the command-line contract used by Main.
    public static NodeArguments parseNodeArguments(String[] args) {
        Objects.requireNonNull(args, "args");
        if (args.length == 0) {
            throw new IllegalArgumentException("missing node role");
        }

        // Split the role from the remaining --flag value pairs.
        Role role = parseRole(args[0]);
        Map<String, String> flags = parseFlags(Arrays.copyOfRange(args, 1, args.length));

        // Defaults keep the local three-node setup easy to launch.
        String host = flags.getOrDefault("--host", DEFAULT_HOST);
        int port = parsePort(flags.get("--port"), role);
        List<String> seedNodes = normalizeSeedNodes(flags.get("--seed-nodes"), host, port);

        // Sensor nodes need identity metadata for the distributed events.
        return switch (role) {
            case CONTROL_UNIT, KEYPAD -> new NodeArguments(role, host, port, seedNodes, null, null, null);
            case SENSOR -> new NodeArguments(
                role,
                host,
                port,
                seedNodes,
                requireFlag(flags, "--sensor-id"),
                parseSensorType(requireFlag(flags, "--sensor-type")),
                parseZone(requireFlag(flags, "--zone"))
            );
        };
    }

    // Builds the Pekko Cluster configuration for a single node.
    public static Config buildClusterConfig(String systemName, String host, int port, List<String> seedNodes) {
        validateSystemIdentity(systemName, host, port);
        Objects.requireNonNull(seedNodes, "seedNodes");

        // Pekko expects full seed-node URIs in the final config.
        List<String> normalizedSeedNodes = normalizeSeedNodes(seedNodes, host, port);
        return ConfigFactory.parseString(buildClusterConfigText(systemName, host, port, normalizedSeedNodes))
            .withFallback(ConfigFactory.load());
    }

    // Converts a host:port pair to a Pekko seed-node URI.
    public static String toSeedNodeUri(String systemName, String hostPort) {
        Objects.requireNonNull(systemName, "systemName");
        Objects.requireNonNull(hostPort, "hostPort");
        String[] parts = splitHostPort(hostPort);
        return toSeedNodeUri(systemName, parts[0], parsePort(parts[1], Role.CONTROL_UNIT));
    }

    // Converts host and port to a Pekko seed-node URI.
    public static String toSeedNodeUri(String systemName, String host, int port) {
        validateSystemIdentity(systemName, host, port);
        return "pekko://%s@%s:%d".formatted(systemName, host, port);
    }

    // Parses the role name accepted by the run scripts.
    private static Role parseRole(String rawRole) {
        Objects.requireNonNull(rawRole, "rawRole");
        return switch (rawRole.toLowerCase(Locale.ROOT)) {
            case "control-unit", "control_unit" -> Role.CONTROL_UNIT;
            case "keypad" -> Role.KEYPAD;
            case "sensor" -> Role.SENSOR;
            default -> throw new IllegalArgumentException("unknown role: " + rawRole);
        };
    }

    // Parses --flag value pairs from the remaining CLI tokens.
    private static Map<String, String> parseFlags(String[] args) {
        java.util.LinkedHashMap<String, String> flags = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            // Every option must be an explicit flag followed by one value.
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + token);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + token);
            }
            String value = args[++i];
            if (value.startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + token);
            }
            flags.put(token, value);
        }
        return flags;
    }

    // Reads a mandatory flag value.
    private static String requireFlag(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required flag: " + key);
        }
        return value;
    }

    // Parses the configured port or falls back to the role default.
    private static int parsePort(String rawPort, Role role) {
        if (rawPort == null || rawPort.isBlank()) {
            return defaultPortFor(role);
        }
        try {
            return Integer.parseInt(rawPort);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + rawPort, e);
        }
    }

    // Parses comma-separated seed nodes in host:port form.
    private static List<String> parseSeedNodes(String rawSeedNodes) {
        if (rawSeedNodes == null || rawSeedNodes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rawSeedNodes.split(","))
            .map(String::trim)
            .filter(seed -> !seed.isEmpty())
            .toList();
    }

    // Resolves raw seed-node text, using the local node as fallback.
    private static List<String> normalizeSeedNodes(String rawSeedNodes, String host, int port) {
        List<String> seedNodes = parseSeedNodes(rawSeedNodes);
        if (!seedNodes.isEmpty()) {
            return seedNodes;
        }
        return List.of(host + ":" + port);
    }

    // Normalizes an already parsed seed-node list.
    private static List<String> normalizeSeedNodes(List<String> seedNodes, String host, int port) {
        if (!seedNodes.isEmpty()) {
            return List.copyOf(seedNodes);
        }
        return List.of(host + ":" + port);
    }

    // Validates the network identity used by the cluster node.
    private static void validateSystemIdentity(String systemName, String host, int port) {
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
    }

    // Returns the default port for the selected role.
    private static int defaultPortFor(Role role) {
        return switch (role) {
            case CONTROL_UNIT -> DEFAULT_CONTROL_UNIT_PORT;
            case KEYPAD -> DEFAULT_KEYPAD_PORT;
            case SENSOR -> DEFAULT_SENSOR_PORT;
        };
    }

    // Splits a seed-node address into host and port.
    private static String[] splitHostPort(String hostPort) {
        String[] parts = hostPort.trim().split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("seed nodes must be in host:port form");
        }
        return new String[] { parts[0].trim(), parts[1].trim() };
    }

    // Builds the small config overlay for this node.
    private static String buildClusterConfigText(String systemName, String host, int port, List<String> seedNodes) {
        String seedNodeList = seedNodes.stream()
            .map(seedNode -> "\"" + toSeedNodeUri(systemName, seedNode) + "\"")
            .collect(Collectors.joining(", "));
        return """
            pekko.remote.artery.canonical.hostname = "%s"
            pekko.remote.artery.canonical.port = %d
            pekko.cluster.seed-nodes = [%s]
            """.formatted(host, port, seedNodeList);
    }

    // Parses a sensor type from CLI text.
    private static SensorType parseSensorType(String rawSensorType) {
        try {
            return SensorType.valueOf(rawSensorType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid sensor type: " + rawSensorType, e);
        }
    }

    // Parses a zone from CLI text.
    private static Zone parseZone(String rawZone) {
        try {
            return Zone.valueOf(rawZone.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid zone: " + rawZone, e);
        }
    }
}
