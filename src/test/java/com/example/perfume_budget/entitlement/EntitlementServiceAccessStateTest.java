package com.example.perfume_budget.entitlement;

import com.example.perfume_budget.platform.enums.SubscriptionStatus;
import com.example.perfume_budget.platform.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BILL-01c AC3: the cache value is built from the tenant row plus the billing projection behind {@link TenantAccessStateSource}. */
class EntitlementServiceAccessStateTest {

    private final TenantRepository tenants = mock(TenantRepository.class);

    @SuppressWarnings("unchecked")
    private EntitlementService service(TenantAccessStateSource source) {
        ObjectProvider<TenantAccessStateSource> p = mock(ObjectProvider.class);
        when(p.getIfAvailable(any(Supplier.class))).thenAnswer(i -> source != null ? source : ((Supplier<?>) i.getArgument(0)).get());
        when(tenants.findSubscriptionState(7L)).thenReturn(Collections.singletonList(
                new Object[]{7L, 2L, SubscriptionStatus.TRIALING, null}));
        return new EntitlementService(null, tenants, null, null, null, null, Clock.systemUTC(), p);
    }

    @Test
    void withoutABillingProjection_theTenantIsFullAndNotBillingManaged() {
        TenantPlanState s = service(null).loadState(7).orElseThrow();
        assertThat(s).isEqualTo(new TenantPlanState(7, 2, SubscriptionStatus.TRIALING, null, AccessState.FULL, false));
    }

    @Test
    void theProjectionOfTheSourceReachesTheCachedState() {
        TenantAccessStateSource source = id -> {
            assertThat(id).isEqualTo(7L);
            return new TenantAccessStateSource.Projection(AccessState.READ_ONLY, true);
        };
        TenantPlanState s = service(source).loadState(7).orElseThrow();
        assertThat(s.accessState()).isEqualTo(AccessState.READ_ONLY);
        assertThat(s.billingManaged()).isTrue();
        assertThat(s.readOnlyAt(java.time.Instant.now())).isTrue();
    }

    @Test
    void anUnknownTenantIsEmpty_neverADefaultGrant() {
        when(tenants.findSubscriptionState(99L)).thenReturn(List.of());
        assertThat(service(null).loadState(99)).isEmpty();
    }
}
