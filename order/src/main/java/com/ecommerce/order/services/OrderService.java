package com.ecommerce.order.services;

import com.ecommerce.order.dtos.OrderCreatedEvent;
import com.ecommerce.order.dtos.OrderItemDTO;
import com.ecommerce.order.dtos.OrderResponse;
import com.ecommerce.order.models.CartItem;
import com.ecommerce.order.models.Order;
import com.ecommerce.order.models.OrderItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private static final String ORDER_CREATED_BINDING = "createOrder-out-0";

    private final CartService cartService;
    private final OrderPersistenceService orderPersistenceService;
    private final StreamBridge streamBridge;

    public Optional<OrderResponse> createOrder(String userId) {
        List<CartItem> cartItems = cartService.getCart(userId);

        // Save the order and clear the cart atomically.
        Optional<Order> saved = orderPersistenceService.placeOrder(userId, cartItems);
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        Order order = saved.get();

        // Published only after the transaction has committed, so no database
        // connection is held while the broker responds.
        //
        // Known limitation: the commit and the publish are not atomic. If this
        // process dies in between, the order exists with no event. The proper fix
        // is the transactional outbox pattern — write the event to an outbox table
        // inside the transaction above and let a poller publish it.
        publishOrderCreated(order);

        return Optional.of(mapToOrderResponse(order));
    }

    private void publishOrderCreated(Order order) {
        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getId(), order.getUserId(), order.getStatus(),
                mapToOrderItemDTOs(order.getItems()),
                order.getTotalAmount(), order.getCreatedAt());
        try {
            streamBridge.send(ORDER_CREATED_BINDING, event);
        } catch (Exception e) {
            log.error("Order {} committed but the OrderCreatedEvent could not be published",
                    order.getId(), e);
        }
    }

    private List<OrderItemDTO> mapToOrderItemDTOs(List<OrderItem> items) {
        return items.stream()
                .map(item -> new OrderItemDTO(item.getId(), item.getProductId(),
                        item.getQuantity(), item.getPrice(),
                        item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()))))
                .toList();
    }

    private OrderResponse mapToOrderResponse(Order order) {
        return new OrderResponse(order.getId(), order.getTotalAmount(), order.getStatus(),
                mapToOrderItemDTOs(order.getItems()), order.getCreatedAt());
    }
}
