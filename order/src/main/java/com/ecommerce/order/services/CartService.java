package com.ecommerce.order.services;

import com.ecommerce.order.clients.ProductServiceClient;
import com.ecommerce.order.clients.UserServiceClient;
import com.ecommerce.order.dtos.CartItemRequest;
import com.ecommerce.order.dtos.ProductResponse;
import com.ecommerce.order.dtos.UserResponse;
import com.ecommerce.order.models.CartItem;
import com.ecommerce.order.repositories.CartItemRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the cart. Remote calls happen here, outside any transaction;
 * persistence is delegated to CartPersistenceService.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final CartPersistenceService cartPersistenceService;
    private final ProductServiceClient productServiceClient;
    private final UserServiceClient userServiceClient;

    /**
     * Circuit breaker first, then retry: the breaker sheds load once product-service
     * is consistently failing, so retries never pile onto an already-failing service.
     */
    @CircuitBreaker(name = "productService", fallbackMethod = "addToCartFallback")
    @Retry(name = "retryBreaker", fallbackMethod = "addToCartFallback")
    public boolean addToCart(String userId, CartItemRequest request) {
        ProductResponse product = productServiceClient.getProductDetails(request.getProductId());
        if (product == null) {
            log.warn("Product {} not found while adding to cart for user {}",
                    request.getProductId(), userId);
            return false;
        }
        if (product.getStockQuantity() == null || product.getStockQuantity() < request.getQuantity()) {
            log.info("Insufficient stock for product {}: requested {}, available {}",
                    request.getProductId(), request.getQuantity(), product.getStockQuantity());
            return false;
        }

        UserResponse user = userServiceClient.getUserDetails(userId);
        if (user == null) {
            log.warn("Unknown user {} while adding to cart", userId);
            return false;
        }

        // The real catalogue price. This was previously hardcoded to 1000.00, so
        // every cart line totalled 1000 regardless of the product.
        cartPersistenceService.upsertCartItem(
                userId, request.getProductId(), request.getQuantity(), product.getPrice());

        return true;
    }

    @SuppressWarnings("unused")   // invoked by Resilience4j
    public boolean addToCartFallback(String userId, CartItemRequest request, Throwable t) {
        log.error("addToCart fallback for user {} / product {}: {}",
                userId, request.getProductId(), t.toString());
        return false;
    }

    public boolean deleteItemFromCart(String userId, String productId) {
        return cartPersistenceService.deleteItem(userId, productId);
    }

    public List<CartItem> getCart(String userId) {
        return cartItemRepository.findByUserId(userId);
    }

    public void clearCart(String userId) {
        cartPersistenceService.clear(userId);
    }
}
