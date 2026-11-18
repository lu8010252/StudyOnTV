package org.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class JSONArray {
    final List<Object> list;

    public JSONArray() {
        this.list = new ArrayList<>();
    }

    JSONArray(List<Object> list, boolean direct) {
        this.list = list;
    }

    public JSONArray(Collection copyFrom) {
        this.list = new ArrayList<>();
        if (copyFrom != null) {
            for (Object o : copyFrom) {
                this.list.add(JsonCodec.wrap(o));
            }
        }
    }

    public JSONArray(String json) throws JSONException {
        if (json == null) throw new JSONException("null json");
        this.list = JsonCodec.parseArray(json);
    }

    public int length() { return list.size(); }

    public JSONArray put(boolean value) { list.add(value); return this; }
    public JSONArray put(double value) throws JSONException {
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new JSONException("Non-finite double");
        list.add(value);
        return this;
    }
    public JSONArray put(int value) { list.add(value); return this; }
    public JSONArray put(long value) { list.add(value); return this; }
    public JSONArray put(Object value) { list.add(JsonCodec.wrap(value)); return this; }

    public Object opt(int index) {
        return (index < 0 || index >= list.size()) ? null : list.get(index);
    }

    public Object get(int index) throws JSONException {
        Object v = opt(index);
        if (v == null) throw new JSONException("Index out of bounds: " + index);
        return v;
    }

    public String getString(int index) throws JSONException {
        Object v = get(index);
        if (v == JSONObject.NULL) throw new JSONException("Null at " + index);
        return String.valueOf(v);
    }

    public String optString(int index) { return optString(index, ""); }
    public String optString(int index, String fallback) {
        Object v = opt(index);
        return (v == null || v == JSONObject.NULL) ? fallback : String.valueOf(v);
    }

    public int getInt(int index) throws JSONException {
        Object v = get(index);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new JSONException("Not an int at " + index, e); }
    }

    public int optInt(int index) { return optInt(index, 0); }
    public int optInt(int index, int fallback) {
        try { return (index >= 0 && index < list.size()) ? getInt(index) : fallback; }
        catch (JSONException e) { return fallback; }
    }

    public JSONObject getJSONObject(int index) throws JSONException {
        Object v = get(index);
        if (v instanceof JSONObject) return (JSONObject) v;
        throw new JSONException("Not a JSONObject at " + index);
    }

    public JSONObject optJSONObject(int index) {
        Object v = opt(index);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONArray getJSONArray(int index) throws JSONException {
        Object v = get(index);
        if (v instanceof JSONArray) return (JSONArray) v;
        throw new JSONException("Not a JSONArray at " + index);
    }

    public JSONArray optJSONArray(int index) {
        Object v = opt(index);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            JsonCodec.writeValue(sb, list.get(i));
        }
        sb.append(']');
        return sb.toString();
    }
}
