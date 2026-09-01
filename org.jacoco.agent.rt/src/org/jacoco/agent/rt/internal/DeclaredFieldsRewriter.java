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
 * <p>
 * 只改写"目标类加载器可解析到 agent runtime（本类）"的类：bootstrap 类（loader 为 null，如 JDK 的
 * java.io.ObjectStreamClass）与隔离类加载器（如 OTel AgentClassLoader）均解析不到 runtime
 * 类，改写出的 INVOKESTATIC 运行时必然 NoClassDefFoundError——前者导致 JDK 序列化路径全局崩溃（应用启动失败），
 * 后者导致 OTel 所有插桩模块加载失败。
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
		// 与 jacoco 插桩器 CoverageTransformer#filter 一致地跳过 bootstrap 类
		// （inclbootstrapclasses 默认关闭）；隔离类加载器同理跳过
		if (loader == null || !isRuntimeVisibleTo(loader)) {
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
	 * 判断目标加载器能否解析到 agent runtime（本类）：改写出的 INVOKESTATIC 由被改写类所在加载器解析，解析不到即运行时
	 * NoClassDefFoundError。
	 *
	 * @param loader
	 *            目标类的加载器（已确保非 null）
	 * @return runtime 类对目标加载器可见时返回 <code>true</code>
	 */
	private static boolean isRuntimeVisibleTo(final ClassLoader loader) {
		try {
			Class.forName(DeclaredFieldsRewriter.class.getName(), false,
					loader);
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
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
