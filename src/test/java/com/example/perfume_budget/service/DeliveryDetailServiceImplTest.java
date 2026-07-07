package com.example.perfume_budget.service;

import com.example.perfume_budget.dto.delivery_detail.request.DeliveryDetailRequest;
import com.example.perfume_budget.dto.delivery_detail.response.DeliveryDetailResponse;
import com.example.perfume_budget.enums.DeliveryLabel;
import com.example.perfume_budget.exception.ResourceNotFoundException;
import com.example.perfume_budget.model.DeliveryDetail;
import com.example.perfume_budget.model.User;
import com.example.perfume_budget.repository.DeliveryDetailRepository;
import com.example.perfume_budget.utils.AuthUserUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryDetailServiceImplTest {

    @Mock
    private DeliveryDetailRepository deliveryDetailRepository;
    @Mock
    private AuthUserUtil authUserUtil;

    @InjectMocks
    private DeliveryDetailServiceImpl deliveryDetailService;

    private User testUser;
    private DeliveryDetail testDetail;
    private DeliveryDetailRequest detailRequest;

    @BeforeEach
    void setUp() {
        testUser = User.builder().id(1L).email("test@example.com").build();
        testDetail = DeliveryDetail.builder()
                .id(1L)
                .recipientName("John Doe")
                .phoneNumber("0241234567")
                .addressLine1("123 Street")
                .city("Accra")
                .region("Greater Accra")
                .label(DeliveryLabel.HOME)
                .isDefault(false)
                .user(testUser)
                .build();

        detailRequest = new DeliveryDetailRequest(
                "John Doe",
                "0241234567",
                null,
                "123 Street",
                null,
                "Accra",
                "Greater Accra",
                "Near Mall",
                DeliveryLabel.HOME,
                true
        );
    }

    @Test
    void addDeliveryDetail_Success() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.save(any(DeliveryDetail.class))).thenReturn(testDetail);

        DeliveryDetailResponse result = deliveryDetailService.addDeliveryDetail(detailRequest);

        assertNotNull(result);
        verify(deliveryDetailRepository).clearDefaultForUser(testUser.getId());
        verify(deliveryDetailRepository).save(any(DeliveryDetail.class));
    }

    @Test
    void updateDeliveryDetail_Success() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByIdAndUser(1L, testUser)).thenReturn(Optional.of(testDetail));
        when(deliveryDetailRepository.save(any(DeliveryDetail.class))).thenReturn(testDetail);

        DeliveryDetailResponse result = deliveryDetailService.updateDeliveryDetail(1L, detailRequest);

        assertNotNull(result);
        assertTrue(testDetail.getIsDefault());
        verify(deliveryDetailRepository).save(testDetail);
    }

    @Test
    void updateDeliveryDetail_Failure_NotFound() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByIdAndUser(1L, testUser)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> deliveryDetailService.updateDeliveryDetail(1L, detailRequest));
    }

    @Test
    void removeDeliveryDetail_Success() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByIdAndUser(1L, testUser)).thenReturn(Optional.of(testDetail));

        deliveryDetailService.removeDeliveryDetail(1L);

        verify(deliveryDetailRepository).delete(testDetail);
    }

    @Test
    void getMyDeliveryDetails_Success() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findAllByUser(testUser)).thenReturn(List.of(testDetail));

        List<DeliveryDetailResponse> result = deliveryDetailService.getMyDeliveryDetails();

        assertEquals(1, result.size());
    }

    @Test
    void setDefaultDeliveryDetail_Success() {
        when(authUserUtil.getCurrentUser()).thenReturn(testUser);
        when(deliveryDetailRepository.findByIdAndUser(1L, testUser)).thenReturn(Optional.of(testDetail));
        when(deliveryDetailRepository.save(any(DeliveryDetail.class))).thenReturn(testDetail);

        deliveryDetailService.setDefaultDeliveryDetail(1L);

        verify(deliveryDetailRepository).clearDefaultForUser(testUser.getId());
        verify(deliveryDetailRepository).flush();
        assertTrue(testDetail.getIsDefault());
    }
}
