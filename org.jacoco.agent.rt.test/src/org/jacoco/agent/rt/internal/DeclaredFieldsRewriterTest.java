/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    kevin - $jacocoData 反射屏蔽后处理改写测试
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.instrument.IllegalClassFormatException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Unit tests for {@link DeclaredFieldsRewriter}.
 */
public class DeclaredFieldsRewriterTest {

	@Test
	public void testGetDeclaredFieldsCallIsRewritten()
			throws IllegalClassFormatException {
		byte[] result = new DeclaredFieldsRewriter().transform(
				getClass().getClassLoader(), "SampleBean", null, null,
				sampleClassBytes("getDeclaredFields",
						"()[Ljava/lang/reflect/Field;"));

		assertEquals(Opcodes.INVOKESTATIC,
				methodInsnInsns(result, "getDeclaredFields").get(0).opcode);
		assertEquals(DeclaredFieldsRewriter.class.getName().replace('.', '/'),
				methodInsnInsns(result, "getDeclaredFields").get(0).owner);
		assertEquals("(Ljava/lang/Class;)[Ljava/lang/reflect/Field;",
				methodInsnInsns(result, "getDeclaredFields").get(0).descriptor);
	}

	@Test
	public void testOtherMethodInsnsAreNotRewritten()
			throws IllegalClassFormatException {
		byte[] result = new DeclaredFieldsRewriter().transform(
				getClass().getClassLoader(), "SampleBean", null, null,
				sampleClassBytes("getDeclaredField",
						"(Ljava/lang/String;)Ljava/lang/reflect/Field;"));

		// 非目标调用点：不改写（返回 null，不产生新字节码）
		assertNull(result);
	}

	@Test
	public void testTransformReturnsNullWithoutRewrite()
			throws IllegalClassFormatException {
		byte[] result = new DeclaredFieldsRewriter().transform(
				getClass().getClassLoader(), "SampleBean", null, null,
				plainClassBytes());

		assertNull(result);
	}

	/**
	 * bootstrap 类（loader 为 null，如 JDK 的 java.io.ObjectStreamClass）不可 改写：agent
	 * runtime 类对 bootstrap 类加载器不可见，改写后其 INVOKESTATIC 必然 NoClassDefFoundError（线上
	 * Spring 启动即因 ObjectStreamClass 被改写而失败）。 与 jacoco 插桩器默认跳过 bootstrap 类
	 * （CoverageTransformer#filter，inclbootstrapclasses 默认关闭）保持一致。
	 */
	@Test
	public void testBootstrapClassLoaderIsNotRewritten()
			throws IllegalClassFormatException {
		byte[] result = new DeclaredFieldsRewriter().transform(null,
				"SampleBean", null, null, sampleClassBytes("getDeclaredFields",
						"()[Ljava/lang/reflect/Field;"));

		assertNull(result);
	}

	/**
	 * 隔离类加载器（如 OTel AgentClassLoader，其父链上不含 agent runtime）： 改写出的 INVOKESTATIC
	 * 同样解析不到 DeclaredFieldsRewriter，应跳过， 否则 OTel 各类插桩模块全部加载失败（Unable to load
	 * instrumentation）。
	 */
	@Test
	public void testIsolatedClassLoaderIsNotRewritten()
			throws IllegalClassFormatException {
		final ClassLoader isolated = new URLClassLoader(new URL[0], null);
		byte[] result = new DeclaredFieldsRewriter().transform(isolated,
				"SampleBean", null, null, sampleClassBytes("getDeclaredFields",
						"()[Ljava/lang/reflect/Field;"));

		assertNull(result);
	}

	/**
	 * 过滤方法：$jacocoData 字段被剔除，普通字段保留。
	 */
	@Test
	public void testGetDeclaredFieldsFiltersJacocoField() {
		final Field[] fields = DeclaredFieldsRewriter
				.getDeclaredFields(MockBean.class);
		assertTrue(Arrays.stream(fields)
				.allMatch(f -> !f.getName().equals("$jacocoData")));
		assertTrue(Arrays.stream(fields)
				.anyMatch(f -> f.getName().equals("regular")));
	}

	/**
	 * 畸形字节码不得抛异常（改写失败不得破坏该类的加载）。
	 */
	@Test
	public void testMalformedClassReturnsNull()
			throws IllegalClassFormatException {
		byte[] result = new DeclaredFieldsRewriter().transform(null,
				"SampleBean", null, null, new byte[] { 1, 2, 3 });

		assertNull(result);
	}

	/**
	 * 无 jacoco 注入字段时返回全部字段（不剔除）。
	 */
	@Test
	public void testGetDeclaredFieldsWithoutJacocoFieldKeepsAll() {
		final Field[] fields = DeclaredFieldsRewriter
				.getDeclaredFields(PlainBean.class);
		assertEquals(1, fields.length);
		assertEquals("regular", fields[0].getName());
	}

	private static class MockBean {
		@SuppressWarnings("unused")
		static final String $jacocoData = "jacoco-injected";

		@SuppressWarnings("unused")
		String regular;
	}

	private static class PlainBean {
		@SuppressWarnings("unused")
		String regular;
	}

	/**
	 * 构造含 {callName} 调用的简单类字节码： {@code sample() { Class c = this.getClass();
	 * c.callName(); pop; return } }
	 */
	private static byte[] sampleClassBytes(final String callName,
			final String callDescriptor) {
		final ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "SampleBean", null,
				"java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "sample", "()V",
				null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object",
				"getClass", "()Ljava/lang/Class;", false);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", callName,
				callDescriptor, false);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * 构造无任何方法调用的简单类字节码。
	 */
	private static byte[] plainClassBytes() {
		final ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "SampleBean", null,
				"java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "sample", "()V",
				null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * 读取结果字节码中名为 {@code name} 的调用点记录。
	 */
	private static List<MethodInsn> methodInsnInsns(final byte[] bytes,
			final String name) {
		final List<MethodInsn> insns = new ArrayList<>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {

			@Override
			public MethodVisitor visitMethod(final int access,
					final String name, final String descriptor,
					final String signature, final String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {

					@Override
					public void visitMethodInsn(final int opcode,
							final String owner, final String name,
							final String descriptor, final boolean itf) {
						insns.add(new MethodInsn(opcode, owner, name,
								descriptor));
					}

				};
			}
		}, 0);
		final List<MethodInsn> result = new ArrayList<>();
		for (MethodInsn insn : insns) {
			if (name.equals(insn.name)) {
				result.add(insn);
			}
		}
		return result;
	}

	private static class MethodInsn {
		final int opcode;
		final String owner;
		final String name;
		final String descriptor;

		MethodInsn(final int opcode, final String owner, final String name,
				final String descriptor) {
			this.opcode = opcode;
			this.owner = owner;
			this.name = name;
			this.descriptor = descriptor;
		}
	}

}
