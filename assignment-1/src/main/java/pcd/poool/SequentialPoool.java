package pcd.poool;

import pcd.poool.model.game.GameStatus;
import pcd.poool.model.game.SequentialGame;
import pcd.poool.model.physics.PhysicsDefaults;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.view.board.View;
import pcd.poool.view.board.ViewModel;

public class SequentialPoool {

    private static final int VIEW_WIDTH = 1200;
    private static final int VIEW_HEIGHT = 800;
    private static final long BOT_THINK_TIME_MILLIS = 600;
    private static final long FRAME_SLEEP_MILLIS = 4;

    public static void main(String[] args) {
        var game = new SequentialGame(new StandardGameBoardConf());
        var viewModel = new ViewModel();
        var view = new View(viewModel, VIEW_WIDTH, VIEW_HEIGHT, game::shootHuman);

        long startTime = System.currentTimeMillis();
        long lastUpdateTime = startTime;
        long waitingForBotSince = 0;
        int renderedFrames = 0;

        while (!game.snapshot().isFinished()) {
            long now = System.currentTimeMillis();
            long elapsed = Math.max(PhysicsDefaults.FIXED_STEP_MILLIS, now - lastUpdateTime);
            lastUpdateTime = now;

            game.step(elapsed);
            var snapshot = game.snapshot();
            if (snapshot.status() == GameStatus.WAITING_FOR_BOT_SHOT) {
                if (waitingForBotSince == 0) {
                    waitingForBotSince = now;
                } else if (now - waitingForBotSince >= BOT_THINK_TIME_MILLIS) {
                    game.shootBot();
                    waitingForBotSince = 0;
                }
            } else {
                waitingForBotSince = 0;
            }

            renderedFrames++;
            int framePerSec = framePerSec(renderedFrames, startTime, now);
            viewModel.update(game.board(), game.snapshot(), framePerSec);
            view.render();
            sleepFrame();
        }

        viewModel.update(game.board(), game.snapshot(), 0);
        view.render();
    }

    private static int framePerSec(int renderedFrames, long startTime, long now) {
        long elapsed = now - startTime;
        if (elapsed <= 0) {
            return 0;
        }
        return (int) (renderedFrames * 1000 / elapsed);
    }

    private static void sleepFrame() {
        try {
            Thread.sleep(FRAME_SLEEP_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
