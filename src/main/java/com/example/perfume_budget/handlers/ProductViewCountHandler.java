package com.example.perfume_budget.handlers;

import com.example.perfume_budget.events.CartTimesEvent;
import com.example.perfume_budget.events.ViewCountEvent;
import com.example.perfume_budget.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductViewCountHandler {
    private final ProductRepository productRepository;

    @Async
    @Transactional
    @EventListener
    public void countProductViews(ViewCountEvent event){
        productRepository.incrementViewCount(event.productId());
        log.info("Product view count updated for product ID: {}", event.productId());
    }

    @Async
    @Transactional
    @EventListener
    public void increaseNumberOfTimesAddedToCart(CartTimesEvent event){
        productRepository.incrementCartTimesCount(event.productId());
        log.info("Product cart times count updated for product ID: {}", event.productId());
    }
}
