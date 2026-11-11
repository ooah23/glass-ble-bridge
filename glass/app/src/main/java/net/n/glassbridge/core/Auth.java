package net.n.glassbridge.core;

import java.nio.charset.Charset;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Challenge response: h = hex(HMAC-SHA256(key=secret UTF-8, msg=nonce string UTF-8)). */
public final class Auth {
    private Auth() {}
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static String response(String secret, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF8), "HmacSHA256"));
            byte[] d = mac.doFinal(nonce.getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
