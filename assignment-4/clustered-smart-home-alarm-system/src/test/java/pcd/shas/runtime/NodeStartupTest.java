package pcd.shas.runtime;

import com.typesafe.config.Config;
import org.junit.jupiter.api.Test;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the node startup argument parser and cluster configuration builder.
 */
public class NodeStartupTest {

    @Test
    public void parsesControlUnitArguments() {
        NodeStartup.NodeArguments arguments = NodeStartup.parseNodeArguments(new String[] {
                "control-unit",
                "--host", "10.0.0.5",
                "--port", "2601",
                "--seed-nodes", "10.0.0.5:2601,10.0.0.6:2601,10.0.0.7:2601"
        });

        assertEquals(NodeStartup.Role.CONTROL_UNIT, arguments.role());
        assertEquals("10.0.0.5", arguments.host());
        assertEquals(2601, arguments.port());
        assertEquals(List.of("10.0.0.5:2601", "10.0.0.6:2601", "10.0.0.7:2601"), arguments.seedNodes());
    }

    @Test
    public void parsesSensorArguments() {
        NodeStartup.NodeArguments arguments = NodeStartup.parseNodeArguments(new String[] {
                "sensor",
                "--host", "127.0.0.1",
                "--port", "2603",
                "--sensor-id", "front_door",
                "--sensor-type", "DOOR_WINDOW",
                "--zone", "PERIMETER",
                "--seed-nodes", "127.0.0.1:2601,127.0.0.1:2602,127.0.0.1:2603"
        });

        assertEquals(NodeStartup.Role.SENSOR, arguments.role());
        assertEquals("front_door", arguments.sensorId());
        assertEquals(SensorType.DOOR_WINDOW, arguments.sensorType());
        assertEquals(Zone.PERIMETER, arguments.zone());
    }

    @Test
    public void defaultsHostPortAndSeedNodesWhenFlagsAreMissing() {
        NodeStartup.NodeArguments arguments = NodeStartup.parseNodeArguments(new String[] {
                "keypad"
        });

        assertEquals(NodeStartup.Role.KEYPAD, arguments.role());
        assertEquals("127.0.0.1", arguments.host());
        assertEquals(2552, arguments.port());
        assertEquals(List.of("127.0.0.1:2552"), arguments.seedNodes());
    }

    @Test
    public void buildsClusterConfigWithSeedNodes() {
        Config config = NodeStartup.buildClusterConfig(
                "shas-cluster",
                "127.0.0.1",
                2601,
                List.of("127.0.0.1:2601", "127.0.0.1:2602", "127.0.0.1:2603"),
                NodeStartup.Role.CONTROL_UNIT
        );

        assertEquals("127.0.0.1", config.getString("pekko.remote.artery.canonical.hostname"));
        assertEquals(2601, config.getInt("pekko.remote.artery.canonical.port"));
        assertEquals(
                List.of(
                        "pekko://shas-cluster@127.0.0.1:2601",
                        "pekko://shas-cluster@127.0.0.1:2602",
                        "pekko://shas-cluster@127.0.0.1:2603"
                ),
                config.getStringList("pekko.cluster.seed-nodes")
        );
        assertEquals(List.of("control-unit"), config.getStringList("pekko.cluster.roles"));
    }

    @Test
    public void convertsHostPortSeedNodesToPekkoUris() {
        assertEquals(
                "pekko://shas-cluster@127.0.0.1:2601",
                NodeStartup.toSeedNodeUri("shas-cluster", "127.0.0.1:2601")
        );
    }
}
