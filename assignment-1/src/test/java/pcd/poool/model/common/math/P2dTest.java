package pcd.poool.model.common.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class P2dTest {

    @Test
    void sumTranslatesPointByVector() {
        var point = new P2d(1.5, -2.0);
        var vector = new V2d(0.5, 3.0);

        assertEquals(new P2d(2.0, 1.0), point.sum(vector));
    }

    @Test
    void subComputesVectorBetweenPoints() {
        var from = new P2d(4.0, 1.5);
        var to = new P2d(1.0, -0.5);

        assertEquals(new V2d(3.0, 2.0), from.sub(to));
    }
}
