package com.bkanent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Set;

/** Stable deployment-owned dimensions; the model cannot register dimensions or subjects. */
@Component
@ConfigurationProperties("memory.v2.preference")
public class MemoryPreferenceProperties {
    private Set<String> dimensions = Set.of("budget", "commute_time", "floor", "copy_length", "bedrooms", "area", "location", "style");
    public Set<String> getDimensions() { return dimensions; }
    public void setDimensions(Set<String> dimensions) { this.dimensions = dimensions == null ? Set.of() : Set.copyOf(dimensions); }
}
