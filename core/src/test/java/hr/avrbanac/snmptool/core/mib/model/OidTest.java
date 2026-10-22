package hr.avrbanac.snmptool.core.mib.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OidTest {

    @Test
    void parsesDottedNotation() {
        assertEquals(Oid.of(1, 3, 6, 1), Oid.parse("1.3.6.1"));
        assertEquals(Oid.of(1, 3, 6, 1), Oid.parse(".1.3.6.1"));
        assertEquals(Oid.of(1, 3, Oid.MAX_ARC), Oid.parse("1.3.4294967295"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "1..3", "1.3.", "1.a.3", "1.-3", "1.3.4294967296", "1.3.99999999999999999999"})
    void rejectsInvalidText(String text) {
        assertThrows(IllegalArgumentException.class, () -> Oid.parse(text));
    }

    @Test
    void rejectsInvalidArcs() {
        assertThrows(IllegalArgumentException.class, Oid::of);
        assertThrows(IllegalArgumentException.class, () -> Oid.of(1, -1));
        assertThrows(IllegalArgumentException.class, () -> Oid.of(1).child(Oid.MAX_ARC + 1));
    }

    @Test
    void formatsWithoutLeadingDot() {
        assertEquals("1.3.6.1.2.1", Oid.parse(".1.3.6.1.2.1").toString());
    }

    @Test
    void navigatesParentAndChild() {
        Oid mib2 = Oid.parse("1.3.6.1.2.1");
        assertEquals(Oid.parse("1.3.6.1.2.1.1"), mib2.child(1));
        assertEquals(Optional.of(Oid.parse("1.3.6.1.2")), mib2.parent());
        assertEquals(Optional.empty(), Oid.of(1).parent());
        assertEquals(1, mib2.lastArc());
        assertEquals(6, mib2.size());
    }

    @Test
    void checksPrefix() {
        Oid mib2 = Oid.parse("1.3.6.1.2.1");
        assertTrue(Oid.parse("1.3.6.1.2.1.2.2.1.2.3").startsWith(mib2));
        assertTrue(mib2.startsWith(mib2));
        assertFalse(Oid.parse("1.3.6.1.2").startsWith(mib2));
        assertFalse(Oid.parse("1.3.6.1.2.2").startsWith(mib2));
    }

    @Test
    void ordersLexicographicallyByArc() {
        List<Oid> sorted = List.of("1.3.6.1.10", "1.3.6.1", "1.3.6.1.2.1", "1.3.6.1.2").stream()
                .map(Oid::parse)
                .sorted()
                .toList();
        assertEquals(List.of("1.3.6.1", "1.3.6.1.2", "1.3.6.1.2.1", "1.3.6.1.10"),
                sorted.stream().map(Oid::toString).toList());
    }

    @Test
    void isImmutable() {
        long[] arcs = {1, 3, 6};
        Oid oid = Oid.of(arcs);
        arcs[0] = 2;
        oid.arcs()[1] = 9;
        assertEquals("1.3.6", oid.toString());
    }
}
