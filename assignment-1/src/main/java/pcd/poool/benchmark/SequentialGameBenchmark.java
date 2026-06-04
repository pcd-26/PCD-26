package pcd.poool.benchmark;

import java.util.Locale;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.SequentialGame;
import pcd.poool.model.physics.PhysicsDefaults;
import pcd.poool.model.physics.config.StandardGameBoardConf;

/**
 * Standalone benchmark for the integrated sequential game loop.
 */
public class SequentialGameBenchmark {

    private static final int DEFAULT_STEPS = 600;
    private static final String OUTPUT_FORMAT =
            "steps=%d balls=%d status=%s elapsed_game_ms=%d avg_step_ms=%.6f%n";

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
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_STEPS;
        var game = new SequentialGame(new StandardGameBoardConf());
        game.shootHuman(new V2d(0, 1.4));

        for (int i = 0; i < steps && !game.snapshot().isFinished(); i++) {
            game.step(PhysicsDefaults.FIXED_STEP_MILLIS);
            if (game.canBotShoot()) {
                game.shootBot();
            }
        }

        var snapshot = game.snapshot();
        System.out.printf(Locale.US, OUTPUT_FORMAT,
                snapshot.simulatedSteps(),
                game.board().getBalls().size(),
                snapshot.status(),
                snapshot.elapsedMillis(),
                snapshot.averageStepMillis());
    }
}
