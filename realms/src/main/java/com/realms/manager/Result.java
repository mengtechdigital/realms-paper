package com.realms.manager;

import java.util.Map;

/**
 * Outcome of a manager operation. {@code messageKey} is the dotted path into
 * messages.yml (e.g. "errors.not-mayor", "info.realm-created"). The command
 * layer renders the template with {@code placeholders}.
 */
public record Result(boolean ok, String messageKey, Map<String, String> placeholders) {

    private static final Map<String, String> EMPTY = Map.of();

    public static Result ok(String key) { return new Result(true, key, EMPTY); }
    public static Result ok(String key, Map<String, String> ph) { return new Result(true, key, ph); }
    public static Result fail(String key) { return new Result(false, key, EMPTY); }
    public static Result fail(String key, Map<String, String> ph) { return new Result(false, key, ph); }
}
