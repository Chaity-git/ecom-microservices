package com.ecommerce.order.services;

import com.ecommerce.order.models.CartItem;
import com.ecommerce.order.repositories.CartItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * The only transactional boundary in the cart flow, deliberately kept to
 * database work alone.
 *
 * CartService previously carried a class-level @Transactional, which meant two
 * remote HTTP calls executed with a PostgreSQL connection held open. With
 * @Retry(maxAttempts=5, waitDuration=5s) on top, a failing product-service could
 * pin one connection for roughly 25 seconds — enough to exhaust the pool under
 * modest load.
 *
 * It lives in its own bean because Spring's transaction proxy only wraps calls
 * arriving from outside the bean; a self-invoked private method would silently
 * run with no transaction at all.
 */
@Service
@RequiredArgsConstructor
public class CartPersistenceService {

    private final CartItemRepository cartItemRepository;

    @Transactional
    public void upsertCartItem(String userId, String productId, int quantity, BigDecimal unitPrice) {
        CartItem existing = cartItemRepository.findByUserIdAndProductId(userId, productId);

        if (existing != null) {
            int newQuantity = existing.getQuantity() + quantity;
            existing.setQuantity(newQuantity);
            existing.setPrice(unitPrice.multiply(BigDecimal.valueOf(newQuantity)));
            cartItemRepository.save(existing);
        } else {
            CartItem item = new CartItem();
            item.setUserId(userId);
            item.setProductId(productId);
            item.setQuantity(quantity);
            item.setPrice(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            cartItemRepository.save(item);
        }
    }

    @Transactional
    public boolean deleteItem(String userId, String productId) {
        CartItem item = cartItemRepository.findByUserIdAndProductId(userId, productId);
        if (item == null) return false;
        cartItemRepository.delete(item);
        return true;
    }

    @Transactional
    public void clear(String userId) {
        cartItemRepository.deleteByUserId(userId);
    }
}
