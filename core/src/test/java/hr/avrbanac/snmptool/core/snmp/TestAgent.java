package hr.avrbanac.snmptool.core.snmp;

import hr.avrbanac.snmptool.core.mib.model.Oid;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.SMIConstants;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * Minimal SNMP agent on 127.0.0.1 for tests: answers GET, GETNEXT, GETBULK and SET from an in-memory MIB, for
 * v2c (communities "public" / "private") and v3 (users listed in {@link #USERS}).
 */
final class TestAgent implements CommandResponder, AutoCloseable {

    static final String READ_COMMUNITY = "public";
    static final String WRITE_COMMUNITY = "private";
    static final String AUTH_PASSWORD = "authpass123";
    static final String PRIV_PASSWORD = "privpass123";

    /** v3 users: name → {auth protocol, priv protocol} (null for none). */
    static final Map<String, OID[]> USERS = Map.of(
            "noauth", new OID[] {null, null},
            "authsha", new OID[] {AuthSHA.ID, null},
            "sha256aes", new OID[] {AuthHMAC192SHA256.ID, PrivAES128.ID},
            "sha256aes256", new OID[] {AuthHMAC192SHA256.ID, PrivAES256.ID},
            "md5des", new OID[] {AuthMD5.ID, PrivDES.ID});

    private final NavigableMap<OID, Variable> mib = new TreeMap<>();
    private final Set<OID> writable = new HashSet<>();
    private final Snmp snmp;
    private final int port;
    private volatile boolean silent;

    TestAgent() throws IOException {
        SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.maxCompatibility);
        protocols.addDefaultProtocols();
        protocols.addAuthenticationProtocol(new AuthMD5());
        protocols.addPrivacyProtocol(new PrivDES());
        byte[] engineId = MPv3.createLocalEngineID(new OctetString("test-agent"));
        USM usm = new USM(protocols, new OctetString(engineId), 0);
        USERS.forEach((name, p) -> usm.addUser(
                new UsmUser(new OctetString(name), p[0], p[0] == null ? null : new OctetString(AUTH_PASSWORD),
                        p[1], p[1] == null ? null : new OctetString(PRIV_PASSWORD)),
                new OctetString(engineId)));

        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        dispatcher.addMessageProcessingModel(new MPv2c());
        dispatcher.addMessageProcessingModel(new MPv3(usm));
        DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping(new UdpAddress("127.0.0.1/0"));
        snmp = new Snmp(dispatcher, transport);
        snmp.addCommandResponder(this);
        snmp.listen();
        port = transport.getListenAddress().getPort();
    }

    int port() {
        return port;
    }

    /** Stops answering, so requests time out. */
    void silence() {
        silent = true;
    }

    TestAgent put(String oid, Variable value) {
        synchronized (mib) {
            mib.put(new OID(oid), value);
        }
        return this;
    }

    TestAgent putWritable(String oid, Variable value) {
        put(oid, value);
        synchronized (mib) {
            writable.add(new OID(oid));
        }
        return this;
    }

    Variable value(String oid) {
        synchronized (mib) {
            return mib.get(new OID(oid));
        }
    }

    static Oid oid(String text) {
        return Oid.parse(text);
    }

    @Override
    public <A extends org.snmp4j.smi.Address> void processPdu(CommandResponderEvent<A> event) {
        if (silent) {
            return;
        }
        PDU request = event.getPDU();
        if (request == null) {
            return;
        }
        boolean v3 = event.getMessageProcessingModel() == MPv3.ID;
        String security = new String(event.getSecurityName());
        if (!v3 && !(security.equals(READ_COMMUNITY) || security.equals(WRITE_COMMUNITY))) {
            return; // unknown community: real agents stay silent
        }
        if (!v3 && request.getType() == PDU.SET && !security.equals(WRITE_COMMUNITY)) {
            return;
        }
        PDU response = request instanceof ScopedPDU scoped ? new ScopedPDU(scoped) : new PDU(request);
        response.clear();
        response.setType(PDU.RESPONSE);
        response.setRequestID(request.getRequestID());
        response.setErrorStatus(0);
        response.setErrorIndex(0);
        synchronized (mib) {
            switch (request.getType()) {
                case PDU.GET -> request.getVariableBindings().forEach(vb -> {
                    Variable v = mib.get(vb.getOid());
                    response.add(new VariableBinding(vb.getOid(), v != null ? v
                            : mib.containsKey(vb.getOid().trim()) ? Null.noSuchInstance : Null.noSuchObject));
                });
                case PDU.GETNEXT -> request.getVariableBindings().forEach(vb -> response.add(next(vb.getOid())));
                case PDU.GETBULK -> {
                    OID current = request.get(0).getOid();
                    for (int i = 0; i < Math.max(1, request.getMaxRepetitions()); i++) {
                        VariableBinding next = next(current);
                        response.add(next);
                        if (next.getVariable().getSyntax() == SMIConstants.EXCEPTION_END_OF_MIB_VIEW) {
                            break;
                        }
                        current = next.getOid();
                    }
                }
                case PDU.SET -> set(request, response);
                default -> {
                    return;
                }
            }
        }
        try {
            event.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(),
                    event.getSecurityModel(), event.getSecurityName(), event.getSecurityLevel(), response,
                    event.getMaxSizeResponsePDU(), event.getStateReference(), new StatusInformation());
            event.setProcessed(true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private VariableBinding next(OID oid) {
        Map.Entry<OID, Variable> next = mib.higherEntry(oid);
        return next == null ? new VariableBinding(oid, Null.endOfMibView)
                : new VariableBinding(next.getKey(), next.getValue());
    }

    private void set(PDU request, PDU response) {
        for (int i = 0; i < request.size(); i++) {
            VariableBinding vb = request.get(i);
            Variable current = mib.get(vb.getOid());
            int error = current == null ? PDU.noCreation
                    : !writable.contains(vb.getOid()) ? PDU.notWritable
                    : current.getSyntax() != vb.getVariable().getSyntax() ? PDU.wrongType
                    : PDU.noError;
            if (error != PDU.noError) {
                response.addAll(request.getVariableBindings());
                response.setErrorStatus(error);
                response.setErrorIndex(i + 1);
                return;
            }
        }
        request.getVariableBindings().forEach(vb -> mib.put(vb.getOid(), vb.getVariable()));
        response.addAll(request.getVariableBindings());
    }

    @Override
    public void close() throws IOException {
        snmp.close();
    }
}
