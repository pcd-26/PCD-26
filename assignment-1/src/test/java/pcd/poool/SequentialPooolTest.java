package pcd.poool;

import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.game.Player;
import pcd.poool.view.board.ViewModel;

import static org.junit.jupiter.api.Assertions.*;

class SequentialPooolTest {

    @Test
    void humanAimingIsDetectedFromThePreviewOwner() {
        var viewModel = new ViewModel();

        viewModel.setShotPreview(new P2d(0, 0), new P2d(1, 0), 1.0, Player.HUMAN);

        assertTrue(SequentialPoool.isHumanAiming(viewModel));
    }

    @Test
    void botPreviewDoesNotCountAsHumanAiming() {
        var viewModel = new ViewModel();

        viewModel.setShotPreview(new P2d(0, 0), new P2d(1, 0), 1.0, Player.BOT);

        assertFalse(SequentialPoool.isHumanAiming(viewModel));
    }

    @Test
    void humanAimingCanCoexistWithBotPreview() {
        var viewModel = new ViewModel();

        viewModel.setShotPreview(new P2d(0, 0), new P2d(1, 0), 1.0, Player.BOT);
        viewModel.setShotPreview(new P2d(0, 1), new P2d(1, 1), 1.0, Player.HUMAN);

        assertTrue(SequentialPoool.isHumanAiming(viewModel));
        assertNotNull(viewModel.getShotPreview(Player.BOT));
    }
}
