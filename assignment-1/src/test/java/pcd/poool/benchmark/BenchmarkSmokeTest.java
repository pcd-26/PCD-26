package pcd.poool.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BenchmarkSmokeTest {

    @Test
    @Timeout(5)
    void sequentialPhysicsBenchmarkRunsForOneStep() {
        PhysicsBenchmark.main(new String[] {"1"});
    }

    @Test
    @Timeout(5)
    void sequentialGameBenchmarkRunsForOneStep() {
        SequentialGameBenchmark.main(new String[] {"1"});
    }

    @Test
    @Timeout(5)
    void threadedPhysicsBenchmarkRunsForOneStep() {
        ThreadedPhysicsBenchmark.main(new String[] {"1", "2"});
    }

    @Test
    @Timeout(5)
    void threadedPhysicsProfilingBenchmarkRunsForOneStep() {
        ThreadedPhysicsProfilingBenchmark.main(new String[] {"1", "2", "16"});
    }
}
