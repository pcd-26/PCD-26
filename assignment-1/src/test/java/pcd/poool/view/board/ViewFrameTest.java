package pcd.poool.view.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;

class ViewFrameTest {

    private static final double EPSILON = 1e-9;

    @Test
    void diagonalArrowCombinationKeepsConfiguredImpulseMagnitude() {
        var shot = ViewFrame.shotImpulseFor(true, false, false, true);

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
}
