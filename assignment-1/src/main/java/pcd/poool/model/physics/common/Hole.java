package pcd.poool.model.physics.common;

import pcd.poool.model.common.math.P2d;

/**
 * Circular hole on the board.
 *
 * @param center hole center
 * @param radius hole radius
 */
public record Hole(P2d center, double radius) {

    /**
     * Checks whether a point is inside this hole.
     *
     * @param point point to test
     * @return whether the point lies inside or on the hole boundary
     */
    public boolean contains(P2d point) {
        return center.sub(point).abs() <= radius;
    }
}
