package com.example.perfume_budget.service;

import com.example.perfume_budget.enums.PaymentStatus;
import com.example.perfume_budget.model.Order;
import com.example.perfume_budget.model.Payment;
import com.example.perfume_budget.repository.CartRepository;
import com.example.perfume_budget.repository.OrderRepository;
import com.example.perfume_budget.repository.PaymentRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.example.perfume_budget.service.interfaces.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookProcessorImplTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderService orderService;
    @Mock
    private CartRepository cartRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private BookkeepingService bookkeepingService;

    @InjectMocks
    private WebhookProcessorImpl webhookProcessor;

    private Payment testPayment;
    private Order testOrder;

    @BeforeEach
    void setUp() {
        testOrder = Order.builder()
                .id(1L)
                .orderNumber("ORD-123")
                .items(new ArrayList<>())
                .build();

        testPayment = Payment.builder()
                .id(1L)
                .providerPaymentReference("REF123")
                .status(PaymentStatus.PENDING)
                .order(testOrder)
                .customerEmail("test@example.com")
                .build();
    }

    @Test
    void processWebhook_Success() {
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"REF123\",\"channel\":\"card\"}}";
        when(paymentRepository.findByProviderPaymentReference("REF123")).thenReturn(testPayment);
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);

        webhookProcessor.processWebhook(body);

        verify(paymentRepository).save(testPayment);
        verify(orderRepository).save(testOrder);
        verify(cartRepository).deleteAllByUserEmail("test@example.com");
        verify(orderService).finalizeReservedStock(testOrder);
        verify(bookkeepingService).recordSale(eq(testOrder), eq(testPayment));
        verify(eventPublisher).publishEvent(any(Payment.class));
    }

    @Test
    void processWebhook_Failure() {
        String body = "{\"event\":\"charge.failed\",\"data\":{\"reference\":\"REF123\",\"channel\":\"card\",\"message\":\"Declined\"}}";
        when(paymentRepository.findByProviderPaymentReference("REF123")).thenReturn(testPayment);
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);

        webhookProcessor.processWebhook(body);

        verify(paymentRepository).save(testPayment);
        verify(orderService).releaseStock(anyString());
    }

    @Test
    void processWebhook_PaymentNotFound() {
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"REF_NONE\"}}";
        when(paymentRepository.findByProviderPaymentReference("REF_NONE")).thenReturn(null);

        webhookProcessor.processWebhook(body);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void processWebhook_AlreadyProcessed() {
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"REF123\"}}";
        testPayment.setStatus(PaymentStatus.COMPLETED);
        when(paymentRepository.findByProviderPaymentReference("REF123")).thenReturn(testPayment);

        webhookProcessor.processWebhook(body);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void processWebhook_IgnoresCancelledPayment() {
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"REF123\",\"channel\":\"card\"}}";
        testPayment.setStatus(PaymentStatus.CANCELLED);
        when(paymentRepository.findByProviderPaymentReference("REF123")).thenReturn(testPayment);

        webhookProcessor.processWebhook(body);

        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(orderRepository, cartRepository, bookkeepingService, eventPublisher);
    }
}
