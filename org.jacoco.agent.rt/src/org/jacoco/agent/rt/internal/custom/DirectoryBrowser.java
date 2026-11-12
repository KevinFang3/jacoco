/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * SPDX-License-Identifier: EPL-2.0
 * Contributors:
 *    kevin - 容器目录树浏览（只读、仅元数据）
 *******************************************************************************/
package org.jacoco.agent.rt.internal.custom;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 只读浏览容器目录树，仅返回文件名、类型、大小与修改时间，不返回文件内容。
 * <p>
 * 用于测试环境排查"应用产物到底放在哪"，不供门户自动流程使用。 {@code depth} 控制展开层级，{@code limit}
 * 控制返回条目总数（目录+文件）， 两者都受硬上限约束，防止大目录拖垮 agent。
 * <p>
 * 目录使用 {@link DirectoryStream} 流式遍历，内存占用与 {@code limit}
 * 成正比、不随目录条目数增长；返回前仅对已收集的条目排序。
 */
public final class DirectoryBrowser {

	/** 默认展开层级 */
	public static final int DEFAULT_DEPTH = 2;
	/** 最大展开层级 */
	public static final int MAX_DEPTH = 4;
	/** 默认返回条目数 */
	public static final int DEFAULT_LIMIT = 200;
	/** 最大返回条目数 */
	public static final int MAX_LIMIT = 500;

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	/** 单个目录项 */
	public static final class Entry {
		private final String path;
		private final String relative;
		private final String type;
		private final long size;
		private final String mtime;
		private final int depth;

		Entry(final String path, final String relative, final String type,
				final long size, final String mtime, final int depth) {
			this.path = path;
			this.relative = relative;
			this.type = type;
			this.size = size;
			this.mtime = mtime;
			this.depth = depth;
		}

		public String getPath() {
			return path;
		}

		public String getRelative() {
			return relative;
		}

		public String getType() {
			return type;
		}

		public long getSize() {
			return size;
		}

		public String getMtime() {
			return mtime;
		}

		public int getDepth() {
			return depth;
		}
	}

	/** 浏览结果 */
	public static final class Result {
		private final String root;
		private final int depth;
		private final String filter;
		private final int limit;
		private final boolean truncated;
		private final List<Entry> entries;

		Result(final String root, final int depth, final String filter,
				final int limit, final boolean truncated,
				final List<Entry> entries) {
			this.root = root;
			this.depth = depth;
			this.filter = filter;
			this.limit = limit;
			this.truncated = truncated;
			this.entries = entries;
		}

		public String getRoot() {
			return root;
		}

		public int getDepth() {
			return depth;
		}

		public String getFilter() {
			return filter;
		}

		public int getLimit() {
			return limit;
		}

		public boolean isTruncated() {
			return truncated;
		}

		public List<Entry> getEntries() {
			return entries;
		}
	}

	private DirectoryBrowser() {
		// no instances
	}

	/**
	 * 从应用工作目录开始浏览。
	 *
	 * @param path
	 *            目录路径，可为空（默认 user.dir）
	 * @param depth
	 *            展开层级，可为空（默认 2）
	 * @param filter
	 *            文件后缀过滤，逗号分隔（如 jar,war），可为空
	 * @param limit
	 *            最大条目数，可为空（默认 200）
	 * @return 浏览结果；路径不存在或不是目录时返回 {@code null}
	 */
	public static Result browse(final String path, final Integer depth,
			final String filter, final Integer limit) {
		return browse(path, depth, filter, limit,
				System.getProperty("user.dir"));
	}

	static Result browse(final String path, final Integer depth,
			final String filter, final Integer limit, final String workDir) {
		final File root = resolveRoot(path, workDir);
		if (root == null) {
			return null;
		}
		final int effectiveDepth = clamp(depth == null ? DEFAULT_DEPTH : depth,
				0, MAX_DEPTH);
		final int effectiveLimit = clamp(limit == null ? DEFAULT_LIMIT : limit,
				1, MAX_LIMIT);
		final Set<String> filters = parseFilters(filter);
		final List<Entry> entries = new ArrayList<>();
		final boolean[] truncated = { false };
		if (effectiveDepth > 0) {
			walk(root, root, 1, effectiveDepth, filters, effectiveLimit,
					entries, truncated);
			// 仅对已收集的、最多 limit 条结果排序，不缓存整个目录
			sortEntries(entries);
		}
		return new Result(root.getPath(), effectiveDepth,
				filter == null ? "" : filter.trim(), effectiveLimit,
				truncated[0], entries);
	}

	private static File resolveRoot(final String path, final String workDir) {
		String value = path == null ? "" : path.trim();
		if (value.isEmpty()) {
			value = workDir == null ? "." : workDir;
		}
		File root = new File(value);
		if (!root.isAbsolute() && workDir != null) {
			root = new File(workDir, value);
		}
		try {
			root = root.getCanonicalFile();
		} catch (final Exception e) {
			return null;
		}
		return root.isDirectory() ? root : null;
	}

