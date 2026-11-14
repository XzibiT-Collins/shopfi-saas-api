package com.example.perfume_budget.tenancy;

/** The provisioning request failed validation (bad slug, email, hash, ...). Nothing was written. */
public class InvalidProvisioningRequestException extends IllegalArgumentException {
    public InvalidProvisioningRequestException(String message) {
        super(message);
    }
}
