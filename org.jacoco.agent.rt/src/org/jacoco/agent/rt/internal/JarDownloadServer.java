/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    kevin - 门户端发起下载 jar（HTTP, 代替原 czx 自定义 TCP 协议）
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jacoco.core.runtime.AgentOptions;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 内嵌 HTTP 下载服务（门户端发起下载，参数为 jar 包路径）。
 * <p>
 * 平替原 jacoco-czx 的 {@code downloadJar}（自定义 TCP 协议 + BLOCK_DOWNBBZX 等块， 需改动
 * org.jacoco.core 的 ExecutionDataReader/Writer、RemoteControl 系列、
 * TcpConnection、CLI Dump 命令）。本实现仅在 org.jacoco.agent.rt 内用 JDK 自带
 * {@code com.sun.net.httpserver.HttpServer}，不触碰 org.jacoco.core：
 * <ul>
 * <li><b>门户 → agent</b>：GET {@code /download?path=<jar路径>} 或 POST
 * multipart/form-data（字段 {@code path}，与门户侧现有多部分工具对齐）；</li>
 * <li><b>agent → 门户</b>：标准 HTTP 文件流（application/octet-stream +
 * Content-Disposition: attachment），无自定义二进制协议；</li>
 * <li><b>路径</b>：任意路径（测试环境不做白名单限制）；相对路径按进程工作目录 {@code user.dir} 解析；仅放行
 * {@code .jar}/{@code .war} 后缀；</li>
 * <li><b>端口</b>：默认 dump TCP 端口 <b>+100</b>（可用系统属性 {@code jacoco.httpPort}
 * 覆盖），被占用自动 +1（与 {@link org.jacoco.agent.rt.internal.output.TcpServerOutput}
 * 端口冲突处理一致）。启动成功后以 <b>action=updateHttpPort</b> 上报实际绑定端口 （门户存至
 * {@code code_coverage_app.http_port}）， 门户下载优先取该值，冲突自增时按 {@code agentPort+100}
 * 推算仅为兜底。</li>
 * </ul>
 * <b>注意</b>：无路径限制仅用于测试/内网环境；生产使用务必加回白名单。
 */
public class JarDownloadServer {

	private static final String PATH_QUERY = "path";

	private static final int MAX_PORT_RETRY = 5;

	private static HttpServer server;

	private JarDownloadServer() {
	}

