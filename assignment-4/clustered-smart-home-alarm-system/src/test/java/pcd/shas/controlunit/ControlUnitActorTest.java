package pcd.shas.controlunit;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.siren.SirenActor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the control-unit state machine.
 */
public class ControlUnitActorTest {

    private static final Duration SHORT_DELAY = Duration.ofMillis(50);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(2);
    private static ActorTestKit testKit;

    @BeforeAll
    public static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    public static void teardown() {
        testKit.shutdownTestKit();
    }

    @Test
    public void initialStateIsStartupRecovery() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        assertState(cu, AlarmState.STARTUP_RECOVERY);
    }

    @Test
    public void sensorEventsAreIgnoredInStartupRecovery() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();

        cu.tell(new ControlUnitActor.SensorActivated(
                new SensorInfo("m1", SensorType.MOTION, Zone.LIVING_AREA)
        ));

        assertState(cu, AlarmState.STARTUP_RECOVERY);
    }

    @Test
    public void wrongPinDoesNotLeaveStartupRecovery() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();

        cu.tell(new ControlUnitActor.PinSubmitted("0000"));

        assertState(cu, AlarmState.STARTUP_RECOVERY);
    }

    @Test
    public void correctPinMovesStartupRecoveryToDisarmed() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();

        cu.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(cu, AlarmState.DISARMED);
    }

    @Test
    public void sensorsAreIgnoredWhileDisarmed() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(cu, AlarmState.DISARMED);

        cu.tell(new ControlUnitActor.SensorActivated(
                new SensorInfo("door-1", SensorType.DOOR_WINDOW, Zone.PERIMETER)
        ));

        assertState(cu, AlarmState.DISARMED);
    }

    @Test
    public void correctPinStartsExitDelay() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(cu, AlarmState.DISARMED);

        cu.tell(new ControlUnitActor.RequestFullArming("1234"));

        assertState(cu, AlarmState.EXIT_DELAY);
    }

    @Test
    public void plainPinDoesNotArmWhileDisarmed() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(cu, AlarmState.DISARMED);

        cu.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(cu, AlarmState.DISARMED);
    }

    @Test
    public void exitDelayExpirationMovesToArmed() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.RequestFullArming("1234"));

        awaitState(cu, AlarmState.ARMED);
    }

    @Test
    public void motionActivationWhileArmedStartsEntryDelay() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.RequestFullArming("1234"));
        awaitState(cu, AlarmState.ARMED);

        cu.tell(new ControlUnitActor.SensorActivated(
                new SensorInfo("m1", SensorType.MOTION, Zone.LIVING_AREA)
        ));

        assertState(cu, AlarmState.ENTRY_DELAY);
    }

    @Test
    public void doorWindowActivationWhileArmedStartsEntryDelay() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.RequestFullArming("1234"));
        awaitState(cu, AlarmState.ARMED);

        cu.tell(new ControlUnitActor.SensorActivated(
                new SensorInfo("door-1", SensorType.DOOR_WINDOW, Zone.PERIMETER)
        ));

        assertState(cu, AlarmState.ENTRY_DELAY);
    }

    @Test
    public void correctPinDuringEntryDelayMovesToDisarmed() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.RequestFullArming("1234"));
        awaitState(cu, AlarmState.ARMED);
        cu.tell(new ControlUnitActor.SensorActivated(
                new SensorInfo("door-1", SensorType.DOOR_WINDOW, Zone.PERIMETER)
        ));
        assertState(cu, AlarmState.ENTRY_DELAY);

        cu.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(cu, AlarmState.DISARMED);
    }

    @Test
    public void entryDelayExpirationTriggersAlarm() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        ActorRef<SirenActor.Command> siren = testKit.spawn(SirenActor.create());
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.RequestFullArming("1234"));
        awaitState(cu, AlarmState.ARMED);
        cu.tell(new ControlUnitActor.SensorActivated(
                new SensorInfo("door-1", SensorType.DOOR_WINDOW, Zone.PERIMETER)
        ));
        assertState(cu, AlarmState.ENTRY_DELAY);

        awaitState(cu, AlarmState.ALARM);
        awaitSirenState(siren, true);
    }

    @Test
    public void correctPinDuringAlarmMovesToDisarmed() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        ActorRef<SirenActor.Command> siren = testKit.spawn(SirenActor.create());
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.RequestFullArming("1234"));
        awaitState(cu, AlarmState.ARMED);
        cu.tell(new ControlUnitActor.SensorActivated(
                new SensorInfo("door-1", SensorType.DOOR_WINDOW, Zone.PERIMETER)
        ));
        awaitState(cu, AlarmState.ALARM);
        awaitSirenState(siren, true);

        cu.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(cu, AlarmState.DISARMED);
        awaitSirenState(siren, false);
    }

    @Test
    public void staleExitDelayTimeoutIsIgnoredAfterStateChanges() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit(SHORT_DELAY, SHORT_DELAY);
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.RequestFullArming("1234"));
        awaitState(cu, AlarmState.ARMED);
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(cu, AlarmState.DISARMED);

        cu.tell(new ControlUnitActor.ExitDelayTimeout());

        assertState(cu, AlarmState.DISARMED);
    }

    @Test
    public void staleEntryDelayTimeoutIsIgnoredAfterDisarming() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit(SHORT_DELAY, SHORT_DELAY);
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.RequestFullArming("1234"));
        awaitState(cu, AlarmState.ARMED);
        cu.tell(new ControlUnitActor.SensorActivated(
                new SensorInfo("door-1", SensorType.DOOR_WINDOW, Zone.PERIMETER)
        ));
        assertState(cu, AlarmState.ENTRY_DELAY);
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(cu, AlarmState.DISARMED);

        cu.tell(new ControlUnitActor.EntryDelayTimeout());

        assertState(cu, AlarmState.DISARMED);
    }

    @Test
    public void wrongPinNeverPerformsUnauthorizedTransition() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(cu, AlarmState.DISARMED);

        cu.tell(new ControlUnitActor.PinSubmitted("0000"));
        assertState(cu, AlarmState.DISARMED);

        cu.tell(new ControlUnitActor.RequestFullArming("1234"));
        awaitState(cu, AlarmState.EXIT_DELAY);
        cu.tell(new ControlUnitActor.PinSubmitted("0000"));
        assertState(cu, AlarmState.EXIT_DELAY);
    }

    @Test
    public void partialArmingIgnoresSensorsFromInactiveZones() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = spawnControlUnit();
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.RequestPartialArming("1234", java.util.Set.of(Zone.PERIMETER)));
        awaitState(cu, AlarmState.ARMED);

        cu.tell(new ControlUnitActor.SensorActivated(
                new SensorInfo("m1", SensorType.MOTION, Zone.LIVING_AREA)
        ));

        assertState(cu, AlarmState.ARMED);
    }

    private ActorRef<ControlUnitActor.Command> spawnControlUnit() {
        return spawnControlUnit(Duration.ofMillis(40), Duration.ofMillis(40));
    }

    private ActorRef<ControlUnitActor.Command> spawnControlUnit(Duration exitDelay, Duration entryDelay) {
        return testKit.spawn(ControlUnitActor.create("1234", exitDelay, entryDelay));
    }

    private void awaitState(ActorRef<ControlUnitActor.Command> cu, AlarmState expected) throws Exception {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        AlarmState current = null;
        while (System.nanoTime() < deadline) {
            current = queryState(cu);
            if (current == expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("Expected state " + expected + " but got " + current);
    }

    private void assertState(ActorRef<ControlUnitActor.Command> cu, AlarmState expected) throws Exception {
        assertEquals(expected, queryState(cu));
    }

    private AlarmState queryState(ActorRef<ControlUnitActor.Command> cu) throws Exception {
        return AskPattern.ask(
                cu,
                ControlUnitActor.QueryState::new,
                Duration.ofSeconds(1),
                testKit.system().scheduler()
        ).toCompletableFuture().get(1, TimeUnit.SECONDS).state();
    }

    private void awaitSirenState(ActorRef<SirenActor.Command> siren, boolean expected) throws Exception {
        SirenActor.StateSnapshot snapshot = AskPattern.ask(
                siren,
                SirenActor.QueryState::new,
                Duration.ofSeconds(1),
                testKit.system().scheduler()
        ).toCompletableFuture().get(1, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (snapshot.active() == expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
            snapshot = AskPattern.ask(
                    siren,
                    SirenActor.QueryState::new,
                    Duration.ofSeconds(1),
                    testKit.system().scheduler()
            ).toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
        assertEquals(expected, snapshot.active());
    }
}
