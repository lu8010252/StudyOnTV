package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class JSONObject {
    public static final Object NULL = new Object() {
        @Override public boolean equals(Object o) { return o == this || o == null; }
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "null"; }
    };

    final Map<String, Object> map;

    public JSONObject() {
        this.map = new LinkedHashMap<>();
    }

    JSONObject(Map<String, Object> map, boolean direct) {
        this.map = map;
    }

    public JSONObject(Map copyFrom) {
        this.map = new LinkedHashMap<>();
        if (copyFrom != null) {
            for (Object eObj : copyFrom.entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) eObj;
                if (e.getKey() != null) {
                    this.map.put(String.valueOf(e.getKey()), JsonCodec.wrap(e.getValue()));
                }
            }
        }
    }

    public JSONObject(String json) throws JSONException {
        if (json == null) throw new JSONException("null json");
        this.map = JsonCodec.parseObject(json);
    }

    public int length() { return map.size(); }

    public JSONObject put(String name, boolean value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        map.put(name, value);
        return this;
    }

    public JSONObject put(String name, double value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new JSONException("Non-finite double");
        map.put(name, value);
        return this;
    }

    public JSONObject put(String name, int value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        map.put(name, value);
        return this;
    }

    public JSONObject put(String name, long value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        map.put(name, value);
        return this;
    }

    public JSONObject put(String name, Object value) throws JSONException {
        if (name == null) throw new JSONException("Null key");
        if (value == null) map.remove(name);
        else map.put(name, JsonCodec.wrap(value));
        return this;
    }

    public Object remove(String name) { return map.remove(name); }
    public boolean has(String name) { return map.containsKey(name); }
    public boolean isNull(String name) {
        Object v = map.get(name);
        return v == null || v == NULL;
    }

    public Object opt(String name) { return name == null ? null : map.get(name); }

    public Object get(String name) throws JSONException {
        Object v = opt(name);
        if (v == null) throw new JSONException("No value for " + name);
        return v;
    }

    public String getString(String name) throws JSONException {
        Object v = get(name);
        if (v == NULL) throw new JSONException("Null value for " + name);
        return String.valueOf(v);
    }

    public String optString(String name) { return optString(name, ""); }
    public String optString(String name, String fallback) {
        Object v = opt(name);
        return (v == null || v == NULL) ? fallback : String.valueOf(v);
    }

    public int getInt(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new JSONException("Not an int: " + name, e); }
    }

    public int optInt(String name) { return optInt(name, 0); }
    public int optInt(String name, int fallback) {
        try { return has(name) && !isNull(name) ? getInt(name) : fallback; }
        catch (JSONException e) { return fallback; }
    }

    public long getLong(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new JSONException("Not a long: " + name, e); }
    }

    public long optLong(String name) { return optLong(name, 0L); }
    public long optLong(String name, long fallback) {
        try { return has(name) && !isNull(name) ? getLong(name) : fallback; }
        catch (JSONException e) { return fallback; }
    }

    public double getDouble(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new JSONException("Not a double: " + name, e); }
    }

    public double optDouble(String name) { return optDouble(name, Double.NaN); }
    public double optDouble(String name, double fallback) {
        try { return has(name) && !isNull(name) ? getDouble(name) : fallback; }
        catch (JSONException e) { return fallback; }
    }

    public boolean getBoolean(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof Boolean) return (Boolean) v;
        if ("true".equalsIgnoreCase(String.valueOf(v))) return true;
        if ("false".equalsIgnoreCase(String.valueOf(v))) return false;
        throw new JSONException("Not a boolean: " + name);
    }

    public boolean optBoolean(String name) { return optBoolean(name, false); }
    public boolean optBoolean(String name, boolean fallback) {
        try { return has(name) && !isNull(name) ? getBoolean(name) : fallback; }
        catch (JSONException e) { return fallback; }
    }

    public JSONArray getJSONArray(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof JSONArray) return (JSONArray) v;
        throw new JSONException("Not a JSONArray: " + name);
    }

    public JSONArray optJSONArray(String name) {
        Object v = opt(name);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    public JSONObject getJSONObject(String name) throws JSONException {
        Object v = get(name);
        if (v instanceof JSONObject) return (JSONObject) v;
        throw new JSONException("Not a JSONObject: " + name);
    }

    public JSONObject optJSONObject(String name) {
        Object v = opt(name);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public Iterator<String> keys() { return map.keySet().iterator(); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            JsonCodec.writeString(sb, e.getKey());
            sb.append(':');
            JsonCodec.writeValue(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }
}
