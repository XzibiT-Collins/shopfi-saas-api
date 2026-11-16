package com.example.perfume_budget.service;

import com.example.perfume_budget.model.Product;
import com.example.perfume_budget.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** API-05 rework: ProductStockLocks locks every product in ascending id order and translates a flush conflict. */
class ProductStockLocksTest {

    private ProductRepository productRepository;
    private EntityManager entityManager;
    private ProductStockLocks locks;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        entityManager = mock(EntityManager.class);
        locks = new ProductStockLocks(productRepository);
        ReflectionTestUtils.setField(locks, "entityManager", entityManager);
    }

    @Test
    void locksAllDistinctIdsAscendingInOneStatement_thenRefreshesEach() {
        Product p3 = new Product();
        Product p9 = new Product();
        when(entityManager.find(Product.class, 3L)).thenReturn(p3);
        when(entityManager.find(Product.class, 9L)).thenReturn(p9);

        locks.lock(Arrays.asList(9L, 3L, null, 9L, 5L));

        InOrder inOrder = inOrder(entityManager, productRepository);
        inOrder.verify(entityManager).flush();
        inOrder.verify(productRepository).lockForStockUpdate(List.of(3L, 5L, 9L));
        inOrder.verify(entityManager).refresh(p3);
        inOrder.verify(entityManager).refresh(p9);
        verify(productRepository, times(1)).lockForStockUpdate(any());
    }

    @Test
    void nothingToLockDoesNothing() {
        locks.lock(Arrays.asList(null, null));
        verifyNoInteractions(productRepository, entityManager);
    }

    @Test
    void aVersionConflictWhileFlushingIsSpringsOptimisticFailure_503NotJpas409() {
        doThrow(new OptimisticLockException("stale")).when(entityManager).flush();
        assertThatThrownBy(() -> locks.lock(List.of(1L)))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasCauseInstanceOf(OptimisticLockException.class);
        verifyNoInteractions(productRepository);
    }

    @Test
    void hibernatesStaleStateIsTranslatedToo() {
        doThrow(new StaleObjectStateException("Product", 1L)).when(entityManager).flush();
        assertThatThrownBy(() -> locks.lock(List.of(1L))).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void otherFlushFailuresAreNotDisguisedAsConflicts() {
        doThrow(new IllegalStateException("bug")).when(entityManager).flush();
        assertThatThrownBy(() -> locks.lock(List.of(1L))).isInstanceOf(IllegalStateException.class);
    }
}
