/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.core.internal.flow;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.jacoco.core.internal.instr.InstrSupport;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AnalyzerAdapter;

/**
 * Adapter that creates additional visitor events for probes to be inserted into
 * a method.
 */
public final class MethodProbesAdapter extends MethodVisitor {

	private final MethodProbesVisitor probesVisitor;

	private final IProbeIdGenerator idGenerator;

	private AnalyzerAdapter analyzer;

	private final Map<Label, Label> tryCatchProbeLabels;

	/**
	 * Create a new adapter instance.
	 *
	 * @param probesVisitor
	 *            visitor to delegate to
	 * @param idGenerator
	 *            generator for unique probe ids
	 */
	public MethodProbesAdapter(final MethodProbesVisitor probesVisitor,
			final IProbeIdGenerator idGenerator) {
		super(InstrSupport.ASM_API_VERSION, probesVisitor);
		this.probesVisitor = probesVisitor;
		this.idGenerator = idGenerator;
		this.tryCatchProbeLabels = new HashMap<Label, Label>();
	}

	/**
	 * If an analyzer is set {@link IFrame} handles are calculated and emitted
	 * to the probes methods.
	 *
	 * @param analyzer
	 *            optional analyzer to set
	 */
	public void setAnalyzer(final AnalyzerAdapter analyzer) {
		this.analyzer = analyzer;
	}

	@Override
	public void visitTryCatchBlock(final Label start, final Label end,
			final Label handler, final String type) {
		probesVisitor.visitTryCatchBlock(getTryCatchLabel(start),
				getTryCatchLabel(end), handler, type);
	}

	private Label getTryCatchLabel(Label label) {
		if (tryCatchProbeLabels.containsKey(label)) {
			label = tryCatchProbeLabels.get(label);
		} else if (LabelInfo.needsProbe(label)) {
			// If a probe will be inserted before the label, we'll need to use a
			// different label to define the range of the try-catch block.
			final Label probeLabel = new Label();
			LabelInfo.setSuccessor(probeLabel);
			tryCatchProbeLabels.put(label, probeLabel);
			label = probeLabel;
		}
		return label;
	}

	@Override
	public void visitLabel(final Label label) {
		if (LabelInfo.needsProbe(label)) {
			if (tryCatchProbeLabels.containsKey(label)) {
				probesVisitor.visitLabel(tryCatchProbeLabels.get(label));
			}
			probesVisitor.visitProbe(idGenerator.nextId());
		}
		probesVisitor.visitLabel(label);
	}

	@Override
	public void visitInsn(final int opcode) {
		switch (opcode) {
		case Opcodes.IRETURN:
		case Opcodes.LRETURN:
		case Opcodes.FRETURN:
		case Opcodes.DRETURN:
		case Opcodes.ARETURN:
		case Opcodes.RETURN:
		case Opcodes.ATHROW:
			probesVisitor.visitInsnWithProbe(opcode, idGenerator.nextId());
			break;
		default:
			probesVisitor.visitInsn(opcode);
			break;
		}
	}

	@Override
	public void visitJumpInsn(final int opcode, final Label label) {
		if (LabelInfo.isMultiTarget(label)) {
			probesVisitor.visitJumpInsnWithProbe(opcode, label,
					idGenerator.nextId(), frame(jumpPopCount(opcode)));
		} else {
			probesVisitor.visitJumpInsn(opcode, label);
		}
	}

	private int jumpPopCount(final int opcode) {
		switch (opcode) {
		case Opcodes.GOTO:
			return 0;
		case Opcodes.IFEQ:
		case Opcodes.IFNE:
		case Opcodes.IFLT:
		case Opcodes.IFGE:
		case Opcodes.IFGT:
		case Opcodes.IFLE:
		case Opcodes.IFNULL:
		case Opcodes.IFNONNULL:
			return 1;
		default: // IF_CMPxx and IF_ACMPxx
			return 2;
		}
	}

