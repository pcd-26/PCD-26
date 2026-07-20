package pcd.assignment2.common;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/**
 * Utility methods for filesystem operations shared across processing paradigms.
 */
public final class FSUtils {

    private FSUtils() { }

    /**
     * Safely lists files in a directory while guarding against symlink cycles
     * and I/O access resolution failures.
     *
     * @param dir          the directory to list files from
     * @param visitedPaths set of canonical paths already visited to prevent cycles
     * @return array of child files, or {@code null} if a cycle is detected, access fails, or dir is not readable
     */
    public static File[] listFilesSafely(File dir, Set<String> visitedPaths) {
        try {
            String canonicalPath = dir.getCanonicalPath();
            if (!visitedPaths.add(canonicalPath)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return dir.listFiles();
    }
}
