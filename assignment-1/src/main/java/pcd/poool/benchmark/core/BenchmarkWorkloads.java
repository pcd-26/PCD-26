package pcd.poool.benchmark;

import java.util.List;
import pcd.poool.model.physics.common.Boundary;

/**
 * Deterministic workload catalog for physics benchmark comparisons.
 */
public final class BenchmarkWorkloads {

    public static final long DEFAULT_SEED = 42L;
    public static final Boundary DEFAULT_BOARD_BOUNDARY = new Boundary(-1.5, -1.0, 1.5, 1.0);

    private BenchmarkWorkloads() {
    }

    /**
     * Returns the full deterministic workload catalog.
     *
     * @return all benchmark workloads
     */
    public static List<BenchmarkWorkload> catalog() {
        return List.of(
                smallLowCollision(),
                smallHighCollision(),
                mediumLowCollision(),
                mediumHighCollision(),
                largeLowCollision(),
                largeHighCollision());
    }

    /**
     * Small workload with low collision pressure.
     *
     * @return deterministic workload
     */
    public static BenchmarkWorkload smallLowCollision() {
        return workload(BenchmarkWorkload.WorkloadSize.SMALL, BenchmarkWorkload.CollisionProfile.LOW_COLLISION);
    }

    /**
     * Small workload with high collision pressure.
     *
     * @return deterministic workload
     */
    public static BenchmarkWorkload smallHighCollision() {
        return workload(BenchmarkWorkload.WorkloadSize.SMALL, BenchmarkWorkload.CollisionProfile.HIGH_COLLISION);
    }

    /**
     * Medium workload with low collision pressure.
     *
     * @return deterministic workload
     */
    public static BenchmarkWorkload mediumLowCollision() {
        return workload(BenchmarkWorkload.WorkloadSize.MEDIUM, BenchmarkWorkload.CollisionProfile.LOW_COLLISION);
    }

    /**
     * Medium workload with high collision pressure.
     *
     * @return deterministic workload
     */
    public static BenchmarkWorkload mediumHighCollision() {
        return workload(BenchmarkWorkload.WorkloadSize.MEDIUM, BenchmarkWorkload.CollisionProfile.HIGH_COLLISION);
    }

    /**
     * Large workload with low collision pressure.
     *
     * @return deterministic workload
     */
    public static BenchmarkWorkload largeLowCollision() {
        return workload(BenchmarkWorkload.WorkloadSize.LARGE, BenchmarkWorkload.CollisionProfile.LOW_COLLISION);
    }

    /**
     * Large workload with high collision pressure.
     *
     * @return deterministic workload
     */
    public static BenchmarkWorkload largeHighCollision() {
        return workload(BenchmarkWorkload.WorkloadSize.LARGE, BenchmarkWorkload.CollisionProfile.HIGH_COLLISION);
    }

    private static BenchmarkWorkload workload(
            BenchmarkWorkload.WorkloadSize size,
            BenchmarkWorkload.CollisionProfile collisionProfile) {
        return new BenchmarkWorkload(size, collisionProfile, DEFAULT_BOARD_BOUNDARY, DEFAULT_SEED);
    }
}
