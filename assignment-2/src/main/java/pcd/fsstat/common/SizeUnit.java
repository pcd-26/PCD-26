package pcd.fsstat.common;

import java.util.Locale;

/** File-size units used by the CLI and GUI. */
public enum SizeUnit {
    BYTES("B", 1L),
    KILOBYTES("KiB", 1_024L),
    MEGABYTES("MiB", 1_024L * 1_024L),
    GIGABYTES("GiB", 1_024L * 1_024L * 1_024L);

    /** Unit label shown to users. */
    private final String symbol;
    /** Number of bytes represented by one unit. */
    private final long bytesFactor;

    /** Creates a unit with its label and byte multiplier. */
    SizeUnit(String symbol, long bytesFactor) {
        this.symbol = symbol;
        this.bytesFactor = bytesFactor;
    }

    /** Returns the display symbol. */
    public String symbol() {
        return symbol;
    }

    @Override
    public String toString() {
        return symbol;
    }

    /** Returns the byte multiplier. */
    public long bytesFactor() {
        return bytesFactor;
    }

    /** Converts this unit to bytes. */
    public long toBytes(double value) {
        return Math.round(value * bytesFactor);
    }

    /** Converts bytes to this unit. */
    public double fromBytes(long bytes) {
        return bytes / (double) bytesFactor;
    }

    /** Formats a byte value using this unit. */
    public String format(long bytes) {
        if (this == BYTES) {
            return String.format(Locale.US, "%,d %s", bytes, symbol);
        }
        return String.format(Locale.US, "%.1f %s", fromBytes(bytes), symbol);
    }

    /** Parses a unit name or common alias. */
    public static SizeUnit parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Size unit cannot be null");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "b", "byte", "bytes" -> BYTES;
            case "kb", "kib", "k", "kilobyte", "kilobytes" -> KILOBYTES;
            case "mb", "mib", "m", "megabyte", "megabytes" -> MEGABYTES;
            case "gb", "gib", "g", "gigabyte", "gigabytes" -> GIGABYTES;
            default -> throw new IllegalArgumentException("Unsupported size unit: " + value);
        };
    }
}
