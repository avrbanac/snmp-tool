package hr.avrbanac.snmptool.app;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/**
 * Clipboard helper.
 */
public final class Clipboards {

    private Clipboards() {
    }

    /** Puts plain text on the system clipboard. */
    public static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
