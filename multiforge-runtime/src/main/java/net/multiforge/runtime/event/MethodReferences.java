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

import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the declared {@link Method} behind a method reference ({@code
 * this::onChat}) handed to {@code addListener}, so {@link AnnotationScanner}
 * can read {@code @DispatchDomain}/{@code @Ordering} off it and {@link
 * ModSafety}/{@link ListenerAffinity} can consult the declaring class — the
 * reflective {@code @SubscribeEvent} scan always saw the method, but the
 * {@code addListener} family treated every consumer as an opaque lambda and
 * silently ignored all of that.
 *
 * <p>The mechanism: javac gives lambda and method-reference classes a private
 * {@code writeReplace} whose {@link SerializedLambda} names the target class,
 * method and erased signature — but only when the functional interface is
 * {@link Serializable}, which plain {@code java.util.function.Consumer} is
 * not. {@link net.multiforge.api.event.EventConsumer} exists exactly for
 * this: casting a method reference to it ({@code
 * bus.addListener((EventConsumer<MyEvent>) this::onMyEvent)}) opts into the
 * metadata without changing anything else. A plain lambda resolves to a
 * synthetic {@code lambda$...} method — no annotations to read, so it
 * declines; so does anything unresolvable (other compilers, exotic
 * classloaders, constructor references, a target renamed by a transform).
 * Registration must never fail because this could not see through the
 * consumer (ground rule 5): every failure path returns {@code null} and the
 * caller keeps today's unannotated-listener behaviour.
 */
@ApiStatus.Internal
final class MethodReferences {

    private MethodReferences() {}

    /**
     * The declared (non-synthetic, non-bridge) method {@code consumer} is a
     * direct reference to, or {@code null} when {@code consumer} is a lambda,
     * a constructor reference, or anything this cannot see through.
     */
    static <T> @Nullable Method declaredMethodBehind(Consumer<T> consumer) {
        SerializedLambda serialized = serializedLambda(consumer);
        if (serialized == null) return null;
        int kind = serialized.getImplMethodKind();
        if (kind != MethodHandleInfo.REF_invokeVirtual
                && kind != MethodHandleInfo.REF_invokeStatic
                && kind != MethodHandleInfo.REF_invokeSpecial
                && kind != MethodHandleInfo.REF_invokeInterface) {
            return null; // constructor and field references cannot be annotated listeners
        }
        try {
            ClassLoader loader = consumer.getClass().getClassLoader();
            Class<?> implClass = Class.forName(serialized.getImplClass().replace('/', '.'), false, loader);
            MethodType signature = MethodType.fromMethodDescriptorString(serialized.getImplMethodSignature(), loader);
            Method method = implClass.getDeclaredMethod(serialized.getImplMethodName(), signature.parameterArray());
            if (method.isSynthetic() || method.isBridge()) return null; // a lambda$... body, not a method reference
            return method;
        } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError | SecurityException e) {
            return null;
        }
    }

    private static @Nullable SerializedLambda serializedLambda(Object consumer) {
        try {
            Method writeReplace = consumer.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            return writeReplace.invoke(consumer) instanceof SerializedLambda serialized ? serialized : null;
        } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError | SecurityException e) {
            return null;
        }
    }
}
