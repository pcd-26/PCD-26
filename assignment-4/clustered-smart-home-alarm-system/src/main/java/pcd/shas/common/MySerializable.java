package pcd.shas.common;

/**
 * Marker interface for message types that must cross Pekko Cluster boundaries.
 *
 * <p>The project maps this marker to the Jackson JSON serializer in
 * {@code application.conf} so remote actor messages remain explicitly
 * serializable.</p>
 */
public interface MySerializable {}
