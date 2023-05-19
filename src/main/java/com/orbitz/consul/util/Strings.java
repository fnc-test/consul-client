package com.orbitz.consul.util;

public class Strings {

    private Strings() {
        // utility class
    }

    public static String trimLeadingSlash(String value) {
        return value.replaceAll("^/+", "");
    }
}
