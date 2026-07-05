package pcd.poool.model.common.math;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class V2dTest {

    private static final double EPSILON = 1e-9;

    /**
     * Verifies that the abs() method correctly computes the vector's Euclidean length.
     */
    @Test
    void absComputesEuclideanLength() {
        assertEquals(5.0, new V2d(3.0, 4.0).abs(), EPSILON);
    }

    /**
     * Verifies that normalizing a non-zero vector returns a unit vector (length 1.0)
     * pointing in the same direction.
     */
    @Test
    void normalizedVectorHasLengthOne() {
        var normalized = new V2d(3.0, 4.0).getNormalized();

        assertEquals(1.0, normalized.abs(), EPSILON);
        assertEquals(0.6, normalized.x(), EPSILON);
        assertEquals(0.8, normalized.y(), EPSILON);
    }

    /**
     * Verifies that attempting to normalize a zero vector returns a zero vector without division errors.
     */
    @Test
    void zeroVectorNormalizationReturnsZeroVector() {
        assertEquals(new V2d(0.0, 0.0), new V2d(0.0, 0.0).getNormalized());
    }

    /**
     * Verifies that multiplying a vector by a scalar correctly scales both its x and y components.
     */
    @Test
    void mulScalesBothComponents() {
        assertEquals(new V2d(6.0, -9.0), new V2d(2.0, -3.0).mul(3.0));
    }
}
