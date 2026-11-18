package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅供纯 JVM 单元测试使用的轻量 JSON 解析/序列化器：
 * Android SDK 的 android.jar 中 org.json 为空壳方法，放在 src/test 可在不引入任何第三方依赖的前提下运行 JSON 单测。
 */
final class JsonCodec {
    private final String src;
    private int pos;

    private JsonCodec(String src) {
        this.src = src;
    }

    static Map<String, Object> parseObject(String raw) throws JSONException {
        JsonCodec c = new JsonCodec(raw);
        Object v = c.readValue();
        c.skipWs();
        if (c.pos != c.src.length() || !(v instanceof JSONObject)) {
            throw new JSONException("Expected JSONObject");
        }
        return ((JSONObject) v).map;
    }

    static List<Object> parseArray(String raw) throws JSONException {
        JsonCodec c = new JsonCodec(raw);
        Object v = c.readValue();
        c.skipWs();
        if (c.pos != c.src.length() || !(v instanceof JSONArray)) {
            throw new JSONException("Expected JSONArray");
        }
        return ((JSONArray) v).list;
    }

    private void skipWs() {
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') pos++;
            else break;
        }
    }

    private Object readValue() throws JSONException {
        skipWs();
        if (pos >= src.length()) throw new JSONException("Unexpected EOF");
        char ch = src.charAt(pos);
        if (ch == '{') return readObject();
        if (ch == '[') return readArray();
        if (ch == '"') return readString();
        if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        if (src.startsWith("null", pos)) { pos += 4; return JSONObject.NULL; }
        return readNumber();
    }

    private JSONObject readObject() throws JSONException {
        pos++; // '{'
        Map<String, Object> out = new LinkedHashMap<>();
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return new JSONObject(out, true);
        }
        while (true) {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != '"') throw new JSONException("Expected key string");
            String key = readString();
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != ':') throw new JSONException("Expected ':'");
            pos++;
            Object val = readValue();
            out.put(key, val);
            skipWs();
            if (pos >= src.length()) throw new JSONException("Unterminated object");
            char next = src.charAt(pos++);
            if (next == '}') break;
            if (next != ',') throw new JSONException("Expected ',' or '}'");
        }
        return new JSONObject(out, true);
    }

    private JSONArray readArray() throws JSONException {
        pos++; // '['
        List<Object> out = new ArrayList<>();
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return new JSONArray(out, true);
        }
        while (true) {
            out.add(readValue());
            skipWs();
            if (pos >= src.length()) throw new JSONException("Unterminated array");
            char next = src.charAt(pos++);
            if (next == ']') break;
            if (next != ',') throw new JSONException("Expected ',' or ']'");
        }
        return new JSONArray(out, true);
    }

    private String readString() throws JSONException {
        pos++; // '"'
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char ch = src.charAt(pos++);
            if (ch == '"') return sb.toString();
            if (ch == '\\') {
                if (pos >= src.length()) throw new JSONException("Unterminated escape");
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) throw new JSONException("Bad unicode escape");
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default:
                        sb.append(esc);
                        break;
                }
            } else {
                sb.append(ch);
            }
        }
        throw new JSONException("Unterminated string");
    }

    private Number readNumber() throws JSONException {
        int start = pos;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if ((ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') pos++;
            else break;
        }
        String token = src.substring(start, pos);
        if (token.isEmpty()) throw new JSONException("Unexpected token");
        try {
            if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                return Double.parseDouble(token);
            }
            long l = Long.parseLong(token);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
            return l;
        } catch (NumberFormatException e) {
            throw new JSONException("Invalid number: " + token, e);
        }
    }

    static Object wrap(Object value) {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof JSONObject || value instanceof JSONArray ||
            value instanceof Boolean || value instanceof Number || value instanceof String) {
            return value;
        }
        if (value instanceof java.util.Collection) {
            return new JSONArray((java.util.Collection<?>) value);
        }
        if (value instanceof Map) {
            return new JSONObject((Map<?, ?>) value);
        }
        return value.toString();
    }

    static void writeValue(StringBuilder sb, Object v) {
        if (v == null || v == JSONObject.NULL) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Number || v instanceof JSONObject || v instanceof JSONArray) {
            sb.append(v.toString());
        } else {
            writeString(sb, v.toString());
        }
    }

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                    break;
            }
        }
        sb.append('"');
    }
}
