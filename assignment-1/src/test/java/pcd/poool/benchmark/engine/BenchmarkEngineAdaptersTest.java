package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pcd.poool.model.physics.common.Board;

class BenchmarkEngineAdaptersTest {

    @Test
    void sequentialAdapterExposesNoWorkerCountAndUsesTheBaselineName() {
        var adapter = BenchmarkEngineAdapters.sequential();

        assertEquals("sequential", adapter.engineName());
        assertTrue(adapter.workerCount().isEmpty());
    }

    @Test
    void concurrentAdaptersExposeConfiguredWorkerCounts() {
        var threaded = BenchmarkEngineAdapters.threaded(4);
        var taskBased = BenchmarkEngineAdapters.taskBased(6);

        assertEquals("threads", threaded.engineName());
        assertEquals(4, threaded.workerCount().orElseThrow());
        assertEquals("executor", taskBased.engineName());
        assertEquals(6, taskBased.workerCount().orElseThrow());
    }

    @Test
    void allAdaptersStartFromTheSameInitialState() throws Exception {
        var workload = BenchmarkWorkloads.mediumLowCollision();
        var adapters = List.of(
                BenchmarkEngineAdapters.sequential(),
                BenchmarkEngineAdapters.threaded(2),
                BenchmarkEngineAdapters.taskBased(2));

        BoardState baseline = null;
        for (var adapter : adapters) {
            var current = snapshot(adapter, workload);
            assertNotNull(runOnce(adapter, workload));
            if (baseline == null) {
                baseline = current;
            } else {
                assertEquals(baseline, current);
            }
        }
    }

    private static long runOnce(BenchmarkEngineAdapter adapter, BenchmarkWorkload workload) throws Exception {
        try (var session = adapter.open()) {
            var board = new Board(session.stepper());
            board.init(workload.createBoardConf());
            return session.execute(board, workload.ticks()).checksum();
        }
    }

    private static BoardState snapshot(BenchmarkEngineAdapter adapter, BenchmarkWorkload workload) throws Exception {
        try (var session = adapter.open()) {
            var board = new Board(session.stepper());
            board.init(workload.createBoardConf());
            return new BoardState(board.getBounds(), board.getPlayerBall(), board.getBotBall(), board.getHoles(), board.getBalls());
        }
    }

    private record BoardState(
            pcd.poool.model.physics.common.Boundary bounds,
            pcd.poool.model.physics.common.Board.BallSnapshot playerBall,
            pcd.poool.model.physics.common.Board.BallSnapshot botBall,
            List<pcd.poool.model.physics.common.Hole> holes,
            List<pcd.poool.model.physics.common.Board.BallSnapshot> smallBalls) {
    }
}
