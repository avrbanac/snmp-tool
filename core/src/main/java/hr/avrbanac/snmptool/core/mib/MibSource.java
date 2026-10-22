package hr.avrbanac.snmptool.core.mib;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MIB source text with a name used in diagnostics.
 *
 * @param name source name, usually the file name
 * @param text source text
 */
public record MibSource(String name, String text) {

    /** Reads a MIB file. UTF-8 is tried first; files that are not valid UTF-8 are read as ISO-8859-1. */
    public static MibSource read(Path file) throws IOException {
        return new MibSource(file.getFileName().toString(), decode(Files.readAllBytes(file)));
    }

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }
}
