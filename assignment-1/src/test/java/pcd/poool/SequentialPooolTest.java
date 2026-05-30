package pcd.poool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.game.Player;
import pcd.poool.view.board.ViewModel;

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
    void aimingOwnerAllowsOnlyOnePlayerAtATime() {
        var aimingOwner = new AtomicReference<Player>();

        assertTrue(SequentialPoool.tryStartAiming(aimingOwner, Player.BOT));
        assertFalse(SequentialPoool.tryStartAiming(aimingOwner, Player.HUMAN));

        SequentialPoool.stopAiming(aimingOwner, Player.BOT);

        assertTrue(SequentialPoool.tryStartAiming(aimingOwner, Player.HUMAN));
    }
}
