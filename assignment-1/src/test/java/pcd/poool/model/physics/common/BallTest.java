package pcd.poool.model.physics.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BallTest {

    private static final double EPSILON = 1e-9;

    /**
     * Verifies that uniform material density gives exactly 1.5 units of mass for
     * a ball of radius 0.05 (matching the reference cue ball properties).
     */
    @Test
    void uniformMaterialMassMatchesReferenceCueBallMass() {
        assertEquals(1.5, Ball.massForRadius(0.05), EPSILON);
    }

    /**
     * Verifies that mass in the 2D uniform density model scales quadratically with the radius
     * (since mass is proportional to disk area, i.e., area = pi * r^2).
     */
    @Test
    void uniformMaterialMassScalesWithRadiusSquared() {
        double smallMass = Ball.massForRadius(0.025);
        double largeMass = Ball.massForRadius(0.05);

        assertEquals(4.0, largeMass / smallMass, EPSILON);
    }

    /**
     * Verifies that passing a zero or negative radius throws an IllegalArgumentException.
     */
    @Test
    void rejectsNonPositiveRadiusForDerivedMass() {
        assertThrows(IllegalArgumentException.class, () -> Ball.massForRadius(0.0));
    }
}
