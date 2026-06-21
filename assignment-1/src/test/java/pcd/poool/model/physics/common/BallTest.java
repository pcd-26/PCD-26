package pcd.poool.model.physics.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BallTest {

    private static final double EPSILON = 1e-9;

    @Test
    void uniformMaterialMassMatchesReferenceCueBallMass() {
        assertEquals(1.5, Ball.massForRadius(0.05), EPSILON);
    }

    @Test
    void uniformMaterialMassScalesWithRadiusSquared() {
        double smallMass = Ball.massForRadius(0.025);
        double largeMass = Ball.massForRadius(0.05);

        assertEquals(4.0, largeMass / smallMass, EPSILON);
    }

    @Test
    void rejectsNonPositiveRadiusForDerivedMass() {
        assertThrows(IllegalArgumentException.class, () -> Ball.massForRadius(0.0));
    }
}
