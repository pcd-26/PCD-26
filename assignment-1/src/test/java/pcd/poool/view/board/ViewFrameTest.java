package pcd.poool.view.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.game.Player;

class ViewFrameTest {

    private static final double EPSILON = 1e-9;

    @Test
    void diagonalArrowCombinationKeepsConfiguredImpulseMagnitude() {
        var shot = ViewFrame.shotImpulseFor(true, false, false, true);

        assertEquals(ViewFrame.SHOT_IMPULSE, shot.abs(), EPSILON);
        assertEquals(shot.x(), shot.y(), EPSILON);
    }

    @Test
    void keyboardDirectionMaskCreatesSameDiagonalImpulse() {
        var shot = ViewFrame.keyboardShotImpulse(1 | 8);

        assertEquals(ViewFrame.SHOT_IMPULSE, shot.abs(), EPSILON);
        assertEquals(shot.x(), shot.y(), EPSILON);
    }

    @Test
    void oppositeDirectionsCancelEachOther() {
        var shot = ViewFrame.shotImpulseFor(true, true, false, false);

        assertEquals(0.0, shot.abs(), EPSILON);
    }

    @Test
    void mouseTargetCreatesImpulseTowardClickedPoint() {
        var shot = ViewFrame.shotImpulseToward(new P2d(0, 0), new P2d(2, 0));

        assertEquals(ViewFrame.SHOT_IMPULSE, shot.x(), EPSILON);
        assertEquals(0.0, shot.y(), EPSILON);
    }

    @Test
    void mouseDragDistanceControlsShotIntensity() {
        var shortShot = ViewFrame.mouseShotImpulse(new P2d(0, 0), new P2d(0.1, 0));
        var longShot = ViewFrame.mouseShotImpulse(new P2d(0, 0), new P2d(0.6, 0));

        assertEquals(0.1 / ViewFrame.MAX_MOUSE_DRAG_DISTANCE * ViewFrame.MAX_MOUSE_SHOT_IMPULSE,
                shortShot.abs(),
                EPSILON);
        assertEquals(0.6 / ViewFrame.MAX_MOUSE_DRAG_DISTANCE * ViewFrame.MAX_MOUSE_SHOT_IMPULSE,
                longShot.abs(),
                EPSILON);
    }

    @Test
    void mouseShotIntensityIsCapped() {
        var shot = ViewFrame.mouseShotImpulse(new P2d(0, 0), new P2d(10, 0));

        assertEquals(ViewFrame.MAX_MOUSE_SHOT_IMPULSE, shot.abs(), EPSILON);
    }

    @Test
    void botPreviewIsDetectedIndependently() {
        var model = new ViewModel();
        model.setShotPreview(new P2d(0, 0), new P2d(1, 0), 1.0, Player.BOT);

        assertTrue(ViewFrame.isBotAiming(model));
    }
}
