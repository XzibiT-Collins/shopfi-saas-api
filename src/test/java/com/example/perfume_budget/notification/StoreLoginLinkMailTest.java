package com.example.perfume_budget.notification;

import com.example.perfume_budget.support.TestLinkBuilders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** API-RD-04: the find-store mail leg with the REAL template: recipient is the owner on record, link path, escaping. */
class StoreLoginLinkMailTest {
    @Test
    void realTemplate_escapesStoreName_recipientIsOwner_linkIsTenantOrigin() {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/"); r.setSuffix(".html"); r.setTemplateMode(TemplateMode.HTML); r.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(r);
        MailTransport transport = mock(MailTransport.class);
        EmailServiceImpl svc = new EmailServiceImpl(transport, engine, null, "admin@example.com",
                TestLinkBuilders.linkBuilder("shopfi.test", "https://frontend.example.com/reset-password"));
        svc.sendStoreLoginLinkEmail("owner@owner.test", "<img src=x onerror=alert(1)>\"'", "acme");
        ArgumentCaptor<MailMessage> c = ArgumentCaptor.forClass(MailMessage.class);
        verify(transport).send(c.capture());
        MailMessage m = c.getValue();
        assertThat(m.to()).isEqualTo("owner@owner.test");
        assertThat(m.subject()).isEqualTo("Your store sign-in link");
        assertThat(m.html()).doesNotContain("<img src=x").contains("&lt;img").contains("https://acme.shopfi.test/admin/login");
        assertThat(m.html()).doesNotContain("token");
    }
}
