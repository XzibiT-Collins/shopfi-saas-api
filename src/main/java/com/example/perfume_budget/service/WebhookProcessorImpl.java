package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.payment.request.PaystackWebhook;
import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.repository.CartRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.service.interfaces.OrderService;
import com.example.perfume_budget.service.interfaces.WebhookProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookProcessorImpl implements WebhookProcessor {
    private final PaymentRepository paymentRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final CartRepository cartRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BookkeepingService bookkeepingService;

    @Async("webhookTaskExecutor")
    @Transactional
    @Override
    public void processWebhook(String body) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            PaystackWebhook payload = objectMapper.readValue(body, PaystackWebhook.class);

            String reference = payload.data().reference().trim();
            log.info("Webhook received: {}", payload);
            Payment payment = paymentRepository.findByProviderPaymentReference(reference);
            if(payment == null){
                log.info("Payment object not found for provider reference {}", reference);
                return;
            }

            if(payment.getStatus() == PaymentStatus.COMPLETED || payment.getStatus() == PaymentStatus.FAILED || payment.getStatus() == PaymentStatus.CANCELLED){
                log.info("Payment already processed for reference: {}, ignoring processing request.",reference);
                return;
            }

            // store payload
            payment.setGatewayResponse(body);

            if ("charge.success".equals(payload.event())) {
                if (payment.getOrder() != null && payment.getOrder().getStatus() == PaymentStatus.CANCELLED) {
                    log.info("Ignoring webhook success for cancelled order {}", payment.getOrder().getOrderNumber());
                    payment.setStatus(PaymentStatus.CANCELLED);
                    paymentRepository.save(payment);
                    return;
                }
                log.info("Payment successful for reference {}", reference);
                payment.setStatus(PaymentStatus.COMPLETED);
                payment.setPaymentMethod(payload.data().channel());
                payment.setPaidAt(LocalDateTime.now());

                handlePaymentSuccess(payment);
            } else {
                log.info("Payment failed for reference {}", reference);
                payment.setStatus(PaymentStatus.FAILED);
                payment.setPaymentMethod(payload.data().channel());
                payment.setFailureReason(payload.data().message());
                handlePaymentFailure(payment);
            }
        }catch (Exception e){
            log.error("Error processing webhook: {}", e.getMessage());
        }
    }

    private void handlePaymentSuccess(Payment payment) {
        Payment savedPayment = paymentRepository.save(payment);

        // get Order and update order paymentStatus
        Order successfulOrder = savedPayment.getOrder();
        if(successfulOrder == null){
            log.info("Order not found for successful payment with reference: {}", savedPayment.getSystemReference());
            return;
        }

        successfulOrder.setPaymentReference(savedPayment.getSystemReference() != null
                ? savedPayment.getSystemReference()
                : savedPayment.getProviderPaymentReference());
        successfulOrder.setStatus(savedPayment.getStatus());

        orderRepository.save(successfulOrder);

        // clear user cart
        cartRepository.deleteAllByUserEmail(payment.getCustomerEmail());

        // increment product sold count
        updateProductSoldCount(successfulOrder);

        orderService.finalizeReservedStock(successfulOrder);

        // Record sale
        bookkeepingService.recordSale(successfulOrder, savedPayment);

        // Notify customer and admin on successful payment processing
        eventPublisher.publishEvent(savedPayment);
        log.info("Payment Success Event Published successfully for Ref: {}", savedPayment.getSystemReference());
    }

    private void updateProductSoldCount(Order successfulOrder) {
        // Update the sold count of each product by the quantity ordered
        successfulOrder.getItems().forEach(item ->
                productRepository.incrementSoldCount(item.getProductId(), item.getQuantity())
        );
    }

    private void handlePaymentFailure(Payment payment) {
        Payment savedPayment = paymentRepository.save(payment);
        // get Order and update order paymentStatus
        Order failedOrder = savedPayment.getOrder();
        if(failedOrder == null){
            log.info("Order not found for failed payment with reference: {}", savedPayment.getSystemReference());
            return;
        }

        failedOrder.setPaymentReference(savedPayment.getSystemReference() != null
                ? savedPayment.getSystemReference()
                : savedPayment.getProviderPaymentReference());
        failedOrder.setStatus(savedPayment.getStatus());

        failedOrder = orderRepository.save(failedOrder);

        // Release stock after failed payment
        orderService.releaseStock(failedOrder.getOrderNumber());
    }
}
