package com.acme.secrets.sync.target;

import java.util.regex.Pattern;

/** Maps vault secret ids to names that cloud secret stores accept. */
public final class TargetNameMapper {

    private static final Pattern INVALID_NAME_CHARACTER = Pattern.compile("[^A-Za-z0-9/_+=.@-]");

    private TargetNameMapper() {
    }

    /** Replaces every character outside {@code [A-Za-z0-9/_+=.@-]} with {@code _}. */
    public static String toTargetName(String sourceId) {
        return INVALID_NAME_CHARACTER.matcher(sourceId).replaceAll("_");
    }
}
