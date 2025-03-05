package com.green.greengram.product;

import com.green.greengram.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // where product_id in (1, 2, 3) >> List<Long> 에 있는 값들이 in에 들어가는 것
    List<Product> findByProductIdIn(List<Long> productIds);
}
