package hr.avrbanac.snmptool.core.snmp;

import java.util.HexFormat;

/**
 * SNMPv3 user-based security (USM) settings.
 *
 * @param user            security name
 * @param level           security level
 * @param authProtocol    authentication protocol (used from {@link Level#AUTH_NO_PRIV})
 * @param authPassword    authentication passphrase
 * @param privProtocol    privacy protocol (used with {@link Level#AUTH_PRIV})
 * @param privPassword    privacy passphrase
 * @param contextName     context name; empty for the default context
 * @param contextEngineId context engine ID in hex; empty to use the discovered engine ID
 */
public record V3Security(
        String user,
        Level level,
        AuthProtocol authProtocol,
        String authPassword,
        PrivProtocol privProtocol,
        String privPassword,
        String contextName,
        String contextEngineId) {

    /** USM security levels. */
    public enum Level {
        NO_AUTH_NO_PRIV("noAuthNoPriv"),
        AUTH_NO_PRIV("authNoPriv"),
        AUTH_PRIV("authPriv");

        private final String text;

        Level(String text) {
            this.text = text;
        }

        public boolean authenticated() {
            return this != NO_AUTH_NO_PRIV;
        }

        public boolean encrypted() {
            return this == AUTH_PRIV;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** Authentication protocols. */
    public enum AuthProtocol {
        MD5("MD5"),
        SHA1("SHA-1"),
        SHA224("SHA-224"),
        SHA256("SHA-256"),
        SHA384("SHA-384"),
        SHA512("SHA-512");

        private final String text;

        AuthProtocol(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** Privacy (encryption) protocols. */
    public enum PrivProtocol {
        DES("DES"),
        DES3("3DES"),
        AES128("AES-128"),
        AES192("AES-192"),
        AES256("AES-256");

        private final String text;

        PrivProtocol(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** No authentication, default context. */
    public static V3Security noAuth(String user) {
        return new V3Security(user, Level.NO_AUTH_NO_PRIV, AuthProtocol.SHA256, "", PrivProtocol.AES128, "", "", "");
    }

    /** Context engine ID as bytes; empty if not set. */
    public byte[] contextEngineIdBytes() {
        String hex = contextEngineId.strip().replaceFirst("^0[xX]", "").replace(":", "").replace(" ", "");
        return hex.isEmpty() ? new byte[0] : HexFormat.of().parseHex(hex);
    }

    /** Never includes the passphrases. */
    @Override
    public String toString() {
        return "V3Security[user=" + user + ", level=" + level + ", auth=" + authProtocol + ", priv=" + privProtocol
                + ", context=" + contextName + "]";
    }
}
