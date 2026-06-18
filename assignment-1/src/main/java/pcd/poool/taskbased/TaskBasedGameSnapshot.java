package pcd.poool.taskbased;

import java.util.List;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.game.GameSnapshot;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.Hole;

/**
 * Immutable state published by the task-based runner.
 *
 * @param game logical game snapshot
 * @param smallBalls immutable small-ball snapshots
 * @param humanBall immutable human cue-ball snapshot, or {@code null}
 * @param botBall immutable bot cue-ball snapshot, or {@code null}
 * @param holes immutable hole layout
 * @param botPreviewShot bot shot preview vector, or zero when the bot cannot shoot
 */
public record TaskBasedGameSnapshot(
        GameSnapshot game,
        List<Board.BallSnapshot> smallBalls,
        Board.BallSnapshot humanBall,
        Board.BallSnapshot botBall,
        List<Hole> holes,
        V2d botPreviewShot) {

    static TaskBasedGameSnapshot from(GameModel game) {
        var board = game.board();
        return new TaskBasedGameSnapshot(
                game.snapshot(),
                List.copyOf(board.getBalls()),
                board.getPlayerBall(),
                board.getBotBall(),
                List.copyOf(board.getHoles()),
                game.previewBotShot());
    }
}
