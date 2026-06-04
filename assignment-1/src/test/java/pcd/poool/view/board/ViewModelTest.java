package pcd.poool.view.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.game.GameSnapshot;
import pcd.poool.model.game.GameStatus;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.Board;
import pcd.poool.model.physics.Hole;

class ViewModelTest {

    @Test
    void shotPreviewStoresOwningPlayerForRendering() {
        var model = new ViewModel();

        model.setShotPreview(new P2d(0, 0), new P2d(1, 0), 0.5, Player.BOT);

        assertEquals(Player.BOT, model.getShotPreview().player());
    }

    @Test
    void updateCopiesImmutableThreadedSnapshotsForRendering() {
        var model = new ViewModel();
        var game = new GameSnapshot(1, 2, GameStatus.RUNNING, null, null, true, false, 16, 1, 0.2);

        model.update(
                List.of(new Board.BallSnapshot(new P2d(0.25, 0.5), 0.03)),
                new Board.BallSnapshot(new P2d(-0.5, 0), 0.05),
                null,
                List.of(new Hole(new P2d(1, 1), 0.12)),
                game,
                60);

        assertEquals(1, model.getBalls().size());
        assertEquals(new P2d(-0.5, 0), model.getPlayerBall().pos());
        assertEquals(null, model.getBotBall());
        assertEquals(1, model.getHoles().size());
        assertEquals(game, model.getGame());
        assertEquals(60, model.getFramePerSec());
    }
}
