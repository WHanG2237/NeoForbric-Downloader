package com.deepseek.neoforbric.net;


public record QueryResult<T>(Kind kind, T value, String detail) {

    public enum Kind {
        OK,
        NOT_FOUND,
        TEMPORARY,
        CONFIG_ERROR,
        RESTRICTED
    }

    public static <T> QueryResult<T> ok(T value) {
        return new QueryResult<>(Kind.OK, value, "");
    }

    public static <T> QueryResult<T> notFound(String detail) {
        return new QueryResult<>(Kind.NOT_FOUND, null, detail);
    }

    public static <T> QueryResult<T> temporary(String detail) {
        return new QueryResult<>(Kind.TEMPORARY, null, detail);
    }

    public static <T> QueryResult<T> configError(String detail) {
        return new QueryResult<>(Kind.CONFIG_ERROR, null, detail);
    }

    public static <T> QueryResult<T> restricted(String detail) {
        return new QueryResult<>(Kind.RESTRICTED, null, detail);
    }

    public static <R> QueryResult<R> failureOf(QueryResult<?> source) {
        return switch (source.kind()) {
            case NOT_FOUND -> notFound(source.detail());
            case CONFIG_ERROR -> configError(source.detail());
            case RESTRICTED -> restricted(source.detail());
            case TEMPORARY, OK -> temporary(source.detail());
        };
    }

    public boolean ok() {
        return kind == Kind.OK;
    }

    public boolean missing() {
        return kind == Kind.NOT_FOUND;
    }

    public boolean restricted() {
        return kind == Kind.RESTRICTED;
    }

    public boolean uncertain() {
        return kind == Kind.TEMPORARY || kind == Kind.CONFIG_ERROR || kind == Kind.RESTRICTED;
    }

    public boolean hasValue() {
        return kind == Kind.OK && value != null;
    }

    public T orElse(T fallback) {
        return value == null ? fallback : value;
    }

    public String describe() {
        return kind.name().toLowerCase(java.util.Locale.ROOT) + (detail.isBlank() ? "" : " (" + detail + ")");
    }
}
