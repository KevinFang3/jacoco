/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * SPDX-License-Identifier: EPL-2.0
 * Contributors:
 *    kevin - 应用本体 jar 自动识别（java -jar）
 *******************************************************************************/
package org.jacoco.agent.rt.internal.custom;

import java.io.File;

/**
 * 识别当前 JVM 启动所用的应用本体 jar。
 * <p>
 * 当前仅覆盖 {@code java -jar xxx.jar} 场景：
 * <ul>
 * <li>优先取 {@code sun.java.command} 的第一个 token（{@code -javaagent} 不会进入
 * classpath，因此这里就是本体 jar）；</li>
 * <li>兜底取 {@code java.class.path}，仅当只有一个 jar 条目时采用；</li>
 * <li>非 jar 启动（{@code -cp} 多条目、目录/WAR 部署等）返回 {@code null}，不做任何猜测。</li>
 * </ul>
 */
public final class ApplicationJarResolver {

	private ApplicationJarResolver() {
		// no instances
	}

	/**
	 * 从系统属性识别本体 jar 的绝对路径。
	 *
	 * @return 本体 jar 路径；无法可靠识别时返回 {@code null}
	 */
	public static String resolve() {
		return resolve(System.getProperty("sun.java.command"),
				System.getProperty("java.class.path"),
				System.getProperty("user.dir"));
	}

	static String resolve(final String sunCommand, final String classPath,
			final String workDir) {
		final String fromCommand = canonicalJar(firstToken(sunCommand),
				workDir);
		if (fromCommand != null) {
			return fromCommand;
		}
		if (classPath == null) {
			return null;
		}
		final String[] entries = classPath
				.split(java.util.regex.Pattern.quote(File.pathSeparator));
		if (entries.length != 1) {
			return null;
		}
		return canonicalJar(entries[0], workDir);
	}

	private static String firstToken(final String command) {
		if (command == null) {
			return null;
		}
		final String trimmed = command.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		final int space = trimmed.indexOf(' ');
		return space < 0 ? trimmed : trimmed.substring(0, space);
	}

	private static String canonicalJar(final String path,
			final String workDir) {
		if (path == null) {
			return null;
		}
		String candidate = path.trim();
		if (candidate.length() >= 2 && candidate.startsWith("\"")
				&& candidate.endsWith("\"")) {
			candidate = candidate.substring(1, candidate.length() - 1);
		}
		if (!candidate.endsWith(".jar")) {
			return null;
		}
		File file = new File(candidate);
		if (!file.isAbsolute() && workDir != null) {
			file = new File(workDir, candidate);
		}
		try {
			file = file.getCanonicalFile();
		} catch (final Exception e) {
			return null;
		}
		return file.isFile() ? file.getPath() : null;
	}
}
