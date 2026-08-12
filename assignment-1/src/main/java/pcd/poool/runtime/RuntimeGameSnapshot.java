package pcd.poool.runtime;

import java.util.List;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.game.GameSnapshot;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.Hole;

// Immutable state published by a game runner.
public record RuntimeGameSnapshot(
        GameSnapshot game,
        List<Board.BallSnapshot> smallBalls,
        Board.BallSnapshot humanBall,
        Board.BallSnapshot botBall,
        List<Hole> holes,
        V2d botPreviewShot) {

    // Copies the state from the given game model.
    public static RuntimeGameSnapshot from(GameModel game) {
        var board = game.board();
        return new RuntimeGameSnapshot(
                game.snapshot(),
                List.copyOf(board.getBalls()),
                board.getPlayerBall(),
                board.getBotBall(),
                List.copyOf(board.getHoles()),
                game.previewBotShot());
    }
}
