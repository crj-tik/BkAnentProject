package com.bkanent.agent.studio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Component
public class StudioJson {
    private final ObjectMapper mapper;
    public StudioJson(ObjectMapper mapper) { this.mapper = mapper; }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw StudioException.invalid("资料无法序列化"); }
    }
    public Map<String, Object> read(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try { return mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception ex) { throw StudioException.unavailable("保存的资料格式暂时无法读取"); }
    }
    public Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    public List<Map<String, Object>> objects(Object value) {
        return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance).map(this::object).toList() : List.of();
    }
    public String text(Object value) { return value == null ? "" : String.valueOf(value); }
    public String required(Object value, String name, int max) {
        if (!(value instanceof String string) || string.isBlank() || string.length() > max)
            throw StudioException.invalid(name + "缺失或超出允许长度");
        return string.trim();
    }
    public long number(Object value) {
        if (value instanceof Number number) {
            try { return new java.math.BigDecimal(number.toString()).longValueExact(); }
            catch (ArithmeticException | NumberFormatException exception) { throw StudioException.invalid("修订号必须是整数"); }
        }
        try { return Long.parseLong(text(value)); } catch (Exception ex) { throw StudioException.invalid("修订号必须是整数"); }
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(canonical(value)).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("hash unavailable", ex); }
    }
    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) { Map<String, Object> sorted = new TreeMap<>(); map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item))); return sorted; }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }
    public String id() { return UUID.randomUUID().toString(); }
    public String instant(long milliseconds) { return Instant.ofEpochMilli(milliseconds).toString(); }
    public Map<String, Object> context(Object value) {
        Map<String, Object> context = object(value);
        if (!Set.of("refs", "criteria").containsAll(context.keySet())) throw StudioException.invalid("工作条件包含不支持的字段");
        List<Map<String, Object>> refs = objects(context.get("refs"));
        if (context.containsKey("refs") && (!(context.get("refs") instanceof List<?> list) || list.size() != refs.size())) throw StudioException.invalid("引用格式无效");
        if (refs.size() > 100) throw StudioException.invalid("引用对象过多");
        List<Map<String, Object>> safeRefs = new ArrayList<>();
        for (Map<String, Object> ref : refs) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("type", required(ref.get("type"), "对象类型", 64)); safe.put("id", required(ref.get("id"), "对象编号", 128));
            for (String key : List.of("label", "sourceRevision")) if (ref.get(key) instanceof String s && s.length() <= 500) safe.put(key, s);
            safeRefs.add(safe);
        }
        Map<String, Object> criteria = object(context.get("criteria"));
        for (String key : List.of("region", "areaCode", "platform", "period", "scopeLabel", "requirements")) {
            Object item = criteria.get(key);
            if (item != null && (!(item instanceof String text) || text.length() > 8000)) throw StudioException.invalid("需求条件格式无效");
        }
        if (criteria.get("bedrooms") != null && (number(criteria.get("bedrooms")) < 0 || number(criteria.get("bedrooms")) > 30)) throw StudioException.invalid("户型条件无效");
        if (!Set.of("region", "areaCode", "budget", "bedrooms", "platform", "period", "scopeLabel", "requirements").containsAll(criteria.keySet()))
            throw StudioException.invalid("需求条件包含不支持的字段");
        Object budget = criteria.get("budget");
        if (budget != null) {
            Map<String, Object> money = object(budget);
            try { if (!"CNY".equals(money.get("currency")) || new java.math.BigDecimal(required(money.get("amount"), "预算", 32)).signum() <= 0) throw new IllegalArgumentException(); }
            catch (Exception ex) { throw StudioException.invalid("预算必须为明确的人民币元金额"); }
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("refs", safeRefs); result.put("criteria", criteria);
        if (write(result).length() > 32768) throw StudioException.invalid("需求资料过长");
        return result;
    }
    public Map<String, Object> page(List<?> items, String nextCursor) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("items", items); result.put("nextCursor", nextCursor); return result;
    }
}
