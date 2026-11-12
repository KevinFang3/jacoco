/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    kevin - 门户端集成（端口冲突处理、jar 下载、$jacocoData 屏蔽）
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal.custom;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

import org.jacoco.core.runtime.AgentOptions;

/**
 * 门户端集成的生命周期增强钩子，由 {@link PreMain} 在 premain 流程两端调用。
 * <p>
 * 定制逻辑全部集中在本类（以及 {@link JarDownloadServer}、
 * {@link SimpleHttpUtil}、{@link DeclaredFieldsRewriter}），不修改
 * org.jacoco.core；{@link PreMain} 仅保留两行接线调用，升级原版 jacoco 时 该两行需按新版 premain
 * 流程重新插回。
 */
public final class AgentEnhancer {

	private AgentEnhancer() {
		// no instances
	}

	/**
	 * Agent 初始化前（{@code Agent.getInstance} 之前）调用： 端口冲突探测调整。
	 *
	 * @param options
	 *            agent 选项（随后传入 {@code Agent.getInstance}，探测调整 直接作用于该实例的 dump
	 *            端口）
	 */
	public static void beforeStartup(final AgentOptions options) {
		// 尽力而为：任何增强逻辑失败都不得影响 agent/业务启动（premain 抛异常 = 进程终止）
		try {
			if (adjustPortOnConflict(options)) {
				reportDumpPort(options);
			}
		} catch (Exception e) {
			System.out.println("[jacoco-enhancer] beforeStartup 失败: " + e);
		}
	}

	/**
	 * Agent 初始化后（{@code Agent.getInstance} 之后）调用： 启动门户端 jar 下载服务 + 注册
	 * {@code $jacocoData} 屏蔽后处理。
	 *
	 * @param options
	 *            agent 选项（此后 {@code options.getPort()} 为最终端口）
	 * @param inst
	 *            instrumentation 回调（用于注册后处理 transformer）
	 */
	public static void afterStartup(final AgentOptions options,
			final Instrumentation inst) {
		try {
			JarDownloadServer.start(options);
		} catch (Exception e) {
			System.out.println("[jacoco-download] 启动失败: " + e);
		}
		// 自动识别本体 jar（java -jar）并上报，门户据此配置 jar_path，无需人工填写
		try {
			final String ownJar = ApplicationJarResolver.resolve();
			if (ownJar != null) {
				System.out
						.println("[jacoco-reportJarPath] 识别到本体 jar: " + ownJar);
				SimpleHttpUtil.asyncReportJarPath(ownJar);
			} else {
				System.out.println(
						"[jacoco-reportJarPath] 未能识别本体 jar（非 java -jar 启动），跳过");
			}
		} catch (Exception e) {
			System.out.println("[jacoco-reportJarPath] 上报失败: " + e);
		}
		// 保存 Instrumentation，供下载服务按需扫描已加载类、定位 WAR/解压目录
		try {
			WebappArtifactResolver.init(inst);
		} catch (Exception e) {
			System.out.println("[jacoco-download] 初始化 WAR 识别失败: " + e);
		}
		try {
			inst.addTransformer(new DeclaredFieldsRewriter());
		} catch (Exception e) {
			// 注册失败不得影响业务启动
			System.out.println("[jacoco-enhancer] 注册 $jacocoData 改写器失败: " + e);
		}
	}

	/**
	 * 探测 agent dump 端口冲突（解决宝兰德BES的 address in use）并在必要时 +1 调整： 用于在
	 * {@code TcpServerOutput.startup} 真正绑定之前提前规避。
	 *
	 * @param options
	 *            agent 选项（冲突时原地修改其端口）
	 * @return 端口是否被调整
	 */
	public static boolean adjustPortOnConflict(final AgentOptions options) {
		// 探测失败（端口越界/无权限等）视为未冲突：交由 TcpServerOutput 原逻辑处理
		try (ServerSocket socket = new ServerSocket()) {
			// reuseAddress 必须在 bind 之前设置才生效
			socket.setReuseAddress(true);
			socket.bind(new InetSocketAddress(options.getPort()));
		} catch (IOException e) {
			options.setPort(options.getPort() + 1);
			return true;
		} catch (Exception e) {
			return false;
		}
		return false;
	}

	private static void reportDumpPort(final AgentOptions options) {
		SimpleHttpUtil.asyncReportPort("reportDumpPort", "agentPort",
				String.valueOf(options.getPort()));
	}

}
