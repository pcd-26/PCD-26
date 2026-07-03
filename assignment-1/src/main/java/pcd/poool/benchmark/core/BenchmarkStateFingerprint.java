package pcd.poool.benchmark;

/**
 * Final-state fingerprint used by the benchmark correctness guard.
 *
 * <p>The fingerprint prefers exact checksums, but also carries a compact set
 * of invariants so implementations can be compared even when floating-point
 * trajectories diverge slightly while keeping the simulation semantically
 * valid.
 *
 * @param checksum deterministic checksum of the final board state
 * @param invariantHash digest of the observable invariants
 * @param remainingBalls number of remaining small balls on the board
 * @param pocketedSmallBalls total pocketed small balls
 * @param playerBallPocketed whether the human cue ball is pocketed
 * @param botBallPocketed whether the bot cue ball is pocketed
 * @param hasNaN whether the snapshot contains any NaN coordinate
 * @param withinBounds whether all observed balls are within the board bounds
 */
public record BenchmarkStateFingerprint(
        long checksum,
        long invariantHash,
        int remainingBalls,
        int pocketedSmallBalls,
        boolean playerBallPocketed,
        boolean botBallPocketed,
        boolean hasNaN,
        boolean withinBounds) {

    /**
     * Creates a checksum-only fingerprint for workloads that do not expose
     * the final board snapshot.
     *
     * @param checksum final checksum
     * @return checksum-only fingerprint
     */
    public static BenchmarkStateFingerprint unknown(long checksum) {
        return new BenchmarkStateFingerprint(checksum, 0L, -1, -1, false, false, true, false);
    }

    /**
     * Checks whether the observed state is valid enough for correctness
     * comparison.
     *
     * @return whether the invariant set reports no corruption
     */
    public boolean isValid() {
        return !hasNaN && withinBounds;
    }

    /**
     * Compares two fingerprints, preferring the exact checksum and otherwise
     * falling back to the invariant digest.
     *
     * @param other another fingerprint
     * @return whether the two states are considered equivalent
     */
    public boolean equivalentTo(BenchmarkStateFingerprint other) {
        if (other == null) {
            return false;
        }
        if (checksum == other.checksum) {
            return true;
        }
        return invariantHash == other.invariantHash
                && remainingBalls == other.remainingBalls
                && pocketedSmallBalls == other.pocketedSmallBalls
                && playerBallPocketed == other.playerBallPocketed
                && botBallPocketed == other.botBallPocketed
                && hasNaN == other.hasNaN
                && withinBounds == other.withinBounds;
    }
}
