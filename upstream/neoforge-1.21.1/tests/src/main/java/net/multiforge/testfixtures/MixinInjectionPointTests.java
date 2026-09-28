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
package net.multiforge.testfixtures;

import java.io.InputStream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * GameTests for injection points mods target inside patched Vanilla methods.
 * {@code :neoforge:checkMixinTargets} proves the methods and lambdas are still
 * there; this checks call sites a real modpack injects at.
 */
@ForEachTest(groups = "multiforge.mixins")
public class MixinInjectionPointTests {
    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "ServerLevel.sendBlockUpdated still calls Set.iterator(), as Vanilla's loop over navigatingMobs does:",
            "Immersive Engineering's wire collisions inject there (INVOKE Ljava/util/Set;iterator())."
    })
    static void sendBlockUpdatedStillIteratesASet(final DynamicTest test) {
        test.onGameTest(helper -> {
            int[] setIterators = new int[1];
            try (InputStream in = ServerLevel.class.getClassLoader()
                    .getResourceAsStream("net/minecraft/server/level/ServerLevel.class")) {
                helper.assertTrue(in != null, "ServerLevel.class not readable");
                new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if (!name.equals("sendBlockUpdated")) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                                if (owner.equals("java/util/Set") && method.equals("iterator")
                                        && desc.equals("()Ljava/util/Iterator;")) {
                                    setIterators[0]++;
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG);
            } catch (java.io.IOException e) {
                helper.fail("reading ServerLevel.class: " + e);
            }
            helper.assertTrue(setIterators[0] == 1,
                    "sendBlockUpdated calls Set.iterator() " + setIterators[0] + " time(s), Vanilla once");
            helper.succeed();
        });
    }
}
