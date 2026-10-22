package hr.avrbanac.snmptool.core.snmp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DisplayHintTest {

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    @Test
    void formatsOctetStrings() {
        assertEquals(Optional.of("Hello"), DisplayHint.formatOctets("255a", "Hello".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Optional.of("00:1b:21:3a:4f:5e"), DisplayHint.formatOctets("1x:", bytes(0, 0x1b, 0x21, 0x3a, 0x4f, 0x5e)));
        assertEquals(Optional.of("192.0.2.1"), DisplayHint.formatOctets("1d.1d.1d.1d", bytes(192, 0, 2, 1)));
        assertEquals(Optional.of("0102"), DisplayHint.formatOctets("2x", bytes(1, 2)));
        assertEquals(Optional.of("čćž"), DisplayHint.formatOctets("255t", "čćž".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void formatsDateAndTime() {
        String hint = "2d-1d-1d,1d:1d:1d.1d,1a1d:1d";
        assertEquals(Optional.of("2024-10-22,14:30:15.0,+2:0"),
                DisplayHint.formatOctets(hint, bytes(0x07, 0xe8, 10, 22, 14, 30, 15, 0, '+', 2, 0)));
        assertEquals(Optional.of("2024-10-22,14:30:15.0"),
                DisplayHint.formatOctets(hint, bytes(0x07, 0xe8, 10, 22, 14, 30, 15, 0)));
    }

    @Test
    void repeatsWithTerminator() {
        // "*1d./": a count octet, then that many decimal octets separated by '.', terminated by '/'.
        assertEquals(Optional.of("1.2.3/4"), DisplayHint.formatOctets("*1d./1d", bytes(3, 1, 2, 3, 4)));
    }

    @Test
    void rejectsInvalidHints() {
        assertEquals(Optional.empty(), DisplayHint.formatOctets("zz", bytes(1)));
        assertEquals(Optional.empty(), DisplayHint.formatOctets("x", bytes(1)));
        assertEquals(Optional.empty(), DisplayHint.formatInteger("q", 1));
    }

    @Test
    void formatsIntegers() {
        assertEquals(Optional.of("12.34"), DisplayHint.formatInteger("d-2", 1234));
        assertEquals(Optional.of("-0.05"), DisplayHint.formatInteger("d-2", -5));
        assertEquals(Optional.of("ff"), DisplayHint.formatInteger("x", 255));
        assertEquals(Optional.of("101"), DisplayHint.formatInteger("b", 5));
        assertEquals(Optional.of("42"), DisplayHint.formatInteger("d", 42));
    }
}
