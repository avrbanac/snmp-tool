package hr.avrbanac.snmptool.core.snmp;

import hr.avrbanac.snmptool.core.mib.model.Oid;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.snmp4j.CommunityTarget;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthHMAC128SHA224;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC256SHA384;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.Priv3DES;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * A connection to one agent described by a {@link DeviceProfile}: get, getNext, walk and set.
 *
 * <p>Each session has its own UDP socket and, for v3, its own USM with only the profile's user, so sessions
 * never share security state. Methods block until the agent answers or the request times out; call them off
 * the UI thread. A session may be used from several threads, but requests are meant to be sequential.
 */
public final class SnmpSession implements AutoCloseable {

    /** usmStats counters reported by v3 agents, with a readable explanation. */
    private static final Map<String, String> USM_REPORTS = Map.of(
            "1.3.6.1.6.3.15.1.1.1.0", "unsupported security level",
            "1.3.6.1.6.3.15.1.1.2.0", "not in time window",
            "1.3.6.1.6.3.15.1.1.3.0", "unknown user name",
            "1.3.6.1.6.3.15.1.1.4.0", "unknown engine ID",
            "1.3.6.1.6.3.15.1.1.5.0", "wrong digest (authentication passphrase or protocol)",
            "1.3.6.1.6.3.15.1.1.6.0", "decryption error (privacy passphrase or protocol)");

    private final DeviceProfile profile;
    private final Snmp snmp;
    private final Target<UdpAddress> readTarget;
    private final Target<UdpAddress> writeTarget;

    private SnmpSession(DeviceProfile profile, Snmp snmp, Target<UdpAddress> readTarget,
            Target<UdpAddress> writeTarget) {
        this.profile = profile;
        this.snmp = snmp;
        this.readTarget = readTarget;
        this.writeTarget = writeTarget;
    }

    /**
     * Opens a session. The host name is resolved here.
     *
     * @throws SnmpException with {@link SnmpException.Kind#LOCAL} if the profile is invalid, the host is unknown
     *                       or the socket cannot be opened
     */
    public static SnmpSession open(DeviceProfile profile) throws SnmpException {
        List<String> problems = profile.problems();
        if (!problems.isEmpty()) {
            throw new SnmpException(SnmpException.Kind.LOCAL, String.join("; ", problems));
        }
        UdpAddress address;
        try {
            address = new UdpAddress(InetAddress.getByName(profile.host().strip()), profile.port());
        } catch (UnknownHostException e) {
            throw new SnmpException(SnmpException.Kind.LOCAL, "Unknown host " + profile.host(), e);
        }
        try {
            SecurityProtocols protocols = new SecurityProtocols(SecurityProtocols.SecurityProtocolSet.none);
            protocols.addAuthenticationProtocol(new AuthMD5());
            protocols.addAuthenticationProtocol(new AuthSHA());
            protocols.addAuthenticationProtocol(new AuthHMAC128SHA224());
            protocols.addAuthenticationProtocol(new AuthHMAC192SHA256());
            protocols.addAuthenticationProtocol(new AuthHMAC256SHA384());
            protocols.addAuthenticationProtocol(new AuthHMAC384SHA512());
            protocols.addPrivacyProtocol(new PrivDES());
            protocols.addPrivacyProtocol(new Priv3DES());
            protocols.addPrivacyProtocol(new PrivAES128());
            protocols.addPrivacyProtocol(new PrivAES192());
            protocols.addPrivacyProtocol(new PrivAES256());
            USM usm = new USM(protocols, new OctetString(MPv3.createLocalEngineID()), 0);

            MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
            dispatcher.addMessageProcessingModel(new MPv2c());
            dispatcher.addMessageProcessingModel(new MPv3(usm));
            Snmp snmp = new Snmp(dispatcher, new DefaultUdpTransportMapping());
            snmp.listen();

            if (profile.version() == SnmpVersion.V3) {
                V3Security v3 = profile.v3();
                usm.addUser(new UsmUser(new OctetString(v3.user()),
                        v3.level().authenticated() ? authProtocol(v3.authProtocol()) : null,
                        v3.level().authenticated() ? new OctetString(v3.authPassword()) : null,
                        v3.level().encrypted() ? privProtocol(v3.privProtocol()) : null,
                        v3.level().encrypted() ? new OctetString(v3.privPassword()) : null));
                Target<UdpAddress> target = userTarget(profile, address);
                return new SnmpSession(profile, snmp, target, target);
            }
            return new SnmpSession(profile, snmp, communityTarget(profile, address, profile.readCommunity()),
                    communityTarget(profile, address, profile.writeCommunity()));
        } catch (IOException e) {
            throw new SnmpException(SnmpException.Kind.LOCAL, "Cannot open UDP socket: " + e.getMessage(), e);
        }
    }

