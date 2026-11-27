package com.example.perfume_budget.support;

import com.example.perfume_budget.platform.plan.model.Plan;
import com.example.perfume_budget.platform.plan.repository.PlanRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** PLAN-01 test fixtures: the seeded plan ids (V51) and a stub for unit tests that build tenant summaries. */
public final class TestPlans {

    public static final long STARTER_ID = 1L;
    public static final long GROWTH_ID = 2L;
    public static final long SCALE_ID = 3L;

    private TestPlans() {
    }

    /** A detached plan row as V51 seeds STARTER (only the fields a tenant summary reads). */
    public static Plan plan(long id) {
        String code = id == STARTER_ID ? "STARTER" : id == GROWTH_ID ? "GROWTH" : id == SCALE_ID ? "SCALE" : "PLAN" + id;
        String name = code.charAt(0) + code.substring(1).toLowerCase(java.util.Locale.ROOT);
        return Plan.builder().id(id).code(code).name(name).trialDays(14).isPublic(true).active(true)
                .isDefaultForSignup(id == STARTER_ID).build();
    }

    /** Mock {@code findById} / {@code findAllById} / {@code findDefaultForSignup} to answer for any id. */
    @SuppressWarnings("unchecked")
    public static void stubAnyId(PlanRepository plans) {
        when(plans.findById(anyLong())).thenAnswer(i -> Optional.of(plan(i.getArgument(0))));
        when(plans.findAllById(any(Iterable.class))).thenAnswer(i -> {
            List<Plan> out = new ArrayList<>();
            for (Object id : (Iterable<Object>) i.getArgument(0)) {
                out.add(plan((Long) id));
            }
            return out;
        });
        when(plans.findDefaultForSignup()).thenReturn(Optional.of(plan(STARTER_ID)));
    }
}
