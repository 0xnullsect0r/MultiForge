/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
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
import net.multiforge.runtime.event.AnnotationScanner.MetadataEntry;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * Wrapper around a real {@link IEventBus} that routes each listener's
 * invocation through {@link DomainDispatcher}, according to its {@code
 * @DispatchDomain}/{@code @Ordering} annotations, the event type's default
 * domain ({@link EventTypeDomainMap}) and the owning mod's {@link ModSafety}.
 * See docs/events.md.
 *
 * <p>{@link #register(Object)} performs its own {@code @SubscribeEvent} scan
 * (the concrete bus builds its listener consumers in a private method) and
 * installs a {@link RoutingListenerWrapper} per method. The {@code
 * addListener} overloads wrap the caller's consumer too; for the overloads
 * without an explicit event type the type is resolved from the consumer's
 * generic signature exactly as the bus itself does, and passed explicitly,
 * because the bus cannot resolve it from the wrapper. A registration map
 * lets {@link #unregister} remove the wrappers installed for an object.
 */
public class DispatchingEventBus implements IEventBus {

    /** {@code -Dmultiforge.event-dispatch=off} disables routing — see {@link #isEnabled()}. */
    public static final String DISABLE_PROPERTY = "multiforge.event-dispatch";

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
        Consumer<T> wrapped = new RoutingListenerWrapper<>(raw, metadata, dispatcher, method.getDeclaringClass());
        inner.addListener(ann.priority(), ann.receiveCanceled(), eventClass, wrapped);
        track(receiver != null ? receiver : method.getDeclaringClass(), wrapped);
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
    // addListener(...) family — wrapped like @SubscribeEvent listeners.
    // ---------------------------------------------------------------

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        addListener(EventPriority.NORMAL, false, eventTypeOf(consumer), consumer);
    }

    @Override
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        addListener(EventPriority.NORMAL, false, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        addListener(priority, false, eventTypeOf(consumer), consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Class<T> eventType, Consumer<T> consumer) {
        addListener(priority, false, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCanceled, Consumer<T> consumer) {
        addListener(priority, receiveCanceled, eventTypeOf(consumer), consumer);
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Consumer<T> consumer) {
        addListener(EventPriority.NORMAL, receiveCanceled, eventTypeOf(consumer), consumer);
    }

    @Override
    public <T extends Event> void addListener(boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        addListener(EventPriority.NORMAL, receiveCanceled, eventType, consumer);
    }

    @Override
    public <T extends Event> void addListener(
            EventPriority priority, boolean receiveCanceled, Class<T> eventType, Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(eventType, "eventType");
        Consumer<T> wrapped = new RoutingListenerWrapper<>(
                consumer, AnnotationScanner.forUnannotated(eventType), dispatcher, consumer.getClass());
        inner.addListener(priority, receiveCanceled, eventType, wrapped);
        track(consumer, wrapped);
    }

    /** The event type of {@code consumer}, resolved from its generic signature as the bus does. */
    @SuppressWarnings("unchecked")
    static <T extends Event> Class<T> eventTypeOf(Consumer<T> consumer) {
        Class<?> type = net.jodah.typetools.TypeResolver.resolveRawArgument(Consumer.class, consumer.getClass());
        if (type == net.jodah.typetools.TypeResolver.Unknown.class || !Event.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Failed to resolve consumer event type: " + consumer);
        }
        return (Class<T>) type;
    }

    // Registered object (listener instance, class, or consumer) -> the
    // wrappers installed on the inner bus for it.
    private final java.util.Map<Object, java.util.List<Consumer<?>>> registrations = new java.util.IdentityHashMap<>();

    private void track(Object owner, Consumer<?> wrapped) {
        synchronized (registrations) {
            registrations
                    .computeIfAbsent(owner, k -> new java.util.ArrayList<>())
                    .add(wrapped);
        }
    }

    // ---------------------------------------------------------------
    // Straight pass-through.
    // ---------------------------------------------------------------

    @Override
    public void unregister(Object target) {
        java.util.List<Consumer<?>> wrappers;
        synchronized (registrations) {
            wrappers = registrations.remove(target);
        }
        if (wrappers != null) {
            for (Consumer<?> w : wrappers) inner.unregister(w);
        }
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
