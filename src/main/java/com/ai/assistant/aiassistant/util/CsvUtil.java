package com.ai.assistant.aiassistant.util;

import java.util.ArrayList;
import java.util.List;

/** Shared CSV helpers. Handles quoted fields, commas inside quotes and escaped quotes (""). */
public final class CsvUtil {

    private CsvUtil() {}

    public static String[] parseLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                tokens.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        tokens.add(current.toString().trim());
        return tokens.toArray(new String[0]);
    }
}