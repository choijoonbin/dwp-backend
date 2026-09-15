import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** Generates local-only, disjoint RS256 JWKs for devctl-managed runtimes. */
public final class DevRuntimeJwkGenerator {
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private DevRuntimeJwkGenerator() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("At least one key id is required");
        }
        Set<String> keyIds = new HashSet<>();
        for (String keyId : arguments) {
            if (!keyId.matches("[A-Za-z0-9_:-]{1,80}") || !keyIds.add(keyId)) {
                throw new IllegalArgumentException("Key ids must be unique and canonical");
            }
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        StringBuilder output = new StringBuilder("{\"version\":1,\"keys\":{");
        for (int index = 0; index < arguments.length; index++) {
            String keyId = arguments[index];
            var pair = generator.generateKeyPair();
            var publicKey = (RSAPublicKey) pair.getPublic();
            var privateKey = (RSAPrivateCrtKey) pair.getPrivate();
            if (index > 0) {
                output.append(',');
            }
            output.append('"').append(keyId).append("\":{\"private\":")
                    .append(privateJwk(keyId, privateKey))
                    .append(",\"public\":")
                    .append(publicJwk(keyId, publicKey))
                    .append('}');
        }
        System.out.print(output.append("}}").toString());
    }

    private static String publicJwk(String keyId, RSAPublicKey key) {
        return "{\"kty\":\"RSA\",\"kid\":\"" + keyId
                + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + component(key.getModulus()) + "\",\"e\":\""
                + component(key.getPublicExponent()) + "\"}";
    }

    private static String privateJwk(String keyId, RSAPrivateCrtKey key) {
        return "{\"kty\":\"RSA\",\"kid\":\"" + keyId
                + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + component(key.getModulus()) + "\",\"e\":\""
                + component(key.getPublicExponent()) + "\",\"d\":\""
                + component(key.getPrivateExponent()) + "\",\"p\":\""
                + component(key.getPrimeP()) + "\",\"q\":\""
                + component(key.getPrimeQ()) + "\",\"dp\":\""
                + component(key.getPrimeExponentP()) + "\",\"dq\":\""
                + component(key.getPrimeExponentQ()) + "\",\"qi\":\""
                + component(key.getCrtCoefficient()) + "\"}";
    }

    private static String component(BigInteger value) {
        byte[] signed = value.toByteArray();
        int offset = signed.length > 1 && signed[0] == 0 ? 1 : 0;
        byte[] unsigned = new byte[signed.length - offset];
        System.arraycopy(signed, offset, unsigned, 0, unsigned.length);
        return BASE64_URL.encodeToString(unsigned);
    }
}
