package com.example.perfume_budget.tenancy;

import com.example.perfume_budget.config.TenantSeeder;
import com.example.perfume_budget.platform.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** PROV-01 AC1: request validation rejects bad input before touching the registry or any schema. */
class TenantProvisioningValidationTest {

    static final String HASH = "$2a$10$" + "a".repeat(53);

    TenantRepository repo = mock(TenantRepository.class);
    TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
    TenantSeeder seeder = mock(TenantSeeder.class);
    TenantProvisioningService service = new TenantProvisioningService(repo, migrator, seeder, mock(PlatformTransactionManager.class));

    private void assertRejected(ProvisioningRequest r) {
        assertThatThrownBy(() -> service.provision(r)).isInstanceOf(InvalidProvisioningRequestException.class);
        verifyNoInteractions(repo, migrator, seeder);
    }

    @Test
    void rejectsInjectionSlug() {
        assertRejected(new ProvisioningRequest("x\"; DROP SCHEMA master CASCADE;--", "n", "a@b.co", "A", HASH, null));
    }

    @Test
    void rejectsReservedSlug() {
        assertRejected(new ProvisioningRequest("public", "n", "a@b.co", "A", HASH, null));
        assertRejected(new ProvisioningRequest("pg-anything", "n", "a@b.co", "A", HASH, null));
    }

    @Test
    void rejectsPlaintextPasswordPassedAsHash_withoutEchoingIt() {
        ProvisioningRequest r = new ProvisioningRequest("acme", "n", "a@b.co", "A", "SuperSecret123!", null);
        assertThatThrownBy(() -> service.provision(r)).isInstanceOf(InvalidProvisioningRequestException.class)
                .hasMessageNotContaining("SuperSecret123!");
        verifyNoInteractions(repo, migrator, seeder);
    }

    @Test
    void rejectsBadEmailsAndNull() {
        assertRejected(new ProvisioningRequest("acme", "n", "not-an-email", "A", HASH, null));
        assertRejected(new ProvisioningRequest("acme", "n", null, "A", HASH, null));
        assertRejected(new ProvisioningRequest("acme", "n", "a@b.co\nBcc: x@y.z", "A", HASH, null));
        assertRejected(null);
    }

    @Test
    void rejectsControlCharsInNames() {
        assertRejected(new ProvisioningRequest("acme", "bad\u0007name", "a@b.co", "A", HASH, null));
        assertRejected(new ProvisioningRequest("acme", "n", "a@b.co", "x".repeat(300), HASH, null));
    }

    @Test
    void requestToStringRedactsHash() {
        String s = new ProvisioningRequest("acme", "n", "a@b.co", "A", HASH, null).toString();
        assertThat(s).doesNotContain(HASH).contains("<redacted>");
    }
}
