package com.biligrab.downloader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * {@code org.json} 的薄封装。
 *
 * <p>Android 自带的 {@code org.json} 抛受检的 {@link JSONException}，
 * 会让每一层调用都要声明两种异常。这里统一转换成 {@link IOException}，
 * 上层只需要处理一种。</p>
 */
public final class Json {

    private Json() {
    }

    public static JSONObject parse(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("接口返回的不是合法 JSON：" + snippet(body), e);
        }
    }

    public static JSONObject obj(JSONObject parent, String key) throws IOException {
        try {
            return parent.getJSONObject(key);
        } catch (JSONException e) {
            throw new IOException("接口响应缺少字段 \"" + key + "\"", e);
        }
    }

    public static JSONObject obj(JSONArray array, int index) throws IOException {
        try {
            return array.getJSONObject(index);
        } catch (JSONException e) {
            throw new IOException("数组下标 " + index + " 不是对象", e);
        }
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
