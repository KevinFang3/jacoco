/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    kevin - WAR / 解压目录自动识别
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal.custom;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 识别当前 JVM 中 Web 应用（WAR）的产物位置。
 * <p>
 * 优先扫描已加载类的 {@link CodeSource}（打包 WAR 为
 * {@code jar:file:/...war!/WEB-INF/classes/}，解压目录为
 * {@code file:/.../WEB-INF/classes/}）；拿不到时回退扫描 {@code catalina.base}
 * 等常见部署目录。识别不唯一且 APP_NAME 无法区分时返回 {@code null}，不做猜测。
 */
public final class WebappArtifactResolver {

	/** 产物类型 */
	public enum Kind {
		/** 打包 WAR 文件 */
		WAR,
		/** 解压后的 webapp 目录 */
		WEBAPP_DIR
	}

	/** 识别结果 */
	public static final class Artifact {
		private final Kind kind;
		private final File path;

		Artifact(final Kind kind, final File path) {
			this.kind = kind;
			this.path = path;
		}

		public Kind getKind() {
			return kind;
		}

		public File getPath() {
			return path;
		}

		String key() {
			return path.getPath();
		}
	}

	private static volatile Instrumentation instrumentation;

	/** 识别结果缓存：成功一次后直接复用，避免每次请求都全量扫描已加载类 */
	private static volatile Artifact cachedArtifact;
	private static volatile long lastAttemptMillis;
	private static final long RETRY_INTERVAL_MILLIS = 10000;

	private WebappArtifactResolver() {
		// no instances
	}

	/**
	 * 保存 premain 阶段拿到的 Instrumentation，供请求时按需扫描已加载类。
	 *
	 * @param inst
	 *            instrumentation
	 */
	public static void init(final Instrumentation inst) {
		instrumentation = inst;
	}

	/**
	 * 按需识别当前 webapp 产物。
	 *
	 * @return 识别结果；无法唯一识别时返回 {@code null}
	 */
	public static Artifact resolve() {
		final Artifact cached = cachedArtifact;
		if (cached != null) {
			return cached;
		}
		return resolveWithCache(System.currentTimeMillis(), () -> doResolve());
	}

	/**
	 * 带缓存的解析：成功后永久复用；失败时 RETRY_INTERVAL_MILLIS 内不重复扫描， 避免异常流量反复触发
	 * {@code getAllLoadedClasses()}。
	 */
	static Artifact resolveWithCache(final long now,
			final ArtifactResolver resolver) {
		final Artifact cached = cachedArtifact;
		if (cached != null) {
			return cached;
		}
		synchronized (WebappArtifactResolver.class) {
			if (cachedArtifact != null) {
				return cachedArtifact;
			}
			if (lastAttemptMillis != 0
					&& now - lastAttemptMillis < RETRY_INTERVAL_MILLIS) {
				return null;
			}
			lastAttemptMillis = now;
			final Artifact resolved = resolver.resolve();
			if (resolved != null) {
				cachedArtifact = resolved;
			}
			return resolved;
		}
	}

	/** 仅测试使用：清空缓存。 */
	static void resetCache() {
		synchronized (WebappArtifactResolver.class) {
			cachedArtifact = null;
			lastAttemptMillis = 0;
		}
	}

	private static Artifact doResolve() {
		final List<String> locations = new ArrayList<String>();
		final Instrumentation inst = instrumentation;
		if (inst != null) {
			Class<?>[] loaded = null;
			try {
				loaded = inst.getAllLoadedClasses();
			} catch (final Throwable ignore) {
				// 扫描失败按未找到处理
			}
			if (loaded != null) {
				for (final Class<?> clazz : loaded) {
					final String location = locationOf(clazz);
					if (location != null) {
						locations.add(location);
					}
				}
			}
		}
		return resolve(locations, System.getenv("APP_NAME"), scanBases());
	}

