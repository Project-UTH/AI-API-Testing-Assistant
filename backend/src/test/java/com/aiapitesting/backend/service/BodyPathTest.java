package com.aiapitesting.backend.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BodyPathTest {

    private Map<String, Object> body() {
        Map<String, Object> salary = new LinkedHashMap<>(Map.of("min", 10));
        Map<String, Object> item = new LinkedHashMap<>(Map.of("sku", "A1"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", "x");
        root.put("salary", salary);
        root.put("items", List.of(item));
        root.put("a.b", "flat");
        return root;
    }

    @Test
    void getAndContains_acrossFlatNestedAndArrayPaths() {
        Map<String, Object> root = body();
        assertThat(BodyPath.get(root, "title")).isEqualTo("x");
        assertThat(BodyPath.get(root, "salary.min")).isEqualTo(10);
        assertThat(BodyPath.get(root, "items[0].sku")).isEqualTo("A1");
        assertThat(BodyPath.contains(root, "items[0]")).isTrue();
        assertThat(BodyPath.contains(root, "salary.max")).isFalse();
        assertThat(BodyPath.contains(root, "missing.x")).isFalse();
        assertThat(BodyPath.get(root, "a.b")).isEqualTo("flat"); // khoá phẳng có dấu chấm được ưu tiên
    }

    @Test
    void setAndRemove_nested() {
        Map<String, Object> root = body();
        assertThat(BodyPath.set(root, "salary.max", 20)).isTrue();
        assertThat(BodyPath.get(root, "salary.max")).isEqualTo(20);
        assertThat(BodyPath.set(root, "missing.x", 1)).isFalse(); // không tự dựng cha còn thiếu (tránh body sai cấu trúc)
        assertThat(BodyPath.contains(root, "missing")).isFalse();
        assertThat(BodyPath.set(root, "title.x", 1)).isFalse(); // cha không phải object
        BodyPath.remove(root, "salary.min");
        assertThat(BodyPath.contains(root, "salary.min")).isFalse();
        assertThat(BodyPath.set(new LinkedHashMap<>(), "items[0].sku", "z")).isFalse(); // không tự tạo mảng
    }

    @Test
    void parentPath() {
        assertThat(BodyPath.parentPath("a.b")).isEqualTo("a");
        assertThat(BodyPath.parentPath("items[0].sku")).isEqualTo("items[0]");
        assertThat(BodyPath.parentPath("items[0]")).isEqualTo("items");
        assertThat(BodyPath.parentPath("a")).isNull();
    }
}
