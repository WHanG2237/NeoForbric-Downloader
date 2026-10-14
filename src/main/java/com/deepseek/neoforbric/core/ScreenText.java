package com.deepseek.neoforbric.core;

import java.util.LinkedHashMap;
import java.util.Map;


public final class ScreenText {

    private static final Map<String, String> DICTIONARY = new LinkedHashMap<>();

    static {
        DICTIONARY.put("信雅互联", "Sinytra Connector");
        DICTIONARY.put("前置", "FFAPI");
        DICTIONARY.put("万能转译加载器", "NeoForbric");
        DICTIONARY.put("正在翻译", "Translating");
        DICTIONARY.put("正在下载", "Translating");
        DICTIONARY.put("官方源", "official");
        DICTIONARY.put("镜像源", "mirror");
        DICTIONARY.put("模组", "mod");
        DICTIONARY.put("进度", "progress");
    }

    private ScreenText() {}


    public static String ascii(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = text;
        for (Map.Entry<String, String> entry : DICTIONARY.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue());
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(c >= 32 && c <= 126 ? c : ' ');
        }
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }
}
