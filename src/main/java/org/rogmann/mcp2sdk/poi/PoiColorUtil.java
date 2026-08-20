package org.rogmann.mcp2sdk.poi;

import java.awt.Color;

/**
 * Shared color helpers for the POI-based toolboxes (xlsx/docx/pptx).
 * <p>
 * Parses RGB hex strings (e.g. {@code "FF0000"} or {@code "#FF0000"}) into
 * {@link java.awt.Color} values which are needed e.g. for PowerPoint font colors.
 * </p>
 */
public final class PoiColorUtil {

    private PoiColorUtil() {
        // Utility class
    }

    /**
     * Parses an RGB color given as a hex string.
     * @param hex hex string, e.g. {@code "FF0000"} or {@code "#FF0000"}
     * @return the parsed {@link java.awt.Color}
     * @throws PoiUserRuntimeException if the value is not a valid 6-digit hex RGB color
     */
    public static Color parseHexColor(String hex) {
        String h = stripHash(hex);
        if (h == null || h.length() != 6) {
            throw new PoiUserRuntimeException(
                    "Invalid color: '" + hex + "' (expected 6 hex digits, e.g. 'FF0000' or '#FF0000').");
        }
        try {
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            return new Color(r, g, b);
        } catch (NumberFormatException e) {
            throw new PoiUserRuntimeException("Invalid color: '" + hex + "'.");
        }
    }

    /**
     * Formats a {@link java.awt.Color} as a 6-digit uppercase hex string (e.g. "FF0000").
     * @param color the color (must not be null)
     * @return hex string
     */
    public static String toHex(Color color) {
        return String.format("%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    /**
     * Removes a leading '#' from a hex string and trims whitespace.
     * @param hex input value
     * @return hex string without leading '#', or null if null/blank
     */
    public static String stripHash(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        return s;
    }
}
