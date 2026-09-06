/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.runtime.event;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.api.event.OrderingContract;
import net.multiforge.runtime.event.AnnotationScanner.MetadataEntry;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * Transparent wrapper around a real {@link IEventBus} that routes each
 * registered listener's invocation through {@link DomainDispatcher}
 * according to its {@code @DispatchDomain}/{@code @Ordering} annotations.
 * See {@code docs/design/m12-event-routing.md} for the full design.
 *
 * <p>Every {@link IEventBus} method delegates straight to the wrapped
 * {@code inner} bus <b>except</b> {@link #register(Object)} and the {@code
 * addListener(...)} family — the entry points where a listener becomes
 * observable to the bus. {@code register}/{@code addListener} instead
 * introspect the listener (where a {@link Method} is available) and
 * install a {@link RoutingListenerWrapper}-backed consumer on {@code
 * inner} in place of the raw one.
 *
 * <p><b>Why {@code register} can't just delegate to {@code
 * inner.register(target)}:</b> the concrete {@code
 * net.neoforged.bus.EventBus}'s {@code registerListener(Object, Method,
 * Method)} — the method that would actually build the dispatch {@code
 * Consumer} for each {@code @SubscribeEvent} method — is {@code private}.
 * {@link IEventBus} exposes no seam to intercept what it builds. So {@code
 * register} here performs its own equivalent reflective scan and installs
 * wrapped consumers directly via {@code inner.addListener(...)} instead.
 *
 * <p><b>Why the {@code addListener(Consumer)} family can't be
 * annotation-routed:</b> a bare {@code Consumer<T>} lambda/method
 * reference carries no {@link Method} to read annotations off. Those
 * overloads are still wrapped (so they get the same {@link
 * DomainDispatcher} routing, ordering, and probe/warn behavior as every
 * other listener) but with a fixed {@code LEGACY_SERIAL}/{@code
 * PER_REGION} metadata — indistinguishable from an unannotated {@code
 * @SubscribeEvent} handler.
 */
public class DispatchingEventBus implements IEventBus {

    /** {@code -Dmultiforge.event-dispatch=off} disables routing — see {@link #isEnabled()}. */
    public static final String DISABLE_PROPERTY = "multiforge.event-dispatch";

    private static final MetadataEntry LEGACY_SERIAL_DEFAULT =
            new MetadataEntry(DispatchDomainKind.LEGACY_SERIAL, OrderingContract.PER_REGION);

    private final IEventBus inner;
    private final DomainDispatcher dispatcher;
    private final boolean enabled;

    public DispatchingEventBus(IEventBus inner, DispatchExecutor executor) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.dispatcher = new DomainDispatcher(Objects.requireNonNull(executor, "executor"));
        this.enabled = !"off".equalsIgnoreCase(System.getProperty(DISABLE_PROPERTY));
    }

    /**
     * Whether {@code -Dmultiforge.event-dispatch=off} was set at
     * construction time. This instance still routes regardless of this
     * flag's value — the flag is read once, here, purely so the fork
     * bridge (which decides whether to construct/install a {@code
     * DispatchingEventBus} at all) has a single accessor to consult. See
     * {@code docs/design/m12-event-routing.md} §8.
     */
    public boolean isEnabled() {
        return enabled;
    }

    // ---------------------------------------------------------------
    // register(Object) — the @SubscribeEvent reflective-scan path.
    // ---------------------------------------------------------------

    @Override
    public void register(Object target) {
        Objects.requireNonNull(target, "target");
        boolean staticOnly = target instanceof Class<?>;
        Class<?> scanClass = staticOnly ? (Class<?>) target : target.getClass();
        Object receiver = staticOnly ? null : target;

        Set<String> seen = new HashSet<>();
        for (Class<?> cls = scanClass; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Method method : cls.getDeclaredMethods()) {
                SubscribeEvent ann = method.getAnnotation(SubscribeEvent.class);
                if (ann == null || method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                if (staticOnly && !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                // De-dup by erased signature so an overridden method is registered
                // only once, from the most-derived class encountered first.
                if (!seen.add(method.getName() + Arrays.toString(method.getParameterTypes()))) {
                    continue;
                }
                registerSubscribeEventMethod(method, ann, receiver);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> void registerSubscribeEventMethod(Method method, SubscribeEvent ann, Object receiver) {
        if (method.getParameterCount() != 1) {
            throw new IllegalArgumentException(
                    "@SubscribeEvent method " + method + " must declare exactly one parameter");
        }
        Class<?> paramType = method.getParameterTypes()[0];
        if (!Event.class.isAssignableFrom(paramType)) {
            throw new IllegalArgumentException(
                    "@SubscribeEvent method " + method + " parameter must extend " + Event.class.getName());
        }
        Class<T> eventClass = (Class<T>) paramType.asSubclass(Event.class);
        method.setAccessible(true);

        MetadataEntry metadata = AnnotationScanner.scan(method);
        Consumer<T> raw = event -> invokeReflectively(method, receiver, event);
        Consumer<T> wrapped = new RoutingListenerWrapper<>(raw, metadata, dispatcher);
        inner.addListener(ann.priority(), ann.receiveCanceled(), eventClass, wrapped);
    }

    private static void invokeReflectively(Method method, Object receiver, Object event) {
        try {
            method.invoke(receiver, event);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke @SubscribeEvent method " + method, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error er) {
                throw er;
            }
            throw new RuntimeException(cause);
        }
    }

    // ---------------------------------------------------------------
    // addListener(...) family — wrapped with the LEGACY_SERIAL default,
    // since a bare Consumer carries no Method to scan annotations off.
    // ---------------------------------------------------------------

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        inner.addListener(wrapLegacy(consumer));
    }

    @Override
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        inner.addListener(eventType, wrapLegacy(consumer));
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        inner.addListener(priority, wrapLegacy(consumer));
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Class<T> eventType, Consumer<T> consumer) {
        inner.addListener(priority, eventType, wrapLegacy(consumer));
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Consumer<T> consumer) {
        inner.addListener(priority, receiveCanceled, wrapLegacy(consumer));
    }

    @Override
    public <T extends Event> void addListener(
            EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        inner.addListener(priority, receiveCanceled, eventType, wrapLegacy(consumer));
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Consumer<T> consumer) {
        inner.addListener(receiveCanceled, wrapLegacy(consumer));
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        inner.addListener(receiveCanceled, eventType, wrapLegacy(consumer));
    }

    private <T extends Event> Consumer<T> wrapLegacy(Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        return new RoutingListenerWrapper<>(consumer, LEGACY_SERIAL_DEFAULT, dispatcher);
    }

    // ---------------------------------------------------------------
    // Straight pass-through.
    // ---------------------------------------------------------------

    @Override
    public void unregister(Object target) {
        inner.unregister(target);
    }

    @Override
    public <T extends Event> T post(T event) {
        return inner.post(event);
    }

    @Override
    public <T extends Event> T post(EventPriority priority, T event) {
        return inner.post(priority, event);
    }

    @Override
    public void start() {
        inner.start();
    }
}
