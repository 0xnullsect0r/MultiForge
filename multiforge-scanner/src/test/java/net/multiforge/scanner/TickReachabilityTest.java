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
package net.multiforge.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

class TickReachabilityTest {

    private static ClassNode classWith(String... handlerParamTypes) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/Mod", null, "java/lang/Object", null);
        int i = 0;
        for (String type : handlerParamTypes) {
            MethodVisitor mv = cw.visitMethod(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "handler" + i++, "(L" + type + ";)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    @Test
    void neoForge21NestedTickEventsAreSeedsWithoutAnnotation() {
        // IEventBus.addListener(Mod::handler) — no @SubscribeEvent, nested Pre/Post event types.
        ClassNode cn = classWith(
                "net/neoforged/neoforge/event/tick/EntityTickEvent$Post",
                "net/neoforged/neoforge/event/tick/LevelTickEvent$Pre",
                "net/neoforged/neoforge/event/tick/ServerTickEvent",
                "net/neoforged/neoforge/event/entity/living/LivingDamageEvent$Post");
        assertThat(TickReachability.compute(cn))
                .contains(
                        "handler0(Lnet/neoforged/neoforge/event/tick/EntityTickEvent$Post;)V",
                        "handler1(Lnet/neoforged/neoforge/event/tick/LevelTickEvent$Pre;)V",
                        "handler2(Lnet/neoforged/neoforge/event/tick/ServerTickEvent;)V")
                .doesNotContain("handler3(Lnet/neoforged/neoforge/event/entity/living/LivingDamageEvent$Post;)V");
    }

    @Test
    void recognisesTickEventNames() {
        assertThat(TickReachability.isTickEvent("net/neoforged/neoforge/event/tick/PlayerTickEvent$Post"))
                .isTrue();
        assertThat(TickReachability.isTickEvent("net/minecraftforge/event/TickEvent$ServerTickEvent"))
                .isTrue();
        assertThat(TickReachability.isTickEvent("com/example/StickEvent")).isFalse();
        assertThat(TickReachability.isTickEvent("net/neoforged/neoforge/event/level/BlockEvent$BreakEvent"))
                .isFalse();
    }

    @Test
    void everyMethodOfARegionThreadClassIsReachable() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/Worker", null, "java/lang/Object", null);
        cw.visitAnnotation(TickReachability.REGION_THREAD_DESC, true).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "work", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        assertThat(TickReachability.compute(cn)).contains("work()V");
    }
}
