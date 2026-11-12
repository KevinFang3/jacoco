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
package org.jacoco.agent.rt.internal.custom;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipOutputStream;

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
 * <li><b>门户 → agent</b>：GET {@code /download?path=<jar路径>}（仅 GET， 不支持请求体，避免大
 * body 占用业务 JVM 内存）；</li>
 * <li><b>agent → 门户</b>：标准 HTTP 文件流（application/octet-stream +
 * Content-Disposition: attachment），无自定义二进制协议；</li>
 * <li><b>目录浏览</b>：GET {@code /browse?path=&depth=&filter=&limit=}，
 * 只读返回目录树元数据（测试环境排查用）；</li>
 * <li><b>路径</b>：任意路径（测试环境不做白名单限制）；相对路径按进程工作目录 {@code user.dir} 解析；仅放行
 * {@code .jar}/{@code .war} 后缀；</li>
 * <li><b>端口</b>：默认 dump TCP 端口 <b>+100</b>（可用系统属性 {@code jacoco.httpPort}
 * 覆盖），被占用自动 +1（与 {@link org.jacoco.agent.rt.internal.output.TcpServerOutput}
 * 端口冲突处理一致）。启动成功后以 <b>action=reportHttpPort</b> 上报实际绑定端口 （门户存至
 * {@code code_coverage_app.http_port}）， 门户下载优先取该值，冲突自增时按 {@code agentPort+100}
 * 推算仅为兜底。</li>
 * </ul>
 * <b>注意</b>：无路径限制仅用于测试/内网环境；生产使用务必加回白名单。
 */
public class JarDownloadServer {

	private static final String PATH_QUERY = "path";

	private static final int MAX_PORT_RETRY = 5;

