package com.example.perfume_budget.platform.service;

import com.example.perfume_budget.platform.enums.TenantStatus;
import com.example.perfume_budget.platform.model.Tenant;
import com.example.perfume_budget.platform.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TenantRegistryServiceImpl implements TenantRegistryService {

    private final TenantRepository tenantRepository;

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        return tenantRepository.findBySlug(slug);
    }

    @Override
    public Optional<Tenant> findByCustomDomain(String customDomain) {
        return tenantRepository.findByCustomDomain(customDomain);
    }

    @Override
    public Optional<Tenant> findByWebhookToken(String webhookToken) {
        return tenantRepository.findByWebhookToken(webhookToken);
    }

    @Override
    public List<Tenant> findByStatus(TenantStatus status) {
        return tenantRepository.findByStatus(status);
    }

    @Override
    public boolean slugExists(String slug) {
        return tenantRepository.existsBySlug(slug);
    }

    @Override
    public boolean ownerEmailExists(String ownerEmail) {
        return tenantRepository.existsByOwnerEmail(ownerEmail);
    }
}
