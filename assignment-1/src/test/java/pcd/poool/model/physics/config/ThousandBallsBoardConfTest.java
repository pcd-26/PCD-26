package pcd.poool.model.physics.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class ThousandBallsBoardConfTest {

    @Test
    void createsOneThousandSmallBalls() {
        var config = new ThousandBallsBoardConf();

        assertEquals(ThousandBallsBoardConf.SMALL_BALL_COUNT, config.getSmallBalls().size());
        assertNotNull(config.getPlayerBall());
        assertNotNull(config.getBotBall());
        assertNotNull(config.getBoardBoundary());
    }
}
