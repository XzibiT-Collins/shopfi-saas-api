package com.example.perfume_budget.config;

import com.example.perfume_budget.dto.product.response.ProductDetailsPageResponse;
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

    private JdkSerializationRedisSerializer createSerializer() {
        return new JdkSerializationRedisSerializer();
    }
}
