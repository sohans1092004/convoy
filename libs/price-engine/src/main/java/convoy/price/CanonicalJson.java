package convoy.price;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON identical to price_ref.canonical: sorted keys, no whitespace, non-ASCII kept as UTF-8,
 * Python json string escaping. Values: Map, List, String, integral Number, Boolean, null.
 */
public final class CanonicalJson {
    private CanonicalJson() {}

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    public static String sha256(Object v) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(write(v).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Object v, StringBuilder sb) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean b) sb.append(b);
        else if (v instanceof Double || v instanceof Float || v instanceof java.math.BigDecimal) {
            throw new IllegalArgumentException("floating point value in canonical JSON: " + v);
        } else if (v instanceof Number n) sb.append(n.longValue());
        else if (v instanceof String s) string(s, sb);
        else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : new TreeMap<>(m).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string((String) e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                write(l.get(i), sb);
            }
            sb.append(']');
        } else throw new IllegalArgumentException("unsupported JSON value: " + v.getClass());
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
