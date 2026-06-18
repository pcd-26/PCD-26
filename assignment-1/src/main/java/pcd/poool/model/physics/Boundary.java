package pcd.poool.model.physics;

/**
 * Axis-aligned rectangular board boundary.
 *
 * @param x0 minimum X coordinate
 * @param y0 minimum Y coordinate
 * @param x1 maximum X coordinate
 * @param y1 maximum Y coordinate
 */
public record Boundary (double x0, double y0, double x1, double y1){}
