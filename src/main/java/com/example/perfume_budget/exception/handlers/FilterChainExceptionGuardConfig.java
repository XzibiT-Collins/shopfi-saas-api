package com.example.perfume_budget.exception.handlers;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** SEC-01e: {@link FilterChainExceptionGuard} first in the servlet filter chain, for every path and dispatch. */
@Configuration(proxyBeanMethods = false)
public class FilterChainExceptionGuardConfig {

    @Bean
    public FilterRegistrationBean<FilterChainExceptionGuard> filterChainExceptionGuard() {
        FilterRegistrationBean<FilterChainExceptionGuard> bean = new FilterRegistrationBean<>(new FilterChainExceptionGuard());
        bean.setName("filterChainExceptionGuard");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return bean;
    }
}
