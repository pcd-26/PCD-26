package pcd.poool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PooolApplicationTest {

    @Test
    void computesFramesPerSecondFromTheSharedRenderLoop() {
        assertEquals(50, PooolApplication.framesPerSecond(100, 1_000, 3_000));
        assertEquals(0, PooolApplication.framesPerSecond(1, 1_000, 1_000));
    }
}
