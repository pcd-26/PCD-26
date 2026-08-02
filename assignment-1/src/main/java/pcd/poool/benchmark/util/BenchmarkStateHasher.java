package pcd.poool.benchmark.util;

import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;

/**
 * Computes deterministic board-state hashes for benchmark comparisons.
 */
public final class BenchmarkStateHasher {

    private BenchmarkStateHasher() {
    }

    public static long checksum(Board board) {
        synchronized (board) {
            long hash = 0x9E3779B97F4A7C15L;
            hash = mix(hash, board.getPocketedSmallBalls());
            hash = mix(hash, board.isPlayerBallPocketed() ? 1L : 0L);
            hash = mix(hash, board.isBotBallPocketed() ? 1L : 0L);

            var playerBall = board.getPlayerBallEntity();
            if (playerBall != null) {
                hash = hashBall(hash, playerBall);
            }

            var botBall = board.getBotBallEntity();
            if (botBall != null) {
                hash = hashBall(hash, botBall);
            }

            for (var ball : board.getSmallBallEntities()) {
                hash = hashBall(hash, ball);
            }
            return avalanche(hash);
        }
    }

    private static long hashBall(long hash, Ball ball) {
        hash = mix(hash, Double.doubleToLongBits(ball.getPos().x()));
        hash = mix(hash, Double.doubleToLongBits(ball.getPos().y()));
        hash = mix(hash, Double.doubleToLongBits(ball.getVel().x()));
        hash = mix(hash, Double.doubleToLongBits(ball.getVel().y()));
        hash = mix(hash, Double.doubleToLongBits(ball.getRadius()));
        hash = mix(hash, Double.doubleToLongBits(ball.getMass()));
        return hash;
    }

    private static long mix(long hash, long value) {
        long z = hash ^ value;
        z ^= z >>> 33;
        z *= 0xff51afd7ed558ccdL;
        z ^= z >>> 33;
        z *= 0xc4ceb9fe1a85ec53L;
        z ^= z >>> 33;
        return z;
    }

    private static long avalanche(long value) {
        return mix(value, value << 1);
    }
}
