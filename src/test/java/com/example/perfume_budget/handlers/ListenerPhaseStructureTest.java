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

    // ---- EVT-01 rework 1 (F1): no after-commit work on the publishing thread

    /** Owners whose methods an inline (non-@Async) after-commit listener may call: hand-off and logging only. */
    private static boolean handOffOrLogging(String owner) {
        return owner.equals(org.springframework.asm.Type.getInternalName(MailDispatcher.class))
                || owner.startsWith("org/slf4j/") || owner.equals("java/lang/Object") || owner.equals("java/lang/String")
                || owner.equals("java/lang/StringBuilder") || owner.equals("java/util/Objects");
    }

    /**
     * Calls made by the body of {@code m} itself (lambda bodies are separate synthetic methods, run later by the
     * executor, and are not included).
     */
    private static List<String> callsIn(Method m) throws java.io.IOException {
        String resource = m.getDeclaringClass().getName().replace('.', '/') + ".class";
        List<String> calls = new ArrayList<>();
        try (java.io.InputStream in = m.getDeclaringClass().getClassLoader().getResourceAsStream(resource)) {
            String desc = org.springframework.asm.Type.getMethodDescriptor(m);
            new org.springframework.asm.ClassReader(in).accept(new org.springframework.asm.ClassVisitor(org.springframework.asm.SpringAsmInfo.ASM_VERSION) {
                @Override
                public org.springframework.asm.MethodVisitor visitMethod(int access, String name, String d, String sig, String[] ex) {
                    if (!name.equals(m.getName()) || !d.equals(desc)) {
                        return null;
                    }
                    return new org.springframework.asm.MethodVisitor(org.springframework.asm.SpringAsmInfo.ASM_VERSION) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                            calls.add(owner + "#" + mname);
                        }
                    };
                }
            }, 0);
        }
        return calls;
    }

    /**
     * A listener that runs inline in the publishing thread's after-commit callback still has that thread's committed
     * transaction and JDBC connection bound: any REQUIRED transaction it starts joins the dead transaction (its writes
     * are never committed, its after-commit hooks never fire) and anything slow it does holds the request's
     * connection. So every after-commit listener is either {@link Async} (a worker thread), or its own body only
     * hands work to {@link MailDispatcher} (and logs); repositories, e-mail and notification services are called only
     * inside the submitted work.
     */
    @Test
    void afterCommitListenersRunNothingOnThePublishingThreadButAHandOff() throws Exception {
        List<String> offenders = new ArrayList<>();
        int inline = 0;
        for (Method m : listenerMethods()) {
            if (AnnotatedElementUtils.findMergedAnnotation(m, TransactionalEventListener.class) == null
                    || AnnotatedElementUtils.hasAnnotation(m, Async.class)) {
                continue;
            }
            inline++;
            for (String call : callsIn(m)) {
                if (!handOffOrLogging(call.substring(0, call.indexOf('#')))) {
                    offenders.add(m.getDeclaringClass().getSimpleName() + "#" + m.getName() + " calls " + call);
                }
            }
            if (callsIn(m).stream().noneMatch(c -> c.startsWith(org.springframework.asm.Type.getInternalName(MailDispatcher.class) + "#submit"))) {
                offenders.add(m.getDeclaringClass().getSimpleName() + "#" + m.getName() + " hands nothing off");
            }
        }
        assertThat(inline).as("the inline (mail) listeners were inspected").isGreaterThanOrEqualTo(6);
        assertThat(offenders).isEmpty();
    }

    /** An @Async after-commit listener that writes does so in a transaction of its own, never a joined one. */
    @Test
    void transactionalAfterCommitListenersUseANewTransaction() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Method m : listenerMethods()) {
            org.springframework.transaction.annotation.Transactional t = AnnotatedElementUtils.findMergedAnnotation(m,
                    org.springframework.transaction.annotation.Transactional.class);
            if (t != null && AnnotatedElementUtils.hasAnnotation(m, TransactionalEventListener.class)
                    && t.propagation() != org.springframework.transaction.annotation.Propagation.REQUIRES_NEW) {
                offenders.add(m.getDeclaringClass().getSimpleName() + "#" + m.getName());
            }
        }
        assertThat(offenders).isEmpty();
    }

    /** Services called from after-commit work open their own transaction (REQUIRES_NEW), never join one. */
    @Test
    void notificationWritesCalledAfterCommitUseANewTransaction() throws Exception {
        List<String> checked = new ArrayList<>();
        for (Method m : com.example.perfume_budget.config.ws.NotificationService.class.getDeclaredMethods()) {
            org.springframework.transaction.annotation.Transactional t = AnnotatedElementUtils.findMergedAnnotation(m,
                    org.springframework.transaction.annotation.Transactional.class);
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && t != null) {
                assertThat(t.propagation()).as(m.getName()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
                checked.add(m.getName());
            }
        }
        assertThat(checked).contains("notifyAdminsOfSuccessfulPayment", "notifyStaffOfShopFloorShortage");
    }
}