	static Artifact resolve(final List<String> locationUrls,
			final String appName, final List<File> scanBases) {
		final Map<String, Artifact> artifacts = new LinkedHashMap<String, Artifact>();
		if (locationUrls != null) {
			final Set<String> roots = new LinkedHashSet<String>();
			for (final String url : locationUrls) {
				final String root = webappRoot(url);
				if (root != null) {
					roots.add(root);
				}
			}
			for (final String root : roots) {
				final Artifact artifact = toArtifact(new File(root));
				if (artifact != null) {
					artifacts.put(artifact.key(), artifact);
				}
			}
		}
		if (artifacts.isEmpty() && scanBases != null) {
			for (final File base : scanBases) {
				if (base != null) {
					artifacts.putAll(scan(base));
				}
			}
		}
		return selectArtifact(artifacts, appName);
	}

	/**
	 * 把解压 webapp 目录按 WAR 布局写入 zip：{@code WEB-INF/classes/**
	 * *.class} + {@code WEB-INF/lib/*.jar}。
	 * <p>
	 * 保持 WAR 布局是为了复用门户现有的多模块提取逻辑（按 groupId 过滤项目模块 jar），避免只打包 classes 导致大量业务类遗漏。
	 *
	 * @param webappDir
	 *            webapp 根目录
	 * @param zip
	 *            目标 zip 流
	 * @throws IOException
	 *             读写失败
	 */
	static void zipWebapp(final File webappDir, final ZipOutputStream zip)
			throws IOException {
		addClassFiles(new File(webappDir, "WEB-INF/classes"), "WEB-INF/classes",
				zip);
		final File lib = new File(webappDir, "WEB-INF/lib");
		if (!lib.isDirectory()) {
			return;
		}
		try (DirectoryStream<Path> jars = Files.newDirectoryStream(lib.toPath(),
				"*.jar")) {
			for (final Path jar : jars) {
				// jar 本身已压缩，用 STORED 避免在业务 JVM 里重复做 DEFLATE
				addStoredFile(jar, "WEB-INF/lib/" + jar.getFileName(), zip);
			}
		}
	}

	private static void addClassFiles(final File dir, final String prefix,
			final ZipOutputStream zip) throws IOException {
		if (!dir.isDirectory()) {
			return;
		}
		try (DirectoryStream<Path> files = Files
				.newDirectoryStream(dir.toPath())) {
			for (final Path file : files) {
				if (Files.isDirectory(file)) {
					addClassFiles(file.toFile(),
							prefix + "/" + file.getFileName(), zip);
				} else if (file.getFileName().toString().endsWith(".class")) {
					zip.putNextEntry(
							new ZipEntry(prefix + "/" + file.getFileName()));
					Files.copy(file, zip);
					zip.closeEntry();
				}
			}
		}
	}

	/** 以 STORED（不压缩）方式写入 zip，仅统计 CRC 与大小，降低 CPU 开销。 */
	private static void addStoredFile(final Path file, final String entryName,
			final ZipOutputStream zip) throws IOException {
		final CRC32 crc = new CRC32();
		long size = 0;
		try (InputStream in = Files.newInputStream(file)) {
			final byte[] buffer = new byte[8192];
			int n;
			while ((n = in.read(buffer)) != -1) {
				crc.update(buffer, 0, n);
				size += n;
			}
		}
		final ZipEntry entry = new ZipEntry(entryName);
		entry.setMethod(ZipEntry.STORED);
		entry.setSize(size);
		entry.setCompressedSize(size);
		entry.setCrc(crc.getValue());
		zip.putNextEntry(entry);
		Files.copy(file, zip);
		zip.closeEntry();
	}

	private static String locationOf(final Class<?> clazz) {
		try {
			final ProtectionDomain domain = clazz.getProtectionDomain();
			final CodeSource source = domain == null ? null
					: domain.getCodeSource();
			final URL location = source == null ? null : source.getLocation();
			return location == null ? null : location.toExternalForm();
		} catch (final Throwable ignore) {
			return null;
		}
	}

