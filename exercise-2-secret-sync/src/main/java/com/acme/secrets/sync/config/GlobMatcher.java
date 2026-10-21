package com.acme.secrets.sync.config;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles sync-policy source patterns such as {@code prod/payments/*} into predicates over vault secret ids.
 * The pattern syntax is documented in the README.
 */
public final class GlobMatcher {

    private static final Pattern WILDCARD = Pattern.compile("\\*\\*|[*?]");

    private GlobMatcher() {
    }

    public static Predicate<String> compile(String glob) {
        Pattern pattern = Pattern.compile(toRegex(glob));
        return id -> pattern.matcher(id).matches();
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        Matcher wildcards = WILDCARD.matcher(glob);
        int literalStart = 0;
        while (wildcards.find()) {
            appendLiteral(regex, glob.substring(literalStart, wildcards.start()));
            regex.append(switch (wildcards.group()) {
                case "**" -> ".*";
                case "*" -> "[^/]*";
                default -> "[^/]";
            });
            literalStart = wildcards.end();
        }
        appendLiteral(regex, glob.substring(literalStart));
        return regex.toString();
    }

    private static void appendLiteral(StringBuilder regex, String literal) {
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal));
        }
    }
}
