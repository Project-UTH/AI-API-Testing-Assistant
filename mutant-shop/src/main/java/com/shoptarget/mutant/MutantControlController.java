package com.shoptarget.mutant;

import com.shoptarget.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Điều khiển bộ lỗi cho harness đo. Chỉ tồn tại khi MUTANT_CONTROL=true; không có trong spec công khai (hidden). */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mutants.control", havingValue = "true")
@io.swagger.v3.oas.annotations.Hidden
public class MutantControlController {

    private final ProductRepository productRepository;

    @GetMapping("/__mutants")
    public Map<String, Object> catalog() {
        return Map.of("catalog", ShopMutants.CATALOG, "enabled", ShopMutants.enabled());
    }

    @PostMapping("/__mutants")
    public Map<String, Object> enable(@RequestBody Map<String, List<String>> body) {
        ShopMutants.set(body.getOrDefault("enabled", List.of()));
        return Map.of("enabled", ShopMutants.enabled());
    }

    /** Về bản chuẩn: tắt mọi mutant và xoá dữ liệu sản phẩm để mỗi phép đo bắt đầu từ trạng thái sạch. */
    @PostMapping("/__reset")
    public Map<String, Object> reset() {
        ShopMutants.set(List.of());
        productRepository.deleteAll();
        return Map.of("reset", true);
    }
}
