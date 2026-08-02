package pcd.poool.benchmark.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.sequential.PhysicsEngine;
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;

class BenchmarkWorkloadsTest {

    @Test
    void catalogContainsSmallMediumLargeLowAndHighCollisionVariants() {
        var catalog = BenchmarkWorkloads.catalog();

        assertEquals(6, catalog.size());
        assertEquals(
                List.of(
                        BenchmarkWorkload.WorkloadSize.SMALL,
                        BenchmarkWorkload.WorkloadSize.SMALL,
                        BenchmarkWorkload.WorkloadSize.MEDIUM,
                        BenchmarkWorkload.WorkloadSize.MEDIUM,
                        BenchmarkWorkload.WorkloadSize.LARGE,
                        BenchmarkWorkload.WorkloadSize.LARGE),
                catalog.stream().map(BenchmarkWorkload::size).toList());
        assertEquals(
                List.of(
                        BenchmarkWorkload.CollisionProfile.LOW_COLLISION,
                        BenchmarkWorkload.CollisionProfile.HIGH_COLLISION,
                        BenchmarkWorkload.CollisionProfile.LOW_COLLISION,
                        BenchmarkWorkload.CollisionProfile.HIGH_COLLISION,
                        BenchmarkWorkload.CollisionProfile.LOW_COLLISION,
                        BenchmarkWorkload.CollisionProfile.HIGH_COLLISION),
                catalog.stream().map(BenchmarkWorkload::collisionProfile).toList());
    }

    @Test
    void repeatedRunsWithTheSameSeedProduceTheSameInitialState() {
        var workload = BenchmarkWorkloads.mediumHighCollision();

        var firstState = snapshot(workload);
        var secondState = snapshot(workload);

        assertEquals(firstState, secondState);
        assertEquals(BenchmarkWorkloads.DEFAULT_SEED, workload.seed());
        assertEquals(workload.size().balls(), workload.balls());
        assertEquals(workload.size().ticks(), workload.ticks());
    }

    @Test
    void allEnginesCanBeInitializedFromTheSameWorkloadDefinition() throws Exception {
        var workload = BenchmarkWorkloads.largeLowCollision();

        var sequentialBoard = initialize(workload, new PhysicsEngine());
        try (var threadedEngine = new ThreadedPhysicsEngine(2);
                var taskBasedEngine = new TaskBasedPhysicsEngine(2)) {
            var threadedBoard = initialize(workload, threadedEngine);
            var taskBasedBoard = initialize(workload, taskBasedEngine);

            assertEquals(snapshot(sequentialBoard), snapshot(threadedBoard));
            assertEquals(snapshot(sequentialBoard), snapshot(taskBasedBoard));
        }
    }

    @Test
    void lowAndHighCollisionProfilesGenerateDifferentInitialStates() {
        var lowCollision = snapshot(BenchmarkWorkloads.smallLowCollision());
        var highCollision = snapshot(BenchmarkWorkloads.smallHighCollision());

        assertNotEquals(lowCollision, highCollision);
    }

    private static Board initialize(BenchmarkWorkload workload, pcd.poool.model.physics.common.PhysicsStepper stepper) {
        var board = new Board(stepper);
        board.init(workload.createBoardConf());
        return board;
    }

    private static BoardState snapshot(BenchmarkWorkload workload) {
        return snapshot(initialize(workload, new PhysicsEngine()));
    }

    private static BoardState snapshot(Board board) {
        return new BoardState(
                board.getBounds(),
                board.getPlayerBall(),
                board.getBotBall(),
                board.getHoles(),
                board.getBalls());
    }

    private record BoardState(
            pcd.poool.model.physics.common.Boundary bounds,
            pcd.poool.model.physics.common.Board.BallSnapshot playerBall,
            pcd.poool.model.physics.common.Board.BallSnapshot botBall,
            List<pcd.poool.model.physics.common.Hole> holes,
            List<pcd.poool.model.physics.common.Board.BallSnapshot> smallBalls) {

        private BoardState {
            assertNotNull(bounds);
            assertNotNull(playerBall);
            assertNotNull(botBall);
            assertNotNull(holes);
            assertNotNull(smallBalls);
        }
    }
}
