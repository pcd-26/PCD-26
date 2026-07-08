package pcd.assignment2.common;

import java.util.Locale;

/**
 * Supported file-size display and input units.
 *
 * <p>The internal report model always stores sizes in bytes. This enum is used
 * only for user-facing input and formatting.
 */
public enum SizeUnit {
    BYTES("B", 1L),
    KILOBYTES("KiB", 1_024L),
    MEGABYTES("MiB", 1_024L * 1_024L),
    GIGABYTES("GiB", 1_024L * 1_024L * 1_024L);

    /** The user-facing unit symbol (e.g. "MiB"). */
    private final String symbol;
    /** The factor used to multiply/divide when converting to/from bytes. */
    private final long bytesFactor;

    /**
     * Internal constructor for SizeUnit.
     *
     * @param symbol      The display symbol.
     * @param bytesFactor The conversion multiplier factor.
     */
    SizeUnit(String symbol, long bytesFactor) {
        this.symbol = symbol;
        this.bytesFactor = bytesFactor;
    }

    /**
     * Gets the symbol representing the size unit.
     *
     * @return The unit symbol.
     */
    public String symbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }

    /**
     * Gets the bytes conversion factor of this unit.
     *
     * @return The conversion factor.
     */
    public long bytesFactor() {
        return bytesFactor;
    }

    /**
     * Converts a value in this unit into bytes.
     *
     * @param value The value in this unit.
     * @return The corresponding size in bytes.
     */
    public long toBytes(double value) {
        return Math.round(value * bytesFactor);
    }

    /**
     * Converts a size in bytes into a value in this unit.
     *
     * @param bytes The size in bytes.
     * @return The corresponding value in this unit.
     */
    public double fromBytes(long bytes) {
        return bytes / (double) bytesFactor;
    }

    /**
     * Formats a given size in bytes to a string representation in this unit.
     *
     * @param bytes The size in bytes.
     * @return A formatted String representation.
     */
    public String format(long bytes) {
        if (this == BYTES) {
            return String.format(Locale.US, "%,d %s", bytes, symbol);
        }
        return String.format(Locale.US, "%.1f %s", fromBytes(bytes), symbol);
    }

    /**
     * Parses a string representing a size unit into a SizeUnit enum value.
     * Supports standard prefixes and common aliases (e.g., "kb", "mb", "gb").
     *
     * @param value The string to parse.
     * @return The matching SizeUnit.
     */
    public static SizeUnit parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Size unit cannot be null");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "b", "byte", "bytes" -> BYTES;
            case "kb", "k", "kilobyte", "kilobytes" -> KILOBYTES;
            case "mb", "m", "megabyte", "megabytes" -> MEGABYTES;
            case "gb", "g", "gigabyte", "gigabytes" -> GIGABYTES;
            default -> throw new IllegalArgumentException("Unsupported size unit: " + value);
        };
    }
}
