package dev.aura.punishments.config;

import java.util.List;

/** Thrown when a configuration file fails validation; carries every problem found. */
public final class ConfigException extends Exception {
    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
