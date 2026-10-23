package hr.avrbanac.snmptool.core.settings;

import java.util.Map;

/**
 * Size and layout of the main window, as last left by the user. Values that are unknown (missing from the
 * file) are {@link Double#NaN}.
 *
 * @param x              left edge of the window when not maximized
 * @param y              top edge of the window when not maximized
 * @param width          width when not maximized
 * @param height         height when not maximized
 * @param maximized      whether the window was maximized
 * @param treeDivider    position of the divider between MIB tree and details (0..1)
 * @param resultsDivider position of the divider between browser and operations (0..1)
 * @param columnWidths   widths of the results table columns, by column title
 */
public record WindowLayout(double x, double y, double width, double height, boolean maximized, double treeDivider,
        double resultsDivider, Map<String, Double> columnWidths) {

    public WindowLayout {
        columnWidths = Map.copyOf(columnWidths);
    }
}
