package pcd.poool.model.common.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class V2dTest {

    private static final double EPSILON = 1e-9;

    @Test
    void absComputesEuclideanLength() {
        assertEquals(5.0, new V2d(3.0, 4.0).abs(), EPSILON);
    }

    @Test
    void normalizedVectorHasLengthOne() {
        var normalized = new V2d(3.0, 4.0).getNormalized();

        assertEquals(1.0, normalized.abs(), EPSILON);
        assertEquals(0.6, normalized.x(), EPSILON);
        assertEquals(0.8, normalized.y(), EPSILON);
    }

    @Test
    void zeroVectorNormalizationReturnsZeroVector() {
        assertEquals(new V2d(0.0, 0.0), new V2d(0.0, 0.0).getNormalized());
    }

    @Test
    void mulScalesBothComponents() {
        assertEquals(new V2d(6.0, -9.0), new V2d(2.0, -3.0).mul(3.0));
    }
}
