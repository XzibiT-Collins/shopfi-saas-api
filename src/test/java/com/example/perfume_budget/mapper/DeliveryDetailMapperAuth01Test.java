package com.example.perfume_budget.mapper;

import com.example.perfume_budget.dto.delivery_detail.request.DeliveryDetailRequest;
import com.example.perfume_budget.enums.DeliveryLabel;
import com.example.perfume_budget.model.DeliveryDetail;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AUTH-01 AC4: label defaults on create (NOT NULL column), landmark is optional. */
class DeliveryDetailMapperAuth01Test {

    @Test
    void missingLabelDefaultsToHome_andMissingLandmarkStaysNull() {
        DeliveryDetail d = DeliveryDetailMapper.toDeliveryDetail(
                new DeliveryDetailRequest("J", "1", null, "A1", null, "C", "R", null, null, false));
        assertThat(d.getLabel()).isEqualTo(DeliveryLabel.HOME);
        assertThat(d.getLandmark()).isNull();
    }

    @Test
    void explicitLabelIsKept() {
        DeliveryDetail d = DeliveryDetailMapper.toDeliveryDetail(
                new DeliveryDetailRequest("J", "1", null, "A1", null, "C", "R", "Near X", DeliveryLabel.OFFICE, true));
        assertThat(d.getLabel()).isEqualTo(DeliveryLabel.OFFICE);
        assertThat(d.getLandmark()).isEqualTo("Near X");
    }
}