    public DeviceProfile profile() {
        return profile;
    }

    /** GET of the given instances. Missing instances come back as noSuchObject / noSuchInstance values. */
    public List<VarBindResult> get(List<Oid> oids) throws SnmpException {
        return results(send(pdu(PDU.GET, oids), readTarget));
    }

    /** GETNEXT: for each OID, the next instance in the agent's MIB view. */
    public List<VarBindResult> getNext(List<Oid> oids) throws SnmpException {
        return results(send(pdu(PDU.GETNEXT, oids), readTarget));
    }

    /**
     * Walks the subtree under {@code root} with GETBULK, delivering each response as a batch. If the subtree is
     * empty (e.g. the root is a scalar instance like {@code sysDescr.0}), the root itself is fetched with GET.
     *
     * @param batches   receives the variable bindings of each response, in OID order
     * @param cancelled checked before each request; the walk stops when it returns true
     * @throws SnmpException if a request fails or the agent returns OIDs that do not increase
     */
    public WalkResult walk(Oid root, Consumer<List<VarBindResult>> batches, BooleanSupplier cancelled)
            throws SnmpException {
        Oid current = root;
        int count = 0;
        while (!cancelled.getAsBoolean()) {
            PDU request = pdu(PDU.GETBULK, List.of(current));
            request.setNonRepeaters(0);
            request.setMaxRepetitions(profile.maxRepetitions());
            List<VarBindResult> response = results(send(request, readTarget));
            List<VarBindResult> batch = new ArrayList<>();
            WalkResult.End end = null;
            for (VarBindResult vb : response) {
                if (vb.value() instanceof SnmpValue.EndOfMibView) {
                    end = WalkResult.End.END_OF_MIB_VIEW;
                    break;
                }
                if (!vb.oid().startsWith(root)) {
                    end = WalkResult.End.END_OF_SUBTREE;
                    break;
                }
                if (vb.oid().compareTo(current) <= 0) {
                    throw new SnmpException(SnmpException.Kind.LOCAL, "Agent returned " + vb.oid() + " after "
                            + current + "; OIDs must increase, walk stopped");
                }
                batch.add(vb);
                current = vb.oid();
            }
            if (!batch.isEmpty()) {
                batches.accept(batch);
                count += batch.size();
            }
            if (response.isEmpty()) {
                end = WalkResult.End.END_OF_MIB_VIEW;
            }
            if (end != null) {
                if (count == 0) {
                    List<VarBindResult> single = get(List.of(root));
                    if (!single.isEmpty() && !single.getFirst().value().isException()) {
                        batches.accept(single);
                        return new WalkResult(1, WalkResult.End.SINGLE_GET);
                    }
                }
                return new WalkResult(count, end);
            }
        }
        return new WalkResult(count, WalkResult.End.CANCELLED);
    }

    /**
     * SET of the given values in one request (all or nothing, as the agent implements it).
     *
     * @return the variable bindings of the response
     */
    public List<VarBindResult> set(List<VarBindResult> values) throws SnmpException {
        PDU request = newPdu(PDU.SET);
        values.forEach(v -> request.add(new VariableBinding(Variables.toOid(v.oid()), Variables.toVariable(v.value()))));
        return results(send(request, writeTarget));
    }