	/**
	 * 从 CodeSource URL 反推 webapp 根目录：
	 * <ul>
	 * <li>{@code jar:file:/x/app.war!/WEB-INF/classes/...} → war 文件路径</li>
	 * <li>{@code file:/x/webapps/app/WEB-INF/classes/...} → webapp 目录</li>
	 * <li>{@code file:/x/webapps/app/WEB-INF/lib/a.jar} → webapp 目录</li>
	 * </ul>
	 */
	static String webappRoot(final String url) {
		if (url == null) {
			return null;
		}
		if (url.startsWith("jar:")) {
			final int bang = url.indexOf("!/WEB-INF/");
			if (bang < 0) {
				return null;
			}
			return decodeFileUrl(url.substring("jar:".length(), bang));
		}
		int idx = url.indexOf("/WEB-INF/classes/");
		if (idx < 0) {
			idx = url.indexOf("/WEB-INF/lib/");
		}
		if (idx < 0) {
			return null;
		}
		return decodeFileUrl(url.substring(0, idx));
	}

	private static String decodeFileUrl(final String url) {
		String path = url;
		if (path.startsWith("file:")) {
			path = path.substring("file:".length());
		}
		try {
			path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
		} catch (final Exception ignore) {
			// 保留原始路径
		}
		return new File(path).getPath();
	}

	private static Artifact toArtifact(final File root) {
		try {
			final File file = root.getCanonicalFile();
			if (file.isFile()) {
				return file.getName().toLowerCase().endsWith(".war")
						? new Artifact(Kind.WAR, file)
						: null;
			}
			if (!file.isDirectory()) {
				return null;
			}
			// 解压目录：优先返回同名 war（Tomcat 默认保留原 war）
			final File sibling = new File(file.getParentFile(),
					file.getName() + ".war");
			if (sibling.isFile()) {
				return new Artifact(Kind.WAR, sibling.getCanonicalFile());
			}
			if (new File(file, "WEB-INF").isDirectory()) {
				return new Artifact(Kind.WEBAPP_DIR, file);
			}
			return null;
		} catch (final Exception e) {
			return null;
		}
	}

	private static Map<String, Artifact> scan(final File base) {
		final Map<String, Artifact> result = new LinkedHashMap<String, Artifact>();
		try {
			if (!base.isDirectory()) {
				return result;
			}
			File dir = base;
			final File webapps = new File(base, "webapps");
			if (webapps.isDirectory()) {
				dir = webapps;
			}
			try (DirectoryStream<Path> files = Files
					.newDirectoryStream(dir.toPath())) {
				for (final Path file : files) {
					final Artifact artifact = toArtifact(file.toFile());
					if (artifact != null) {
						result.put(artifact.key(), artifact);
					}
				}
			}
		} catch (final Exception ignore) {
			// 扫描失败按未找到处理
		}
		return result;
	}

	private static Artifact selectArtifact(
			final Map<String, Artifact> artifacts, final String appName) {
		if (artifacts.isEmpty()) {
			return null;
		}
		if (artifacts.size() == 1) {
			return artifacts.values().iterator().next();
		}
		if (appName != null && !appName.trim().isEmpty()) {
			final String name = appName.trim().toLowerCase();
			Artifact matched = null;
			int count = 0;
			for (final Artifact artifact : artifacts.values()) {
				String candidate = artifact.getPath().getName().toLowerCase();
				if (candidate.endsWith(".war")) {
					candidate = candidate.substring(0,
							candidate.length() - ".war".length());
				}
				final boolean hit = candidate.length() >= 3
						&& (name.equals(candidate)
								|| name.endsWith("_" + candidate)
								|| name.contains(candidate));
				if (hit) {
					matched = artifact;
					count++;
				}
			}
			if (count == 1) {
				return matched;
			}
		}
		return null;
	}

	private static List<File> scanBases() {
		final Set<File> bases = new LinkedHashSet<File>();
		final String[] props = { "catalina.base", "catalina.home", "user.dir" };
		for (final String prop : props) {
			final String value = System.getProperty(prop);
			if (value != null && !value.trim().isEmpty()) {
				bases.add(new File(value));
			}
		}
		final String[] common = { "/usr/local/tomcat", "/opt/tomcat",
				"/usr/local/tomcat9", "/opt/tomcat9", "/opt/app", "/app",
				"/usr/local/bes", "/opt/bes", "/opt/webserver" };
		for (final String path : common) {
			bases.add(new File(path));
		}
		return new ArrayList<File>(bases);
	}

	/** 解析函数（便于测试缓存逻辑）。 */
	interface ArtifactResolver {
		Artifact resolve();
	}
}
