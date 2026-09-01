/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    kevin - 屏蔽反射感知到的 jacoco 插桩字段（$jacocoData）
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.Arrays;

import org.jacoco.core.internal.instr.InstrSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 屏蔽反射感知到的 jacoco 插桩字段（{@link InstrSupport#DATAFIELD_NAME}）： 插桩会给被插桩类注入静态
 * synthetic 字段 {@code $jacocoData}，业务代码/框架 通过反射遍历
 * {@code Class.getDeclaredFields()} 时会把该字段当作业务字段处理 （序列化、Bean 拷贝、反射工具等出现污染）。
 * <p>
 * 作为 {@link ClassFileTransformer} 在 jacoco 插桩链之后运行：把插桩后 字节码中的
 * {@code Class.getDeclaredFields()} 调用点改写为 {@link #getDeclaredFields(Class)} ——
 * 仅过滤掉 jacoco 注入字段， 其余字段（含 CGLIB 等代理生成的合成字段）原样返回，相比 jacoco-czx 的"过滤全部
 * synthetic"更精准、更少副作用。
 * <p>
 * 改写发生时探针布局已完成（本 module 不触碰 org.jacoco.core）， 且 INVOKEVIRTUAL → INVOKESTATIC 的
 * receiver 原地转为显式参数， 操作数栈形状不变，不影响已有栈帧。
 */
public final class DeclaredFieldsRewriter implements ClassFileTransformer {

	private static final String OWNER = "java/lang/Class";

	private static final String NAME = "getDeclaredFields";

	private static final String DESC = "()[Ljava/lang/reflect/Field;";

	@Override
	public byte[] transform(final ClassLoader loader, final String className,
			final Class<?> classBeingRedefined,
			final ProtectionDomain protectionDomain,
			final byte[] classfileBuffer) throws IllegalClassFormatException {
		if (className == null) {
			return null;
		}
		try {
			final ClassReader reader = new ClassReader(classfileBuffer);
			final ClassWriter writer = new ClassWriter(reader, 0);
			final boolean[] rewritten = { false };
			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {

				@Override
				public MethodVisitor visitMethod(final int access,
						final String name, final String descriptor,
						final String signature, final String[] exceptions) {
					return new MethodVisitor(Opcodes.ASM9, super.visitMethod(
							access, name, descriptor, signature, exceptions)) {

						@Override
						public void visitMethodInsn(final int opcode,
								final String owner, final String name,
								final String descriptor, final boolean itf) {
							if (opcode == Opcodes.INVOKEVIRTUAL
									&& OWNER.equals(owner) && NAME.equals(name)
									&& DESC.equals(descriptor)) {
								rewritten[0] = true;
								super.visitMethodInsn(Opcodes.INVOKESTATIC,
										DeclaredFieldsRewriter.class.getName()
												.replace('.', '/'),
										NAME,
										"(Ljava/lang/Class;)[Ljava/lang/reflect/Field;",
										false);
							} else {
								super.visitMethodInsn(opcode, owner, name,
										descriptor, itf);
							}
						}
					};
				}
			}, 0);
			return rewritten[0] ? writer.toByteArray() : null;
		} catch (RuntimeException e) {
			// 字节码异常时保持原样：改写失败不得破坏该类的加载
			return null;
		}
	}

	/**
	 * 被插桩类的 {@code getDeclaredFields()} 替代实现： 返回的字段数组中剔除 jacoco 注入字段。
	 *
	 * @param clazz
	 *            反射源类
	 * @return 去掉 {@code $jacocoData} 后的字段数组
	 */
	public static Field[] getDeclaredFields(final Class<?> clazz) {
		final Field[] fields = clazz.getDeclaredFields();
		for (int i = 0; i < fields.length; i++) {
			if (InstrSupport.DATAFIELD_NAME.equals(fields[i].getName())) {
				return Arrays.stream(fields).filter(
						f -> !f.getName().equals(InstrSupport.DATAFIELD_NAME))
						.toArray(Field[]::new);
			}
		}
		// 无 jacoco 注入字段（未插桩类）：原样返回，避免常见类无谓的数组重建
		return fields;
	}

}
