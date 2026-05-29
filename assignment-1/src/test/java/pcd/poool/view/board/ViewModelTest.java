package pcd.poool.view.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.game.Player;

class ViewModelTest {

    @Test
    void shotPreviewStoresOwningPlayerForRendering() {
        var model = new ViewModel();

        model.setShotPreview(new P2d(0, 0), new P2d(1, 0), 0.5, Player.BOT);

        assertEquals(Player.BOT, model.getShotPreview().player());
    }
}
