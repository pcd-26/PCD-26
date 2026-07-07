package pcd.poool.view.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.game.Player;

class ViewFrameTest {

    private static final double EPSILON = 1e-9;

    /**
     * Verifies that combination of diagonal keyboard arrows yields the correct shot impulse
     * magnitude and equal x/y components.
     */
    @Test
    void diagonalArrowCombinationKeepsConfiguredImpulseMagnitude() {
        var shot = ViewFrame.shotImpulseFor(true, false, false, true);

        assertEquals(ViewFrame.SHOT_IMPULSE, shot.abs(), EPSILON);
        assertEquals(shot.x(), shot.y(), EPSILON);
    }

    /**
     * Verifies that a bitwise keyboard direction mask creates the expected diagonal impulse.
     */
    @Test
    void keyboardDirectionMaskCreatesSameDiagonalImpulse() {
        var shot = ViewFrame.keyboardShotImpulse(1 | 8);

        assertEquals(ViewFrame.SHOT_IMPULSE, shot.abs(), EPSILON);
        assertEquals(shot.x(), shot.y(), EPSILON);
    }

    /**
     * Verifies that opposite arrow key directions cancel each other's impulses, yielding zero force.
     */
    @Test
    void oppositeDirectionsCancelEachOther() {
        var shot = ViewFrame.shotImpulseFor(true, true, false, false);

        assertEquals(0.0, shot.abs(), EPSILON);
    }

    /**
     * Verifies that clicking/targeting with the mouse creates an impulse vector pointing directly
     * towards the clicked coordinates.
     */
    @Test
    void mouseTargetCreatesImpulseTowardClickedPoint() {
        var shot = ViewFrame.shotImpulseToward(new P2d(0, 0), new P2d(2, 0));

        assertEquals(ViewFrame.SHOT_IMPULSE, shot.x(), EPSILON);
        assertEquals(0.0, shot.y(), EPSILON);
    }

    /**
     * Verifies that mouse drag distance proportionally controls shot impulse intensity.
     */
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

    /**
     * Verifies that the mouse shot impulse magnitude is capped at MAX_MOUSE_SHOT_IMPULSE
     * regardless of extreme drag distances.
     */
    @Test
    void mouseShotIntensityIsCapped() {
        var shot = ViewFrame.mouseShotImpulse(new P2d(0, 0), new P2d(10, 0));

        assertEquals(ViewFrame.MAX_MOUSE_SHOT_IMPULSE, shot.abs(), EPSILON);
    }

    /**
     * Verifies that the bot's shot preview state is correctly recognized as bot aiming.
     */
    @Test
    void botPreviewIsDetectedIndependently() {
        var model = new ViewModel();
        model.setShotPreview(new P2d(0, 0), new P2d(1, 0), 1.0, Player.BOT);

        assertTrue(ViewFrame.isBotAiming(model));
    }

    /**
     * Verifies that the shot preview line width scales between 2.0f and 5.0f
     * as the launch force intensity goes from 0% to 100%.
     */
    @Test
    void shotPreviewWidthScalesProportionatelyWithLaunchForce() {
        // At 0% intensity
        float minWidth = ViewFrame.calculateShotPreviewWidth(0.0);
        assertEquals(2.0f, minWidth, (float) EPSILON);

        // At 100% intensity (MAX_MOUSE_SHOT_IMPULSE)
        float maxWidth = ViewFrame.calculateShotPreviewWidth(ViewFrame.MAX_MOUSE_SHOT_IMPULSE);
        assertEquals(5.0f, maxWidth, (float) EPSILON);

        // At 50% intensity
        float midWidth = ViewFrame.calculateShotPreviewWidth(ViewFrame.MAX_MOUSE_SHOT_IMPULSE * 0.5);
        assertEquals(3.5f, midWidth, (float) EPSILON);

        // Clamping check for negative intensity (should clamp to 0%)
        float negativeWidth = ViewFrame.calculateShotPreviewWidth(-1.0);
        assertEquals(2.0f, negativeWidth, (float) EPSILON);

        // Clamping check for excessive intensity (should clamp to 100%)
        float excessiveWidth = ViewFrame.calculateShotPreviewWidth(100.0);
        assertEquals(5.0f, excessiveWidth, (float) EPSILON);
    }
}
