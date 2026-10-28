package com.fashioncommerce.fashioncommercebackend.product.controller;

import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.BrandResponse;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.CategoryResponse;
import com.fashioncommerce.fashioncommercebackend.product.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final ProductService service;

    public CatalogController(ProductService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return service.categories();
    }

    @GetMapping("/brands")
    public List<BrandResponse> brands() {
        return service.brands();
    }
}