	/** 简单 token 校验：未配置时保持开放（测试环境），配置后所有接口必须携带 */
	private static final String TOKEN_PROPERTY = "jacoco.httpToken";
	private static final String TOKEN_ENV = "JACOCO_AGENT_TOKEN";
	private static final String TOKEN_HEADER = "X-Agent-Token";
	private static final String AUTH_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";

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
		httpServer.createContext("/browse", exchange -> handleBrowse(exchange));
		// 固定大小线程池 + 有界队列：避免无鉴权接口被并发请求拖垮业务 JVM。
		// 队列满时 CallerRunsPolicy 在分发线程执行，形成天然背压。
		final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0L,
				TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(16),
				r -> {
					Thread t = new Thread(r, "jacoco-download");
					t.setDaemon(true);
					return t;
				}, new ThreadPoolExecutor.CallerRunsPolicy());
		httpServer.setExecutor(executor);
		httpServer.start();
		server = httpServer;
		final int port = httpServer.getAddress().getPort();
		// 上报实际绑定端口（app.code_coverage_app.http_port）：
		// 端口冲突自增后门户按 agentPort+100 推算会失配，以本上报值为准
		SimpleHttpUtil.asyncReportPort("reportHttpPort", "httpPort",
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
		final long start = System.currentTimeMillis();
		try {
			if (!isAuthorized(exchange)) {
				System.out.println("[jacoco-download] 拒绝未授权请求: "
						+ exchange.getRequestURI());
				sendText(exchange, 401, "unauthorized");
				return;
			}
			final String rawPath;
			try {
				rawPath = requestPath(exchange);
			} catch (IllegalArgumentException e) {
				System.out.println("[jacoco-download] 下载失败: " + e.getMessage());
				sendText(exchange, 400, e.getMessage());
				return;
			}
			if (rawPath == null || rawPath.trim().isEmpty()) {
				System.out.println(
						"[jacoco-download] 下载失败: not found or not allowed");
				sendText(exchange, 404, "not found or not allowed");
				return;
			}
			if ("auto".equalsIgnoreCase(rawPath.trim())) {
				handleAuto(exchange, start);
				return;
			}
			final String path = resolveTarget(rawPath);
			if (path == null) {
				System.out.println(
						"[jacoco-download] 下载失败: not found or not allowed");
				sendText(exchange, 404, "not found or not allowed");
				return;
			}
			final File file = new File(path);
			serveFile(exchange, file);
			System.out.println("[jacoco-download] 下载完成: " + file.getName()
					+ ", " + file.length() + " bytes, "
					+ (System.currentTimeMillis() - start) + " ms");
		} finally {
			exchange.close();
		}
	}

	/**
	 * 自动识别模式（{@code path=auto}）：先按 {@code java -jar} 识别本体 jar， 再按
	 * CodeSource/目录扫描识别 WAR；解压目录现场打成 WAR 布局的 zip 返回。
	 */
	private static void handleAuto(final HttpExchange exchange,
			final long start) throws IOException {
		final String ownJar = ApplicationJarResolver.resolve();
		if (ownJar != null && new File(ownJar).isFile()) {
			final File file = new File(ownJar);
			serveFile(exchange, file);
			System.out.println("[jacoco-download] 自动识别下载完成(jar): " + file + ", "
					+ (System.currentTimeMillis() - start) + " ms");
			return;
		}
		final WebappArtifactResolver.Artifact artifact = WebappArtifactResolver
				.resolve();
		if (artifact == null) {
			System.out.println("[jacoco-download] 自动识别失败: 未找到 WAR / 解压目录");
			sendText(exchange, 404, "not found or not allowed");
			return;
		}
		if (artifact.getKind() == WebappArtifactResolver.Kind.WAR) {
			serveFile(exchange, artifact.getPath());
		} else {
			serveWebapp(exchange, artifact.getPath());
		}
		System.out.println("[jacoco-download] 自动识别下载完成(" + artifact.getKind()
				+ "): " + artifact.getPath() + ", "
				+ (System.currentTimeMillis() - start) + " ms");
	}

	private static void serveFile(final HttpExchange exchange, final File file)
			throws IOException {
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
		} catch (IOException e) {
			System.out.println("[jacoco-download] 下载失败(传输中断): " + e);
			throw e;
		}
	}

	private static void serveWebapp(final HttpExchange exchange,
			final File webappDir) throws IOException {
		final String fileName = webappDir.getName() + ".war";
		exchange.getResponseHeaders().set("Content-Type",
				"application/octet-stream");
		exchange.getResponseHeaders().set("Content-Disposition",
				"attachment; filename=\"" + fileName + "\"");
		// 目录打包大小未知，使用 chunked 传输
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream out = exchange.getResponseBody();
				ZipOutputStream zip = new ZipOutputStream(out)) {
			WebappArtifactResolver.zipWebapp(webappDir, zip);
		}
	}

	/**
	 * 只读浏览容器目录树（测试环境排查用，不返回文件内容）。
	 * <p>
	 * GET {@code /browse?path=/opt/app&depth=2&filter=jar,war&limit=200}（仅
	 * GET）。
	 */
	private static void handleBrowse(final HttpExchange exchange)
			throws IOException {
		try {
			if (!isAuthorized(exchange)) {
				sendText(exchange, 401, "unauthorized");
				return;
			}
			final Map<String, String> params;
			try {
				params = browseParams(exchange);
			} catch (IllegalArgumentException e) {
				sendText(exchange, 400, e.getMessage());
				return;
			}
			final Integer depth;
			final Integer limit;
			try {
				depth = parseInt(params.get("depth"), "depth");
				limit = parseInt(params.get("limit"), "limit");
			} catch (IllegalArgumentException e) {
				sendText(exchange, 400, e.getMessage());
				return;
			}
			final DirectoryBrowser.Result result = DirectoryBrowser.browse(
					params.get("path"), depth, params.get("filter"), limit);
			if (result == null) {
				sendText(exchange, 404, "not found or not allowed");
				return;
			}
			final byte[] body = DirectoryBrowser.toJson(result)
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type",
					"application/json; charset=utf-8");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		} finally {
			exchange.close();
		}
	}

	/**
	 * 简单 token 校验：配置了 {@code -Djacoco.httpToken} 或环境变量
	 * {@code JACOCO_AGENT_TOKEN} 时，要求请求头 {@code X-Agent-Token}（或
	 * {@code Authorization: Bearer}）匹配；未配置时保持开放（测试环境）。
	 */
	private static boolean isAuthorized(final HttpExchange exchange) {
		final String expected = expectedToken();
		if (expected.isEmpty()) {
			return true;
		}
		String provided = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
		if (provided == null) {
			final String authorization = exchange.getRequestHeaders()
					.getFirst(AUTH_HEADER);
			if (authorization != null
					&& authorization.startsWith(BEARER_PREFIX)) {
				provided = authorization.substring(BEARER_PREFIX.length())
						.trim();
			}
		}
		if (provided == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				provided.getBytes(StandardCharsets.UTF_8));
	}

	private static String expectedToken() {
		String token = System.getProperty(TOKEN_PROPERTY);
		if (token == null || token.trim().isEmpty()) {
			token = System.getenv(TOKEN_ENV);
		}
		return token == null ? "" : token.trim();
	}

	private static Map<String, String> browseParams(final HttpExchange exchange)
			throws IOException {
		final String method = exchange.getRequestMethod();
		if (!"GET".equalsIgnoreCase(method)) {
			throw new IllegalArgumentException("method not allowed: " + method);
		}
		final Map<String, String> decoded = new HashMap<String, String>();
		for (final Map.Entry<String, String> entry : parseQuery(exchange)
				.entrySet()) {
			decoded.put(entry.getKey(), urlDecode(entry.getValue()));
		}
		return decoded;
	}

	private static String urlDecode(final String value) {
		if (value == null) {
			return null;
		}
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
		} catch (final Exception e) {
			return value;
		}
	}

	private static Integer parseInt(final String value, final String name) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			return Integer.valueOf(value.trim());
		} catch (final NumberFormatException e) {
			throw new IllegalArgumentException(
					"invalid " + name + ": " + value);
		}
	}

	private static String requestPath(final HttpExchange exchange)
			throws IOException {
		final String method = exchange.getRequestMethod();
		if (!"GET".equalsIgnoreCase(method)) {
			throw new IllegalArgumentException("method not allowed: " + method);
		}
		return parseQuery(exchange).get(PATH_QUERY);
	}

	/**
	 * 校验路径（测试环境：任意路径，仅限制 .jar/.war 后缀）。 返回 canonical 路径或 null（不存在/不合法）。
	 */
	private static String resolveTarget(final String rawPath)
			throws IOException {
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
