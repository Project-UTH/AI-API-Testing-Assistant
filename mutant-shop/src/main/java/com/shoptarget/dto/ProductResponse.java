package com.shoptarget.dto;

import com.shoptarget.entity.Product;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer stock,
        Instant createdAt
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStock(), product.getCreatedAt());
    }
}