	/**
	 * 启动下载服务（幂等，仅首次生效）。
	 *
	 * @param options
	 *            agent 选项（获取最终 dump 端口，+1 即 http 默认端口）
	 * @throws IOException
	 *             启动失败
	 */
	public static synchronized void start(final AgentOptions options)
			throws IOException {
		if (server != null) {
			return;
		}
		final HttpServer httpServer = bindServer(resolveBasePort(options));
		if (httpServer == null) {
			System.out.println("[jacoco-download] 端口占用且重试次数用尽，下载服务不启动");
			return;
		}
		httpServer.createContext("/download",
				exchange -> handleDownload(exchange));
		ExecutorService executor = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "jacoco-download");
			t.setDaemon(true);
			return t;
		});
		httpServer.setExecutor(executor);
		httpServer.start();
		server = httpServer;
		final int port = httpServer.getAddress().getPort();
		// 上报实际绑定端口（app.code_coverage_app.http_port）：
		// 端口冲突自增后门户按 agentPort+100 推算会失配，以本上报值为准
		SimpleHttpUtil.asyncReportPort("updateHttpPort", "httpPort",
				String.valueOf(port));
		System.out.println("[jacoco-download] http 下载服务已启动: port=" + port);
	}

	private static HttpServer bindServer(final int basePort) {
		int port = basePort;
		for (int i = 0; i < MAX_PORT_RETRY; i++) {
			try {
				return HttpServer.create(new InetSocketAddress(port), 0);
			} catch (IOException e) {
				System.out.println("[jacoco-download] 端口 " + port + " 被占用，尝试 "
						+ (port + 1));
				port++;
			}
		}
		return null;
	}

	private static int resolveBasePort(final AgentOptions options) {
		final String custom = System.getProperty("jacoco.httpPort");
		if (custom != null && !custom.trim().isEmpty()) {
			try {
				return Integer.parseInt(custom.trim());
			} catch (NumberFormatException e) {
				System.out.println(
						"[jacoco-download] jacoco.httpPort 非法: " + custom);
			}
		}
		return options.getPort() + 100;
	}

	// === 请求处理 ===

	private static void handleDownload(final HttpExchange exchange)
			throws IOException {
		try {
			final String path;
			try {
				path = resolveTarget(exchange);
			} catch (IllegalArgumentException e) {
				sendText(exchange, 400, e.getMessage());
				return;
			}
			if (path == null) {
				sendText(exchange, 404, "not found or not allowed");
				return;
			}
			final File file = new File(path);
			final long length = file.length();
			exchange.getResponseHeaders().set("Content-Type",
					"application/octet-stream");
			exchange.getResponseHeaders().set("Content-Disposition",
					"attachment; filename=\"" + file.getName() + "\"");
			exchange.sendResponseHeaders(200, length);
			try (InputStream in = Files.newInputStream(file.toPath());
					OutputStream out = exchange.getResponseBody()) {
				final byte[] buffer = new byte[8192];
				int n;
				while ((n = in.read(buffer)) != -1) {
					out.write(buffer, 0, n);
				}
			}
		} finally {
			exchange.close();
		}
	}

	/**
	 * 解析请求并校验路径（测试环境：任意路径，仅限制 .jar/.war 后缀）。 返回 canonical 路径或 null（不存在/不合法）。
	 */
	private static String resolveTarget(final HttpExchange exchange)
			throws IOException {
		final String method = exchange.getRequestMethod();
		final String rawPath;
		if ("GET".equalsIgnoreCase(method)) {
			rawPath = parseQuery(exchange).get(PATH_QUERY);
		} else if ("POST".equalsIgnoreCase(method)) {
			rawPath = parseMultipart(exchange).get(PATH_QUERY);
		} else {
			throw new IllegalArgumentException("method not allowed: " + method);
		}
		if (rawPath == null || rawPath.trim().isEmpty()) {
			return null;
		}
		final String path = URLDecoder.decode(rawPath.trim(),
				StandardCharsets.UTF_8.name());
		if (!path.endsWith(".jar") && !path.endsWith(".war")) {
			return null;
		}
		final File file = new File(path).getCanonicalFile();
		return file.isFile() ? file.getPath() : null;
	}

	private static Map<String, String> parseQuery(final HttpExchange exchange) {
		final Map<String, String> params = new HashMap<>();
		final String query = exchange.getRequestURI().getRawQuery();
		if (query == null) {
			return params;
		}
		for (String pair : query.split("&")) {
			int idx = pair.indexOf('=');
			if (idx > 0) {
				params.put(pair.substring(0, idx), pair.substring(idx + 1));
			} else if (!pair.isEmpty()) {
				params.put(pair, "");
			}
		}
		return params;
	}

	/**
	 * 极简 multipart/form-data 解析（仅取字段值，无文件域）。 content-type: multipart/form-data;
	 * boundary=----xxxx
	 */
	private static Map<String, String> parseMultipart(
			final HttpExchange exchange) throws IOException {
		final Map<String, String> params = new HashMap<>();
		final String contentType = exchange.getRequestHeaders()
				.getFirst("Content-Type");
		if (contentType == null) {
			return params;
		}
		int idx = contentType.toLowerCase().indexOf("boundary=");
		if (idx < 0) {
			return params;
		}
		String boundary = contentType.substring(idx + "boundary=".length())
				.trim();
		if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
			boundary = boundary.substring(1, boundary.length() - 1);
		}
		final String text = new String(readAll(exchange.getRequestBody()),
				StandardCharsets.UTF_8);
		final String delimiter = "--" + boundary;
		int pos = text.indexOf(delimiter);
		while (pos >= 0) {
			final int next = text.indexOf(delimiter, pos + delimiter.length());
			if (next < 0) {
				break;
			}
			// part 内容: headers \r\n\r\n value \r\n
			final String part = text.substring(pos + delimiter.length(), next);
			final int headerEnd = part.indexOf("\r\n\r\n");
			if (headerEnd >= 0) {
				final String headers = part.substring(0, headerEnd);
				final String value = part.substring(headerEnd + 4);
				final String name = extractName(headers);
				if (name == null) {
					pos = next;
					continue;
				}
				final String clean = value.endsWith("\r\n")
						? value.substring(0, value.length() - 2)
						: value;
				params.put(name, clean);
			}
			pos = next;
		}
		return params;
	}

	private static String extractName(final String headers) {
		final String marker = "name=\"";
		int idx = headers.indexOf(marker);
		if (idx < 0) {
			return null;
		}
		final int end = headers.indexOf('"', idx + marker.length());
		if (end < 0) {
			return null;
		}
		return headers.substring(idx + marker.length(), end);
	}

	private static byte[] readAll(final InputStream in) throws IOException {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		final byte[] chunk = new byte[8192];
		int n;
		while ((n = in.read(chunk)) != -1) {
			buffer.write(chunk, 0, n);
		}
		return buffer.toByteArray();
	}

	private static void sendText(final HttpExchange exchange, final int code,
			final String message) throws IOException {
		final byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type",
				"text/plain; charset=utf-8");
		exchange.sendResponseHeaders(code, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
