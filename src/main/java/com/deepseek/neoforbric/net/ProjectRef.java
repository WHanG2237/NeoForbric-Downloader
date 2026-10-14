package com.deepseek.neoforbric.net;


public record ProjectRef(String id, String slug, String title, String source) {


    public String describe() {
        return title + " (" + slug + " / " + id + " @ " + source + ")";
    }
}
