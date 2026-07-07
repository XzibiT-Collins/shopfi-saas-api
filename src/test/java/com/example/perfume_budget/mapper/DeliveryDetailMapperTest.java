package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.delivery_detail.request.DeliveryDetailRequest;
import com.example.perfume_budget.dto.delivery_detail.response.DeliveryDetailResponse;
import com.example.perfume_budget.enums.DeliveryLabel;
import com.example.perfume_budget.model.DeliveryDetail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryDetailMapperTest {

    @Test
    void toDeliveryDetailResponse_Success() {
        DeliveryDetail detail = DeliveryDetail.builder()
                .id(1L).recipientName("John").phoneNumber("123").addressLine1("Addr").city("City").region("Reg").label(DeliveryLabel.HOME).isDefault(true)
                .build();

        DeliveryDetailResponse response = DeliveryDetailMapper.toDeliveryDetailResponse(detail);

        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("John", response.recipientName());
        assertTrue(response.isDefault());
    }

    @Test
    void toDeliveryDetail_Success() {
        DeliveryDetailRequest request = new DeliveryDetailRequest("John", "123", "456", "Addr1", "Addr2", "City", "Reg", "Land", DeliveryLabel.WORK, false);

        DeliveryDetail result = DeliveryDetailMapper.toDeliveryDetail(request);

        assertNotNull(result);
        assertEquals("John", result.getRecipientName());
        assertEquals("123", result.getPhoneNumber());
        assertEquals(DeliveryLabel.WORK, result.getLabel());
    }

    @Test
    void toDeliveryDetail_NullInput_ThrowsException() {
        assertThrows(NullPointerException.class, () -> DeliveryDetailMapper.toDeliveryDetail(null));
    }
}
