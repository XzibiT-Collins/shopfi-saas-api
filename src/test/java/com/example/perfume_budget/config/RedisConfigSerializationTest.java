package com.example.perfume_budget.config;

import com.example.perfume_budget.dto.PageResponse;
import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
import com.example.perfume_budget.dto.product.response.PublicProductListing;
import com.example.perfume_budget.dto.product.response.ProductListing;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisConfigSerializationTest {

    @Test
    void featuredProductsCachePayloadRoundTripsThroughRedisSerializer() throws Exception {
        JdkSerializationRedisSerializer serializer = createSerializer();
        List<ProductListing> payload = List.of(
                ProductListing.builder()
                        .productId(1L)
                        .productName("Featured Perfume")
                        .productShortDescription("Short")
                        .price("USD 100.00")
                        .isOutOfStock(false)
                        .slug("featured-perfume")
                        .build()
        );

        byte[] serialized = serializer.serialize(payload);
        List<?> restored = assertDoesNotThrow(() -> (List<?>) serializer.deserialize(serialized));

        assertEquals(1, restored.size());
    }

    @Test
    void productDetailsCachePayloadRoundTripsThroughRedisSerializer() throws Exception {
        JdkSerializationRedisSerializer serializer = createSerializer();
        ProductDetailsPageResponse payload = ProductDetailsPageResponse.builder()
                .productId(1L)
                .productName("Detail Perfume")
                .productShortDescription("Short")
                .productDescription("Detailed")
                .productImageUrl("http://image.com/a.jpg")
                .category("Fragrance")
                .sellingPrice("USD 100.00")
                .isOutOfStock(false)
                .isFeatured(true)
                .slug("detail-perfume")
                .build();

        byte[] serialized = serializer.serialize(payload);
        ProductDetailsPageResponse restored = assertDoesNotThrow(
                () -> (ProductDetailsPageResponse) serializer.deserialize(serialized)
        );

        assertEquals("detail-perfume", restored.slug());
    }

    /** SEC-03d: the PUBLIC DTOs (what the public caches hold since SEC-03b) survive the real serializer. */
    @Test
    void publicListingAndPublicPageRoundTrip() {
        JdkSerializationRedisSerializer serializer = createSerializer();
        PublicProductListing row = PublicProductListing.builder().productId(7L).productName("Pub").productShortDescription("s")
                .productImageUrl("u").categoryName("c").price("GHS 9.00").originalPrice("GHS 10.00").onSale(true)
                .discountPercentage(new BigDecimal("10.00")).discountEndsAt(java.time.LocalDateTime.of(2030, 1, 2, 3, 4))
                .isOutOfStock(false).slug("pub").build();
        assertEquals(row, serializer.deserialize(serializer.serialize(row)));

        PageResponse<PublicProductListing> page = PageResponse.<PublicProductListing>builder().content(List.of(row))
                .totalPages(1).totalElements(1).size(20).number(0).numberOfElements(1).isFirst(true).isLast(true)
                .isEmpty(false).build();
        @SuppressWarnings("unchecked")
        PageResponse<PublicProductListing> back = (PageResponse<PublicProductListing>) serializer.deserialize(serializer.serialize(page));
        assertEquals(page, back);
        assertEquals(row, back.content().get(0));

        @SuppressWarnings("unchecked")
        List<PublicProductListing> featured = (List<PublicProductListing>) serializer.deserialize(serializer.serialize(List.of(row)));
        assertEquals(List.of(row), featured);
    }

    /** SEC-03d: an unreadable cached value is a miss that evicts the entry (never a 500 until the TTL). */
    @Test
    void unreadableCacheEntryIsAMissAndIsEvicted() {
        org.springframework.cache.CacheManager manager = new org.springframework.cache.concurrent.ConcurrentMapCacheManager("c");
        org.springframework.cache.Cache cache = manager.getCache("c");
        cache.put("k", "v");
        org.springframework.cache.interceptor.CacheErrorHandler handler = new CacheErrorConfig().errorHandler();
        handler.handleCacheGetError(new org.springframework.data.redis.serializer.SerializationException("bad bytes"), cache, "k");
        org.junit.jupiter.api.Assertions.assertNull(cache.get("k"), "the unreadable entry is gone");
        // a failing evict while handling the read error must not turn the miss into a failure
        org.springframework.cache.Cache failing = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        org.mockito.Mockito.when(failing.getName()).thenReturn("f");
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(failing).evict("k");
        assertDoesNotThrow(() -> handler.handleCacheGetError(new RuntimeException("x"), failing, "k"));
        // stale-data failures stay loud
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> handler.handleCacheEvictError(new IllegalStateException("e"), cache, "k"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> handler.handleCacheClearError(new IllegalStateException("e"), cache));
        assertDoesNotThrow(() -> handler.handleCachePutError(new RuntimeException("p"), cache, "k", "v"));
    }

    private JdkSerializationRedisSerializer createSerializer() {
        return new JdkSerializationRedisSerializer();
    }
}