    @Override
    public void close() {
        try {
            snmp.close();
        } catch (IOException e) {
            // Nothing useful to do; the socket is gone either way.
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private PDU pdu(int type, List<Oid> oids) {
        PDU pdu = newPdu(type);
        oids.forEach(o -> pdu.add(new VariableBinding(Variables.toOid(o))));
        return pdu;
    }

    private PDU newPdu(int type) {
        PDU pdu;
        if (profile.version() == SnmpVersion.V3) {
            ScopedPDU scoped = new ScopedPDU();
            scoped.setContextName(new OctetString(profile.v3().contextName()));
            byte[] engineId = profile.v3().contextEngineIdBytes();
            if (engineId.length > 0) {
                scoped.setContextEngineID(new OctetString(engineId));
            }
            pdu = scoped;
        } else {
            pdu = new PDU();
        }
        pdu.setType(type);
        return pdu;
    }

    private PDU send(PDU request, Target<UdpAddress> target) throws SnmpException {
        ResponseEvent<UdpAddress> event;
        try {
            event = snmp.send(request, target);
        } catch (IOException e) {
            throw new SnmpException(SnmpException.Kind.LOCAL, "Sending failed: " + e.getMessage(), e);
        }
        if (event == null || event.getResponse() == null) {
            if (event != null && event.getError() != null) {
                throw new SnmpException(SnmpException.Kind.LOCAL, describe(event.getError()), event.getError());
            }
            throw new SnmpException(SnmpException.Kind.TIMEOUT, "No response from " + profile.host() + ":"
                    + profile.port() + " within " + profile.timeoutMillis() + " ms (" + (profile.retries() + 1)
                    + (profile.retries() == 0 ? " attempt)" : " attempts)") + timeoutHint());
        }
        PDU response = event.getResponse();
        if (response.getType() == PDU.REPORT) {
            String oid = response.size() > 0 ? response.get(0).getOid().toDottedString() : "";
            throw new SnmpException(SnmpException.Kind.REPORT, "Agent reported "
                    + USM_REPORTS.getOrDefault(oid, "an error (" + oid + ")"));
        }
        if (response.getErrorStatus() != PDU.noError) {
            int index = response.getErrorIndex();
            String detail = index > 0 && index <= request.size()
                    ? Variables.fromOid(request.get(index - 1).getOid()).toString() : "";
            throw SnmpException.errorStatus(response.getErrorStatus(), response.getErrorStatusText(), index, detail);
        }
        return response;
    }

    private String timeoutHint() {
        if (profile.version() == SnmpVersion.V2C) {
            return "; check host, port and community";
        }
        // Agents may drop requests with a wrong digest, or answer with a report the client cannot verify.
        return profile.v3().level().authenticated()
                ? "; check host, port, user, protocols and passphrases"
                : "; check host, port and user";
    }

    private static String describe(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static List<VarBindResult> results(PDU response) {
        List<VarBindResult> results = new ArrayList<>(response.size());
        for (VariableBinding vb : response.getVariableBindings()) {
            results.add(new VarBindResult(Variables.fromOid(vb.getOid()), Variables.fromVariable(vb.getVariable())));
        }
        return results;
    }

    private static Target<UdpAddress> communityTarget(DeviceProfile p, UdpAddress address, String community) {
        CommunityTarget<UdpAddress> target = new CommunityTarget<>(address, new OctetString(community));
        target.setVersion(SnmpConstants.version2c);
        target.setTimeout(p.timeoutMillis());
        target.setRetries(p.retries());
        return target;
    }

    private static Target<UdpAddress> userTarget(DeviceProfile p, UdpAddress address) {
        UserTarget<UdpAddress> target = new UserTarget<>();
        target.setAddress(address);
        target.setSecurityName(new OctetString(p.v3().user()));
        target.setSecurityLevel(switch (p.v3().level()) {
            case NO_AUTH_NO_PRIV -> SecurityLevel.NOAUTH_NOPRIV;
            case AUTH_NO_PRIV -> SecurityLevel.AUTH_NOPRIV;
            case AUTH_PRIV -> SecurityLevel.AUTH_PRIV;
        });
        target.setVersion(SnmpConstants.version3);
        target.setTimeout(p.timeoutMillis());
        target.setRetries(p.retries());
        return target;
    }

    static OID authProtocol(V3Security.AuthProtocol protocol) {
        return switch (protocol) {
            case MD5 -> AuthMD5.ID;
            case SHA1 -> AuthSHA.ID;
            case SHA224 -> AuthHMAC128SHA224.ID;
            case SHA256 -> AuthHMAC192SHA256.ID;
            case SHA384 -> AuthHMAC256SHA384.ID;
            case SHA512 -> AuthHMAC384SHA512.ID;
        };
    }

    static OID privProtocol(V3Security.PrivProtocol protocol) {
        return switch (protocol) {
            case DES -> PrivDES.ID;
            case DES3 -> Priv3DES.ID;
            case AES128 -> PrivAES128.ID;
            case AES192 -> PrivAES192.ID;
            case AES256 -> PrivAES256.ID;
        };
    }
}
