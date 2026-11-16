package com.example.perfume_budget.handlers;

import com.example.perfume_budget.events.CartTimesEvent;
import com.example.perfume_budget.events.ViewCountEvent;
import com.example.perfume_budget.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Product analytics counters. EVT-01: counted only for work that committed (an add-to-cart whose transaction rolls
 * back, or each failed attempt of a retried one, must not count), in their own transaction on the default async
 * executor; published outside a transaction (the product page GET) they count at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductViewCountHandler {
    private final ProductRepository productRepository;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void countProductViews(ViewCountEvent event){
        productRepository.incrementViewCount(event.productId());
        log.info("Product view count updated for product ID: {}", event.productId());
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void increaseNumberOfTimesAddedToCart(CartTimesEvent event){
        productRepository.incrementCartTimesCount(event.productId());
        log.info("Product cart times count updated for product ID: {}", event.productId());
    }
}
