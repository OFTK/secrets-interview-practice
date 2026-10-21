package com.acme.secrets.sync.config;

import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Compiles sync-policy source patterns such as {@code prod/payments/*} into predicates over vault secret ids.
 * The pattern syntax is documented in the README.
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    public static Predicate<String> compile(String glob) {
        Pattern pattern = Pattern.compile(glob.replace("*", ".*"));
        return id -> pattern.matcher(id).find();
    }
}
