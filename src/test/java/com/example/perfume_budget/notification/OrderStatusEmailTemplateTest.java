package com.example.perfume_budget.notification;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.perfume_budget.enums.OrderProcessingStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** ORD-07 review low: the order-status e-mail's progress tracker for a RETURNED order (rendered with the real template). */
class OrderStatusEmailTemplateTest {

    private static String render(OrderProcessingStatus status) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        TemplateEngine engine = new org.thymeleaf.spring6.SpringTemplateEngine(); // SpEL, like the application
        engine.setTemplateResolver(resolver);
        Context ctx = new Context();
        ctx.setVariable("orderNumber", "PB-20260101-ABCDEFGHIJ");
        ctx.setVariable("orderStatus", status);
        return engine.process("order-status-update", ctx);
    }

    /** The class attribute of each of the four tracker dots, in order. */
    private static java.util.List<String> dots(String html) {
        java.util.List<String> out = new java.util.ArrayList<>();
        // Thymeleaf renders classappend into the existing class attribute: class="step-dot cancelled"
        Matcher all = Pattern.compile("class=\"(step-dot[^\"]*)\"").matcher(html);
        while (all.find()) {
            out.add(all.group(1).trim().replaceAll("\\s+", " "));
        }
        return out;
    }

    @Test
    void aReturnedOrderShowsAClosedTracker_allFourDotsGrey_likeACancelledOne_andTheReturnedBanner() {
        String returned = render(OrderProcessingStatus.RETURNED);
        assertThat(dots(returned)).containsExactly("step-dot cancelled", "step-dot cancelled", "step-dot cancelled", "step-dot cancelled");
        assertThat(returned).contains("Order Returned").doesNotContain("Order Delivered").doesNotContain("Order Cancelled");
        assertThat(dots(render(OrderProcessingStatus.CANCELLED)))
                .containsExactly("step-dot cancelled", "step-dot cancelled", "step-dot cancelled", "step-dot cancelled");
    }

    @Test
    void theOtherStatusesKeepTheirTracker() {
        assertThat(dots(render(OrderProcessingStatus.PENDING))).containsExactly("step-dot done", "step-dot", "step-dot", "step-dot");
        assertThat(dots(render(OrderProcessingStatus.PACKING))).containsExactly("step-dot done", "step-dot active", "step-dot", "step-dot");
        assertThat(dots(render(OrderProcessingStatus.OUT_FOR_DELIVERY)))
                .containsExactly("step-dot done", "step-dot done", "step-dot active", "step-dot");
        String delivered = render(OrderProcessingStatus.DELIVERED);
        assertThat(dots(delivered)).containsExactly("step-dot done", "step-dot done", "step-dot done", "step-dot active");
        assertThat(delivered).contains("Order Delivered").doesNotContain("Order Returned");
    }
}
