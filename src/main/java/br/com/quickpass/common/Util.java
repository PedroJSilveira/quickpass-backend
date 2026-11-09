package br.com.quickpass.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Util {
    private Util() {
    }

    /** Arredonda para 2 casas (mock: valores monetários em double). */
    public static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Mapa ordenado que aceita valores nulos: {@code props("k1", v1, "k2", v2)}. */
    public static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String soDigitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }
}
