package pcd.poool.benchmark;

import java.util.Locale;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.config.StandardGameBoardConf;

/**
 * Standalone benchmark for the integrated sequential game loop.
 */
public class SequentialGameBenchmark {

    /**
     * Utility class; not meant to be instantiated.
     */
    private SequentialGameBenchmark() {
    }

    /**
     * Runs the benchmark.
     *
     * @param args optional first argument: number of game steps
     */
    public static void main(String[] args) {
        var config = BenchmarkConfig.sequentialGameDefaults();
        if (args.length > 0) {
            config = config.withSteps(Integer.parseInt(args[0]));
        }
        var game = new GameModel(new StandardGameBoardConf());
        game.shootHuman(new V2d(0, 1.4));

        for (int i = 0; i < config.steps() && !game.snapshot().isFinished(); i++) {
            game.step(PhysicsDefaults.FIXED_STEP_MILLIS);
            if (game.canBotShoot()) {
                game.shootBot();
            }
        }

        var snapshot = game.snapshot();
        System.out.printf(Locale.US,
                "config=%s steps=%d balls=%d status=%s elapsed_game_ms=%d avg_step_ms=%.6f%n",
                config.toKeyValueString(),
                snapshot.simulatedSteps(),
                game.board().getBalls().size(),
                snapshot.status(),
                snapshot.elapsedMillis(),
                snapshot.averageStepMillis());
    }
}
