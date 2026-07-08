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

    private final String symbol;
    private final long bytesFactor;

    SizeUnit(String symbol, long bytesFactor) {
        this.symbol = symbol;
        this.bytesFactor = bytesFactor;
    }

    public String symbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }

    public long bytesFactor() {
        return bytesFactor;
    }

    public long toBytes(double value) {
        return Math.round(value * bytesFactor);
    }

    public double fromBytes(long bytes) {
        return bytes / (double) bytesFactor;
    }

    public String format(long bytes) {
        if (this == BYTES) {
            return String.format(Locale.US, "%,d %s", bytes, symbol);
        }
        return String.format(Locale.US, "%.1f %s", fromBytes(bytes), symbol);
    }

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