	private static void walk(final File dir, final File root, final int level,
			final int maxDepth, final Set<String> filters, final int limit,
			final List<Entry> entries, final boolean[] truncated) {
		if (level > maxDepth || truncated[0]) {
			return;
		}
		// 流式遍历：不一次性构建整个目录的子项数组，内存与 limit 成正比
		try (DirectoryStream<Path> children = Files
				.newDirectoryStream(dir.toPath())) {
			for (final Path childPath : children) {
				if (entries.size() >= limit) {
					truncated[0] = true;
					return;
				}
				final File child = childPath.toFile();
				final boolean link = Files.isSymbolicLink(childPath);
				final boolean isDir = !link && Files.isDirectory(childPath,
						LinkOption.NOFOLLOW_LINKS);
				if (!link && !isDir && !filters.isEmpty()
						&& !matches(child.getName(), filters)) {
					continue;
				}
				final String type = link ? "link" : isDir ? "dir" : "file";
				entries.add(toEntry(child, root, type, level));
				if (isDir) {
					walk(child, root, level + 1, maxDepth, filters, limit,
							entries, truncated);
					if (truncated[0]) {
						return;
					}
				}
			}
		} catch (final DirectoryIteratorException e) {
			// 遍历过程中目录被删除/变动，跳过该目录
		} catch (final IOException e) {
			// 目录不可读，跳过
		}
	}

	/** 对已收集的条目排序：目录优先，其次按相对路径名称。 */
	private static void sortEntries(final List<Entry> entries) {
		entries.sort((left, right) -> {
			final boolean leftDir = "dir".equals(left.getType());
			final boolean rightDir = "dir".equals(right.getType());
			if (leftDir != rightDir) {
				return leftDir ? -1 : 1;
			}
			return left.getRelative().compareToIgnoreCase(right.getRelative());
		});
	}

	private static Entry toEntry(final File file, final File root,
			final String type, final int level) {
		final String relative = root.toPath().relativize(file.toPath())
				.toString().replace(File.separatorChar, '/');
		long size = 0;
		long mtime = 0;
		try {
			if ("file".equals(type)) {
				size = Files.size(file.toPath());
			}
			mtime = Files.getLastModifiedTime(file.toPath(),
					LinkOption.NOFOLLOW_LINKS).toMillis();
		} catch (final IOException ignore) {
			// 读取失败时保留默认值
		}
		return new Entry(file.getAbsolutePath(), relative, type, size,
				TIME_FORMAT.format(Instant.ofEpochMilli(mtime)), level);
	}

	private static Set<String> parseFilters(final String filter) {
		final Set<String> filters = new LinkedHashSet<>();
		if (filter == null || filter.trim().isEmpty()) {
			return filters;
		}
		for (final String part : filter.split(",")) {
			String value = part.trim().toLowerCase();
			if (value.startsWith(".")) {
				value = value.substring(1);
			}
			if (!value.isEmpty()) {
				filters.add(value);
			}
		}
		return filters;
	}

	private static boolean matches(final String name,
			final Set<String> filters) {
		final String lower = name.toLowerCase();
		for (final String filter : filters) {
			if (lower.endsWith("." + filter)) {
				return true;
			}
		}
		return false;
	}

	private static int clamp(final int value, final int min, final int max) {
		return Math.max(min, Math.min(max, value));
	}

	/**
	 * 序列化为 JSON。
	 *
	 * @param result
	 *            浏览结果
	 * @return JSON 字符串
	 */
	public static String toJson(final Result result) {
		final StringBuilder json = new StringBuilder();
		json.append("{\"root\":\"").append(escape(result.getRoot()))
				.append("\",\"depth\":").append(result.getDepth())
				.append(",\"limit\":").append(result.getLimit())
				.append(",\"filter\":\"").append(escape(result.getFilter()))
				.append("\",\"truncated\":").append(result.isTruncated())
				.append(",\"entries\":[");
		final List<Entry> entries = result.getEntries();
		for (int i = 0; i < entries.size(); i++) {
			final Entry entry = entries.get(i);
			if (i > 0) {
				json.append(',');
			}
			json.append("{\"path\":\"").append(escape(entry.getPath()))
					.append("\",\"relative\":\"")
					.append(escape(entry.getRelative()))
					.append("\",\"type\":\"").append(entry.getType())
					.append("\",\"size\":").append(entry.getSize())
					.append(",\"mtime\":\"").append(escape(entry.getMtime()))
					.append("\",\"depth\":").append(entry.getDepth())
					.append('}');
		}
		json.append("]}");
		return json.toString();
	}

	private static String escape(final String value) {
		if (value == null) {
			return "";
		}
		final StringBuilder escaped = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			switch (c) {
			case '"':
				escaped.append("\\\"");
				break;
			case '\\':
				escaped.append("\\\\");
				break;
			case '\n':
				escaped.append("\\n");
				break;
			case '\r':
				escaped.append("\\r");
				break;
			case '\t':
				escaped.append("\\t");
				break;
			default:
				if (c < 0x20) {
					escaped.append(String.format("\\u%04x", (int) c));
				} else {
					escaped.append(c);
				}
			}
		}
		return escaped.toString();
	}
}
