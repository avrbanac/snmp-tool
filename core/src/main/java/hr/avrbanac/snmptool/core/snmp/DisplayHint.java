package hr.avrbanac.snmptool.core.snmp;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * DISPLAY-HINT formatting as defined in RFC 2579, section 3.1.
 */
public final class DisplayHint {

    private DisplayHint() {
    }

    /** One octet-format specification, e.g. {@code 1x:} or {@code *1d.}. */
    private record Spec(boolean repeat, int length, char format, Character separator, Character terminator) {
    }

    /**
     * Formats octets with an OCTET STRING display hint such as {@code 255a}, {@code 1x:} or
     * {@code 2d-1d-1d,1d:1d:1d.1d,1a1d:1d}.
     *
     * @return the formatted text, or empty if the hint is not a valid octet-string hint
     */
    public static Optional<String> formatOctets(String hint, byte[] data) {
        Optional<List<Spec>> parsed = parse(hint.strip());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        List<Spec> specs = parsed.get();
        StringBuilder out = new StringBuilder();
        int pos = 0;
        int specIndex = 0;
        while (pos < data.length) {
            Spec spec = specs.get(Math.min(specIndex, specs.size() - 1));
            int repeat = 1;
            if (spec.repeat()) {
                repeat = data[pos++] & 0xFF;
            }
            for (int r = 0; r < repeat && pos < data.length; r++) {
                int n = Math.min(spec.length(), data.length - pos);
                out.append(render(spec.format(), Arrays.copyOfRange(data, pos, pos + n)));
                pos += n;
                boolean lastOfGroup = spec.repeat() && r == repeat - 1 && spec.terminator() != null;
                if (spec.separator() != null && pos < data.length && !lastOfGroup) {
                    out.append(spec.separator());
                }
            }
            if (spec.repeat() && spec.terminator() != null && pos < data.length) {
                out.append(spec.terminator());
            }
            specIndex++;
        }
        return Optional.of(out.toString());
    }

    /**
     * Formats an integer with an INTEGER display hint: {@code d}, {@code d-N} (N implied decimal places),
     * {@code x}, {@code o} or {@code b}.
     *
     * @return the formatted text, or empty if the hint is not a valid integer hint
     */
    public static Optional<String> formatInteger(String hint, long value) {
        String h = hint.strip();
        if (h.equals("x")) {
            return Optional.of(Long.toHexString(value));
        }
        if (h.equals("o")) {
            return Optional.of(Long.toOctalString(value));
        }
        if (h.equals("b")) {
            return Optional.of(Long.toBinaryString(value));
        }
        if (h.equals("d")) {
            return Optional.of(Long.toString(value));
        }
        if (h.matches("d-\\d+")) {
            int places = Integer.parseInt(h.substring(2));
            if (places == 0) {
                return Optional.of(Long.toString(value));
            }
            String digits = Long.toString(Math.abs(value));
            if (digits.length() <= places) {
                digits = "0".repeat(places - digits.length() + 1) + digits;
            }
            String text = digits.substring(0, digits.length() - places) + "." + digits.substring(digits.length() - places);
            return Optional.of(value < 0 ? "-" + text : text);
        }
        return Optional.empty();
    }

    private static Optional<List<Spec>> parse(String hint) {
        List<Spec> specs = new ArrayList<>();
        int i = 0;
        while (i < hint.length()) {
            boolean repeat = hint.charAt(i) == '*';
            if (repeat) {
                i++;
            }
            int start = i;
            while (i < hint.length() && Character.isDigit(hint.charAt(i))) {
                i++;
            }
            if (i == start || i >= hint.length() || "xdoat".indexOf(hint.charAt(i)) < 0) {
                return Optional.empty();
            }
            int length = Integer.parseInt(hint.substring(start, i));
            char format = hint.charAt(i++);
            Character separator = null;
            if (i < hint.length() && isDelimiter(hint.charAt(i))) {
                separator = hint.charAt(i++);
            }
            Character terminator = null;
            if (repeat && i < hint.length() && isDelimiter(hint.charAt(i))) {
                terminator = hint.charAt(i++);
            }
            if (length == 0) {
                return Optional.empty();
            }
            specs.add(new Spec(repeat, length, format, separator, terminator));
        }
        return specs.isEmpty() ? Optional.empty() : Optional.of(specs);
    }

    private static boolean isDelimiter(char c) {
        return !Character.isDigit(c) && c != '*';
    }

    private static String render(char format, byte[] chunk) {
        return switch (format) {
            case 'a' -> new String(chunk, StandardCharsets.ISO_8859_1);
            case 't' -> new String(chunk, StandardCharsets.UTF_8);
            case 'd' -> new BigInteger(1, chunk).toString();
            case 'o' -> new BigInteger(1, chunk).toString(8);
            case 'x' -> {
                String hex = new BigInteger(1, chunk).toString(16);
                yield "0".repeat(Math.max(0, chunk.length * 2 - hex.length())) + hex;
            }
            default -> throw new IllegalArgumentException("format " + format);
        };
    }
}
