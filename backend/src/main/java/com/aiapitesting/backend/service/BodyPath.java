package com.aiapitesting.backend.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Đường dẫn tới field trong body JSON: {@code a} (cấp gốc), {@code a.b} (object lồng), {@code items[0].sku} (phần tử đầu của mảng object).
 * Khoá phẳng có sẵn trong map (kể cả khi tên chứa dấu chấm) luôn được ưu tiên để spec phẳng giữ nguyên hành vi.
 */
final class BodyPath {

    private BodyPath() {
    }

    static boolean isNested(String path) {
        return path != null && (path.indexOf('.') >= 0 || path.indexOf('[') >= 0);
    }

    /** Cha của path lồng ({@code a.b -> a}, {@code items[0].sku -> items[0]}, {@code items[0] -> items}); null nếu ở cấp gốc. */
    static String parentPath(String path) {
        if (path == null) {
            return null;
        }
        if (path.endsWith("[0]")) {
            return path.substring(0, path.length() - 3);
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? null : path.substring(0, dot);
    }

    static boolean contains(Map<String, Object> root, String path) {
        if (path != null && path.endsWith("[0]") && !root.containsKey(path)) {
            return get(root, path) != null;
        }
        Map<String, Object> holder = holderOf(root, path, false);
        return holder != null && holder.containsKey(lastKey(path, root));
    }

    @SuppressWarnings("unchecked")
    static Object get(Map<String, Object> root, String path) {
        if (path != null && path.endsWith("[0]") && !root.containsKey(path)) {
            Object list = get(root, path.substring(0, path.length() - 3));
            return list instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map ? l.get(0) : null;
        }
        Map<String, Object> holder = holderOf(root, path, false);
        return holder == null ? null : holder.get(lastKey(path, root));
    }

    /** Gán giá trị vào object cha ĐÃ CÓ. Trả false nếu không gán được (cha vắng/khác kiểu) - không tự dựng cha để khỏi sinh body sai cấu trúc. */
    static boolean set(Map<String, Object> root, String path, Object value) {
        Map<String, Object> holder = holderOf(root, path, true);
        if (holder == null) {
            return false;
        }
        holder.put(lastKey(path, root), value);
        return true;
    }

    static void remove(Map<String, Object> root, String path) {
        Map<String, Object> holder = holderOf(root, path, false);
        if (holder != null) {
            holder.remove(lastKey(path, root));
        }
    }

    private static String lastKey(String path, Map<String, Object> root) {
        if (root.containsKey(path) || !isNested(path)) {
            return path;
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /** Map chứa khoá cuối của path; null nếu đường đi không tồn tại. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> holderOf(Map<String, Object> root, String path, boolean create) {
        if (root == null || path == null || path.isEmpty()) {
            return null;
        }
        if (root.containsKey(path) || !isNested(path)) {
            return root;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return null; // dạng "items[0]" đứng một mình: không có khoá để gán
        }
        Map<String, Object> current = root;
        for (String token : path.substring(0, dot).split("[.]")) {
            boolean first = token.endsWith("[0]");
            String key = first ? token.substring(0, token.length() - 3) : token;
            Object next = current.get(key);
            if (first) {
                next = next instanceof List<?> list && !list.isEmpty() ? list.get(0) : null;
            }
            if (!(next instanceof Map)) {
                return null;
            }
            current = (Map<String, Object>) next;
        }
        return current;
    }
}
