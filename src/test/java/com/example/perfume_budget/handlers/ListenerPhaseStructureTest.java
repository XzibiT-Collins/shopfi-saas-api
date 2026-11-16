package com.example.perfume_budget.handlers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVT-01 (a) guard: every listener for the application's own events (domain payloads published by services, possibly
 * inside a transaction that may roll back or be retried) is an AFTER_COMMIT {@link TransactionalEventListener}; a
 * plain {@link EventListener} is allowed only for Spring's own lifecycle / WebSocket session events, which are never
 * published from inside a business transaction. E-mail listeners hand their work to {@link MailDispatcher} and are
 * not {@link Async} themselves (the dispatcher logs the reference of a rejected or failed send).
 */
class ListenerPhaseStructureTest {

    private static List<Method> listenerMethods() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        List<Method> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.example.perfume_budget")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            if (String.valueOf(type.getProtectionDomain().getCodeSource().getLocation()).contains("test-classes")) {
                continue; // production listeners only (tests declare their own probe listeners)
            }
            for (Method m : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(m, EventListener.class)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static boolean frameworkEvent(Method m) {
        return m.getParameterCount() == 1 && ApplicationEvent.class.isAssignableFrom(m.getParameterTypes()[0])
                && m.getParameterTypes()[0].getName().startsWith("org.springframework.");
    }

    @Test
    void everyApplicationEventListenerRunsAfterCommitOnly() throws Exception {
        List<Method> listeners = listenerMethods();
        assertThat(listeners).as("the scan found the listeners").hasSizeGreaterThanOrEqualTo(10);
        List<String> offenders = new ArrayList<>();
        for (Method m : listeners) {
            TransactionalEventListener tel = AnnotatedElementUtils.findMergedAnnotation(m, TransactionalEventListener.class);
            String name = m.getDeclaringClass().getSimpleName() + "#" + m.getName();
            if (tel == null) {
                if (!frameworkEvent(m)) {
                    offenders.add(name + " is a plain @EventListener for an application payload");
                }
            } else if (tel.phase() != TransactionPhase.AFTER_COMMIT) {
                offenders.add(name + " runs in phase " + tel.phase());
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void mailListenersAreNotAsyncThemselvesTheyUseTheMailDispatcher() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Method m : listenerMethods()) {
            Class<?> owner = m.getDeclaringClass();
            boolean mail = owner == SuccessfulOrderHandler.class || owner == OrderStatusHandler.class
                    || owner == OTPEmailSendHandler.class || owner == ForgotPasswordHandler.class;
            if (mail && AnnotatedElementUtils.hasAnnotation(m, Async.class)) {
                offenders.add(owner.getSimpleName() + "#" + m.getName());
            }
            if (mail && !List.of(owner.getDeclaredConstructors()[0].getParameterTypes()).contains(MailDispatcher.class)) {
                offenders.add(owner.getSimpleName() + " has no MailDispatcher");
            }
        }
        assertThat(offenders).isEmpty();
    }
}
