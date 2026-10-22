package hr.avrbanac.snmptool.core.snmp;

import static hr.avrbanac.snmptool.core.snmp.TestAgent.oid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hr.avrbanac.snmptool.core.snmp.V3Security.AuthProtocol;
import hr.avrbanac.snmptool.core.snmp.V3Security.Level;
import hr.avrbanac.snmptool.core.snmp.V3Security.PrivProtocol;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;

class SnmpSessionTest {

    private TestAgent agent;

    @BeforeEach
    void startAgent() throws IOException {
        agent = new TestAgent()
                .put("1.3.6.1.2.1.1.1.0", new OctetString("Test agent"))
                .put("1.3.6.1.2.1.1.2.0", new OID("1.3.6.1.4.1.8072.3.2.10"))
                .put("1.3.6.1.2.1.1.3.0", new TimeTicks(123456))
                .putWritable("1.3.6.1.2.1.1.5.0", new OctetString("old-name"))
                .put("1.3.6.1.2.1.2.1.0", new Integer32(3))
                .put("1.3.6.1.2.1.4.20.1.1.10.0.0.1", new IpAddress("10.0.0.1"))
                .put("1.3.6.1.2.1.2.2.1.10.1", new Counter32(4000000000L))
                .put("1.3.6.1.2.1.2.2.1.5.1", new Gauge32(1000000000L))
                .put("1.3.6.1.2.1.31.1.1.1.6.1", new Counter64(-2L)); // 2^64 - 2 unsigned
        for (int i = 1; i <= 60; i++) {
            agent.put("1.3.6.1.2.1.2.2.1.2." + i, new OctetString("eth" + i));
        }
    }

    @AfterEach
    void stopAgent() throws IOException {
        agent.close();
    }

    private DeviceProfile v2c() {
        return new DeviceProfile("test", "127.0.0.1", agent.port(), SnmpVersion.V2C, 1000, 0, 10,
                TestAgent.READ_COMMUNITY, TestAgent.WRITE_COMMUNITY, V3Security.noAuth(""));
    }

    private DeviceProfile v3(String user, Level level, AuthProtocol auth, PrivProtocol priv) {
        return new DeviceProfile("test", "127.0.0.1", agent.port(), SnmpVersion.V3, 1000, 0, 10, "", "",
                new V3Security(user, level, auth, TestAgent.AUTH_PASSWORD, priv, TestAgent.PRIV_PASSWORD, "", ""));
    }

