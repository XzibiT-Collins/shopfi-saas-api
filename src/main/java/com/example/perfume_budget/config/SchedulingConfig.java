package com.example.perfume_budget.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class SchedulingConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
