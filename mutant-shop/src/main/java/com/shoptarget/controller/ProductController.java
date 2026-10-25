package com.shoptarget.controller;

import com.shoptarget.dto.ProductRequest;
import com.shoptarget.dto.ProductResponse;
import com.shoptarget.entity.Product;
import com.shoptarget.exception.ProductNotFoundException;
import com.shoptarget.exception.ValidationFailedException;
import com.shoptarget.mutant.ShopMutants;
import com.shoptarget.repository.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * BẢN SAO có lỗi cài sẵn của shop-api-target (xem {@link ShopMutants}). Khi không bật mutant nào, hành vi giống bản gốc: cùng đường dẫn,
 * cùng mã trạng thái, cùng ràng buộc. Validation chạy thủ công (thay cho @Valid) để mutant S01-S04 tắt được từng ràng buộc.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;
    private final Validator validator;

    /** Bỏ qua vi phạm ở các field cho phép (mutant); vi phạm còn lại → 400. */
    private void validate(ProductRequest request, Set<String> ignoredFields) {
        for (ConstraintViolation<ProductRequest> v : validator.validate(request)) {
            String field = v.getPropertyPath().toString();
            if (!ignoredFields.contains(field)) {
                throw new ValidationFailedException(field + ": " + v.getMessage());
            }
        }
    }

    @Operation(summary = "Tao san pham moi - id duoc backend tu sinh")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public ResponseEntity<ProductResponse> create(@RequestBody ProductRequest request) {
        java.util.Set<String> ignored = new java.util.HashSet<>();
        if (ShopMutants.on("S01")) {
            ignored.add("price");
        }
        if (ShopMutants.on("S02")) {
            ignored.add("name");
        }
        if (ShopMutants.on("S03")) {
            ignored.add("stock");
        }
        validate(request, ignored);
        Product product = Product.builder()
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .stock(request.stock())
                .build();
        Product saved = productRepository.save(product);
        HttpStatus status = ShopMutants.on("S08") ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ProductResponse.from(saved));
    }

    @Operation(summary = "Lay san pham theo id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tim thay san pham"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay san pham voi id da cho")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getById(@PathVariable Long id) {
        if (ShopMutants.on("S05") && productRepository.findById(id).isEmpty()) {
            return ResponseEntity.ok(new ProductResponse(null, null, null, null, null, null));
        }
        return ResponseEntity.ok(ProductResponse.from(findOrThrow(id)));
    }

    @Operation(summary = "Danh sach san pham, loc duoc theo ten va gia toi thieu (query param)")
    @GetMapping
    public ResponseEntity<List<ProductResponse>> list(
            @Parameter(description = "Loc theo ten (khong phan biet hoa thuong, chua chuoi con)")
            @RequestParam(required = false) String name,
            @Parameter(description = "Chi lay san pham co gia >= minPrice")
            @RequestParam(required = false) BigDecimal minPrice
    ) {
        boolean caseSensitive = ShopMutants.on("S11");
        boolean ignoreMinPrice = ShopMutants.on("S10");
        List<ProductResponse> result = productRepository.findAll().stream()
                .filter(p -> name == null || (caseSensitive ? p.getName().contains(name) : p.getName().toLowerCase().contains(name.toLowerCase())))
                .filter(p -> ignoreMinPrice || minPrice == null || p.getPrice().compareTo(minPrice) >= 0)
                .map(ProductResponse::from)
                .toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Cap nhat toan bo thong tin san pham qua request body")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cap nhat thanh cong"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay san pham voi id da cho")
    })
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> update(@PathVariable Long id, @RequestBody ProductRequest request) {
        validate(request, ShopMutants.on("S04") ? Set.of("price") : Set.of());
        Product product = productRepository.findById(id).orElse(null);
        if (product == null) {
            if (!ShopMutants.on("S14")) {
                throw new ProductNotFoundException("Khong tim thay san pham id=" + id);
            }
            product = Product.builder().build();
        }
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        return ResponseEntity.ok(ProductResponse.from(productRepository.save(product)));
    }

    @Operation(summary = "Cap nhat ton kho CHI qua query parameter, khong dung request body")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cap nhat thanh cong"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay san pham voi id da cho")
    })
    @PatchMapping("/{id}/stock")
    public ResponseEntity<ProductResponse> updateStock(
            @PathVariable Long id,
            @Parameter(description = "So luong thay doi, phai > 0") @RequestParam int quantity,
            @Parameter(description = "increase hoac decrease") @RequestParam String operation
    ) {
        Product product = findOrThrow(id);
        int delta = "decrease".equalsIgnoreCase(operation) ? -quantity : quantity;
        int newStock = product.getStock() + delta;
        if (newStock < 0 && !ShopMutants.on("S09")) {
            newStock = 0;
        }
        product.setStock(newStock);
        return ResponseEntity.ok(ProductResponse.from(productRepository.save(product)));
    }

    @Operation(summary = "Xoa san pham")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Xoa thanh cong"),
            @ApiResponse(responseCode = "404", description = "Khong tim thay san pham voi id da cho")
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Product product = productRepository.findById(id).orElse(null);
        if (product == null) {
            if (ShopMutants.on("S06")) {
                return ResponseEntity.noContent().build();
            }
            throw new ProductNotFoundException("Khong tim thay san pham id=" + id);
        }
        if (!ShopMutants.on("S07")) {
            productRepository.delete(product);
        }
        return ResponseEntity.noContent().build();
    }

    private Product findOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Khong tim thay san pham id=" + id));
    }
}