    @Test
    void getDecodesAllValueTypes() throws SnmpException {
        try (SnmpSession s = SnmpSession.open(v2c())) {
            List<VarBindResult> r = s.get(List.of(oid("1.3.6.1.2.1.1.1.0"), oid("1.3.6.1.2.1.1.2.0"),
                    oid("1.3.6.1.2.1.1.3.0"), oid("1.3.6.1.2.1.2.1.0"), oid("1.3.6.1.2.1.4.20.1.1.10.0.0.1"),
                    oid("1.3.6.1.2.1.2.2.1.10.1"), oid("1.3.6.1.2.1.2.2.1.5.1"), oid("1.3.6.1.2.1.31.1.1.1.6.1")));
            assertEquals(new SnmpValue.OctetString("Test agent".getBytes(StandardCharsets.UTF_8)), r.get(0).value());
            assertEquals(new SnmpValue.ObjectId(oid("1.3.6.1.4.1.8072.3.2.10")), r.get(1).value());
            assertEquals(new SnmpValue.TimeTicks(123456), r.get(2).value());
            assertEquals(new SnmpValue.Integer32(3), r.get(3).value());
            assertEquals(new SnmpValue.IpAddress(new byte[] {10, 0, 0, 1}), r.get(4).value());
            assertEquals(new SnmpValue.Counter32(4000000000L), r.get(5).value());
            assertEquals(new SnmpValue.Gauge32(1000000000L), r.get(6).value());
            assertEquals(new SnmpValue.Counter64(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.TWO)), r.get(7).value());
        }
    }

    @Test
    void getReportsMissingObjectsAsExceptionValues() throws SnmpException {
        try (SnmpSession s = SnmpSession.open(v2c())) {
            List<VarBindResult> r = s.get(List.of(oid("1.3.6.1.2.1.1.1.0"), oid("1.3.6.1.2.1.1.99.0")));
            assertInstanceOf(SnmpValue.OctetString.class, r.get(0).value());
            assertInstanceOf(SnmpValue.NoSuchObject.class, r.get(1).value());
            assertTrue(r.get(1).value().isException());
        }
    }

    @Test
    void getNextReturnsFollowingInstance() throws SnmpException {
        try (SnmpSession s = SnmpSession.open(v2c())) {
            List<VarBindResult> r = s.getNext(List.of(oid("1.3.6.1.2.1.1")));
            assertEquals(oid("1.3.6.1.2.1.1.1.0"), r.getFirst().oid());
        }
    }

    @Test
    void walkStreamsSubtreeInBatches() throws SnmpException {
        List<List<VarBindResult>> batches = new ArrayList<>();
        try (SnmpSession s = SnmpSession.open(v2c())) {
            WalkResult result = s.walk(oid("1.3.6.1.2.1.2.2.1.2"), batches::add, () -> false);
            assertEquals(60, result.count());
            assertEquals(WalkResult.End.END_OF_SUBTREE, result.end());
        }
        assertTrue(batches.size() >= 6, "max-repetitions 10 gives several batches");
        List<VarBindResult> all = batches.stream().flatMap(List::stream).toList();
        assertEquals(oid("1.3.6.1.2.1.2.2.1.2.1"), all.getFirst().oid());
        assertEquals(oid("1.3.6.1.2.1.2.2.1.2.60"), all.getLast().oid());
    }

    @Test
    void walkOfScalarInstanceFallsBackToGet() throws SnmpException {
        List<VarBindResult> received = new ArrayList<>();
        try (SnmpSession s = SnmpSession.open(v2c())) {
            WalkResult result = s.walk(oid("1.3.6.1.2.1.1.1.0"), received::addAll, () -> false);
            assertEquals(new WalkResult(1, WalkResult.End.SINGLE_GET), result);
        }
        assertEquals(oid("1.3.6.1.2.1.1.1.0"), received.getFirst().oid());
    }

    @Test
    void walkToEndOfMibView() throws SnmpException {
        try (SnmpSession s = SnmpSession.open(v2c())) {
            WalkResult result = s.walk(oid("1.3.6.1.2.1.31"), _ -> { }, () -> false);
            assertEquals(new WalkResult(1, WalkResult.End.END_OF_MIB_VIEW), result);
        }
    }

    @Test
    void walkCanBeCancelled() throws SnmpException {
        AtomicBoolean cancel = new AtomicBoolean();
        List<VarBindResult> received = new ArrayList<>();
        try (SnmpSession s = SnmpSession.open(v2c())) {
            WalkResult result = s.walk(oid("1.3.6.1.2.1.2.2.1.2"), batch -> {
                received.addAll(batch);
                cancel.set(true);
            }, cancel::get);
            assertEquals(WalkResult.End.CANCELLED, result.end());
            assertEquals(10, result.count());
        }
    }

    @Test
    void setChangesWritableObject() throws SnmpException {
        try (SnmpSession s = SnmpSession.open(v2c())) {
            SnmpValue value = new SnmpValue.OctetString("new-name".getBytes(StandardCharsets.UTF_8));
            List<VarBindResult> r = s.set(List.of(new VarBindResult(oid("1.3.6.1.2.1.1.5.0"), value)));
            assertEquals(value, r.getFirst().value());
            assertEquals(new OctetString("new-name"), agent.value("1.3.6.1.2.1.1.5.0"));
        }
    }

    @Test
    void setReportsErrorStatus() throws SnmpException {
        try (SnmpSession s = SnmpSession.open(v2c())) {
            SnmpException e = assertThrows(SnmpException.class, () -> s.set(List.of(
                    new VarBindResult(oid("1.3.6.1.2.1.1.1.0"), new SnmpValue.OctetString(new byte[] {1})))));
            assertEquals(SnmpException.Kind.ERROR_STATUS, e.kind());
            assertEquals(17, e.errorStatus());
            assertEquals(1, e.errorIndex());
            assertTrue(e.getMessage().contains("1.3.6.1.2.1.1.1.0"), e.getMessage());

            SnmpException wrongType = assertThrows(SnmpException.class, () -> s.set(List.of(
                    new VarBindResult(oid("1.3.6.1.2.1.1.5.0"), new SnmpValue.Integer32(1)))));
            assertEquals(7, wrongType.errorStatus());
        }
    }

    @Test
    void wrongCommunityTimesOut() throws SnmpException {
        DeviceProfile wrong = new DeviceProfile("test", "127.0.0.1", agent.port(), SnmpVersion.V2C, 200, 0, 10,
                "wrong", "wrong", V3Security.noAuth(""));
        try (SnmpSession s = SnmpSession.open(wrong)) {
            SnmpException e = assertThrows(SnmpException.class, () -> s.get(List.of(oid("1.3.6.1.2.1.1.1.0"))));
            assertEquals(SnmpException.Kind.TIMEOUT, e.kind());
            assertTrue(e.getMessage().contains("community"), e.getMessage());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "noauth,       NO_AUTH_NO_PRIV, SHA256, AES128",
        "authsha,      AUTH_NO_PRIV,    SHA1,   AES128",
        "sha256aes,    AUTH_PRIV,       SHA256, AES128",
        "sha256aes256, AUTH_PRIV,       SHA256, AES256",
        "md5des,       AUTH_PRIV,       MD5,    DES",
    })
    void v3SecurityLevelsAndProtocols(String user, Level level, AuthProtocol auth, PrivProtocol priv)
            throws SnmpException {
        try (SnmpSession s = SnmpSession.open(v3(user, level, auth, priv))) {
            List<VarBindResult> r = s.get(List.of(oid("1.3.6.1.2.1.1.1.0")));
            assertEquals(new SnmpValue.OctetString("Test agent".getBytes(StandardCharsets.UTF_8)), r.getFirst().value());
            List<VarBindResult> walked = new ArrayList<>();
            assertEquals(60, s.walk(oid("1.3.6.1.2.1.2.2.1.2"), walked::addAll, () -> false).count());
        }
    }

    @Test
    void v3SetWorks() throws SnmpException {
        try (SnmpSession s = SnmpSession.open(v3("sha256aes", Level.AUTH_PRIV, AuthProtocol.SHA256, PrivProtocol.AES128))) {
            s.set(List.of(new VarBindResult(oid("1.3.6.1.2.1.1.5.0"),
                    new SnmpValue.OctetString("v3-name".getBytes(StandardCharsets.UTF_8)))));
        }
        assertEquals(new OctetString("v3-name"), agent.value("1.3.6.1.2.1.1.5.0"));
    }

    /**
     * RFC 3414 agents report a wrong digest unauthenticated, which surfaces as a REPORT. The SNMP4J test agent
     * authenticates that report with the real key, so the client (correctly) cannot verify it and times out;
     * the timeout message then points at the passphrases.
     */
    @Test
    void v3WrongPassphraseTimesOutWithHint() throws SnmpException {
        DeviceProfile wrongAuth = new DeviceProfile("test", "127.0.0.1", agent.port(), SnmpVersion.V3, 1000, 0, 10,
                "", "", new V3Security("authsha", Level.AUTH_NO_PRIV, AuthProtocol.SHA1, "not-the-password",
                        PrivProtocol.AES128, "", "", ""));
        try (SnmpSession s = SnmpSession.open(wrongAuth)) {
            SnmpException e = assertThrows(SnmpException.class, () -> s.get(List.of(oid("1.3.6.1.2.1.1.1.0"))));
            assertEquals(SnmpException.Kind.TIMEOUT, e.kind(), e.getMessage());
            assertTrue(e.getMessage().contains("passphrases"), e.getMessage());
        }
    }

    @Test
    void v3ReportsUnknownUser() throws SnmpException {
        try (SnmpSession s = SnmpSession.open(v3("nobody", Level.NO_AUTH_NO_PRIV, AuthProtocol.SHA1, PrivProtocol.AES128))) {
            SnmpException e = assertThrows(SnmpException.class, () -> s.get(List.of(oid("1.3.6.1.2.1.1.1.0"))));
            assertEquals(SnmpException.Kind.REPORT, e.kind(), e.getMessage());
            assertTrue(e.getMessage().contains("unknown user"), e.getMessage());
        }
    }

    @Test
    void invalidProfilesAndHostsAreRejected() {
        DeviceProfile noHost = DeviceProfile.v2c("x", " ", "public", "private");
        assertEquals(SnmpException.Kind.LOCAL, assertThrows(SnmpException.class, () -> SnmpSession.open(noHost)).kind());
        DeviceProfile badHost = DeviceProfile.v2c("x", "no-such-host.invalid", "public", "private");
        SnmpException e = assertThrows(SnmpException.class, () -> SnmpSession.open(badHost));
        assertTrue(e.getMessage().startsWith("Unknown host"), e.getMessage());
    }

    @Test
    void profilesNeverPrintSecrets() {
        DeviceProfile p = v3("sha256aes", Level.AUTH_PRIV, AuthProtocol.SHA256, PrivProtocol.AES128);
        assertTrue(!p.toString().contains(TestAgent.AUTH_PASSWORD) && !p.v3().toString().contains(TestAgent.PRIV_PASSWORD));
        assertTrue(!DeviceProfile.v2c("x", "h", "secret-read", "secret-write").toString().contains("secret"));
    }
}
