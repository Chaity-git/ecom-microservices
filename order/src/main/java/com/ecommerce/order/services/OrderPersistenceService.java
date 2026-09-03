package com.ecommerce.order.services;

import com.ecommerce.order.models.CartItem;
import com.ecommerce.order.models.Order;
import com.ecommerce.order.models.OrderItem;
import com.ecommerce.order.models.OrderStatus;
import com.ecommerce.order.repositories.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Saving the order and clearing the cart in ONE transaction.
 *
 * Previously these were two independent writes: if clearCart failed the order
 * existed while the cart still held its items, so a retry double-ordered.
 */
@Service
@RequiredArgsConstructor
public class OrderPersistenceService {

    private final OrderRepository orderRepository;
    private final CartPersistenceService cartPersistenceService;

    @Transactional
    public Optional<Order> placeOrder(String userId, List<CartItem> cartItems) {
        if (cartItems.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal totalPrice = cartItems.stream()
                .map(CartItem::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setTotalAmount(totalPrice);
        order.setItems(cartItems.stream()
                .map(item -> new OrderItem(null, item.getProductId(),
                        item.getQuantity(), item.getPrice(), order))
                .toList());

        Order saved = orderRepository.save(order);
        cartPersistenceService.clear(userId);   // joins the same transaction
        return Optional.of(saved);
    }
}