	@Override
	public void visitLookupSwitchInsn(final Label dflt, final int[] keys,
			final Label[] labels) {
		if (markLabels(dflt, labels)) {
			probesVisitor.visitLookupSwitchInsnWithProbes(dflt, keys, labels,
					frame(1));
		} else {
			probesVisitor.visitLookupSwitchInsn(dflt, keys, labels);
		}
	}

	@Override
	public void visitTableSwitchInsn(final int min, final int max,
			final Label dflt, final Label... labels) {
		if (markLabels(dflt, labels)) {
			probesVisitor.visitTableSwitchInsnWithProbes(min, max, dflt, labels,
					frame(1));
		} else {
			probesVisitor.visitTableSwitchInsn(min, max, dflt, labels);
		}
	}

	private boolean markLabels(final Label dflt, final Label[] labels) {
		boolean probe = false;
		LabelInfo.resetDone(labels);
		if (LabelInfo.isMultiTarget(dflt)) {
			LabelInfo.setProbeId(dflt, idGenerator.nextId());
			probe = true;
		}
		LabelInfo.setDone(dflt);
		for (final Label l : labels) {
			if (LabelInfo.isMultiTarget(l) && !LabelInfo.isDone(l)) {
				LabelInfo.setProbeId(l, idGenerator.nextId());
				probe = true;
			}
			LabelInfo.setDone(l);
		}
		return probe;
	}

	private IFrame frame(final int popCount) {
		return FrameSnapshot.create(analyzer, popCount);
	}

	/**
	 * 屏蔽反射感知到的 jacoco 插桩字段（{@link InstrSupport#DATAFIELD_NAME}）：
	 * 插桩会给被插桩类注入静态 synthetic 字段 {@code $jacocoData}，业务代码/框架
	 * 通过反射遍历 {@code Class.getDeclaredFields()} 时会把该字段当作业务字段处理
	 * （序列化、Bean 拷贝、反射工具等出现污染）。
	 * <p>
	 * 改写把 {@code Class.getDeclaredFields()} 调用点改为
	 * {@link #getDeclaredFields(Class)} —— 仅过滤掉 jacoco 注入字段，
	 * 其余字段（含 CGLIB 等代理生成的合成字段）原样返回，相比 jacoco-czx
	 * 的"过滤全部 synthetic"更精准、更少副作用。
	 * <p>
	 * 改写发生在插桩路径（{@code MethodProbesAdapter} 由
	 * {@link org.jacoco.core.instr.Instrumenter} 在类加载时驱动），
	 * 效果持久写入被插桩类字节码；分析路径使用同一适配器，
	 * 逻辑改写与插桩产物一致，探针布局不受影响。
	 */
	@Override
	public void visitMethodInsn(int opcode, String owner, String name,
			String descriptor, boolean isInterface) {
		if ("java/lang/Class".equals(owner) && "getDeclaredFields".equals(name)
				&& "()[Ljava/lang/reflect/Field;".equals(descriptor)) {
			super.visitMethodInsn(Opcodes.INVOKESTATIC,
					"org/jacoco/core/internal/flow/MethodProbesAdapter",
					"getDeclaredFields",
					"(Ljava/lang/Class;)[Ljava/lang/reflect/Field;", false);
		} else {
			super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
		}
	}

	/**
	 * 被插桩类的 {@code getDeclaredFields()} 替代实现：
	 * 返回的字段数组中剔除 jacoco 注入字段。
	 *
	 * @param clazz
	 *            反射源类
	 * @return 去掉 {@code $jacocoData} 后的字段数组
	 */
	public static Field[] getDeclaredFields(final Class<?> clazz) {
		final Field[] fields = clazz.getDeclaredFields();
		for (int i = 0; i < fields.length; i++) {
			if (InstrSupport.DATAFIELD_NAME.equals(fields[i].getName())) {
				return Arrays.stream(fields)
						.filter(f -> !f.getName()
								.equals(InstrSupport.DATAFIELD_NAME))
						.toArray(Field[]::new);
			}
		}
		// 无 jacoco 注入字段（未插桩类）：原样返回，避免常见类无谓的数组重建
		return fields;
	}

}
