package com.example.perfume_budget.dto.inventory.request;

/**
 * Each flag set to {@code true} moves that role to the target location (clearing the current
 * holder). {@code false}/null flags are ignored — exactly one location must hold each role.
 */
public record LocationDefaultsRequest(
        Boolean isDefaultReceiving,
        Boolean isWalkInSaleSource,
        Boolean isEcommerceFulfilmentSource
) {
}
