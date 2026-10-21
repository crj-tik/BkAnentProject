package com.bkanent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable deployment-owned dimensions; the model cannot register dimensions or subjects. */
@Component
@ConfigurationProperties("memory.v2.preference")
public class MemoryPreferenceProperties {
    private Set<String> dimensions = Set.of("budget", "commute_time", "floor", "copy_length", "bedrooms", "area", "location", "style");
    private Map<String, List<String>> aliases = defaults();
    private int halfLifeDays = 180;
    public Set<String> getDimensions() { return dimensions; }
    public void setDimensions(Set<String> dimensions) {
        Set<String> copy = dimensions == null ? Set.of() : Set.copyOf(dimensions);
        if (copy.size() > 32 || copy.stream().anyMatch(id -> id == null || !id.matches("[a-z][a-z0-9_]{0,63}"))) {
            throw new IllegalArgumentException("memory.v2.preference.dimensions must contain at most 32 stable lowercase ids");
        }
        this.dimensions = copy;
    }
    public Map<String, List<String>> getAliases() { return aliases; }
    public void setAliases(Map<String, List<String>> value) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (value != null) value.forEach((dimension, terms) -> {
            if (dimension == null || !dimension.matches("[a-z][a-z0-9_]{0,63}") || terms == null || terms.isEmpty()
                    || terms.stream().anyMatch(term -> term == null || term.isBlank() || term.length() > 128)) {
                throw new IllegalArgumentException("invalid memory.v2.preference.aliases entry");
            }
            copy.put(dimension, List.copyOf(terms));
        });
        aliases = Map.copyOf(copy);
    }
    public List<String> aliasesFor(String dimension) {
        List<String> registered = aliases.get(dimension);
        return registered == null || registered.isEmpty() ? List.of(dimension.replace('_', ' '), dimension) : registered;
    }
    public int getHalfLifeDays() { return halfLifeDays; }
    public void setHalfLifeDays(int value) {
        if (value < 1 || value > 36500) throw new IllegalArgumentException("memory.v2.preference.half-life-days must be 1..36500");
        halfLifeDays = value;
    }

    private static Map<String, List<String>> defaults() {
        return Map.of(
                "budget", List.of("budget", "price", "price range", "purchase price", "total price", "预算", "总价", "价格", "首付"),
                "commute_time", List.of("commute", "commute time", "transport", "subway", "通勤", "交通", "地铁", "公交", "距离"),
                "floor", List.of("floor", "elevator", "楼层", "电梯", "低楼层", "高楼层"),
                "copy_length", List.of("copy length", "description length", "文案长度", "篇幅"),
                "bedrooms", List.of("bedrooms", "bedroom", "rooms", "户型", "卧室", "几室"),
                "area", List.of("area", "floor area", "size", "面积", "平米", "平方米"),
                "location", List.of("location", "district", "neighborhood", "地段", "区域", "位置", "商圈"),
                "style", List.of("style", "decoration", "装修风格", "风格", "装修"));
    }
}
