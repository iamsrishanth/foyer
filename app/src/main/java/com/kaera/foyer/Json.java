package com.kaera.foyer;

/** Tiny JSON helpers; no external dependencies on purpose. */
public final class Json {
    private Json() {
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    /** Reads a flat string field out of a small JSON object. */
    public static String field(String json, String key) {
        if (json == null || key == null) return null;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int c = json.indexOf(':', i + pat.length());
        if (c < 0) return null;
        int q = json.indexOf('"', c + 1);
        if (q < 0) return null;
        StringBuilder b = new StringBuilder();
        for (int k = q + 1; k < json.length(); k++) {
            char ch = json.charAt(k);
            if (ch == '\\' && k + 1 < json.length()) {
                char n = json.charAt(++k);
                if (n == 'n') b.append('\n');
                else if (n == 't') b.append('\t');
                else b.append(n);
                continue;
            }
            if (ch == '"') break;
            b.append(ch);
        }
        return b.toString();
    }
}
