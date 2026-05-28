package pcd.poool.model.physics;

import pcd.poool.model.common.math.P2d;

/**
 * Circular hole on the board.
 */
public record Hole(P2d center, double radius) {

    public boolean contains(P2d point) {
        return center.sub(point).abs() <= radius;
    }
}
