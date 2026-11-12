/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    kevin - HTTP 接口仅保留 GET 的行为测试
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal.custom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.jacoco.core.runtime.AgentOptions;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link JarDownloadServer} HTTP 接口。
 */
public class JarDownloadServerHttpTest {

	private static final String MULTIPART_BOUNDARY = "----agenttest";

	private static String originalProxyHost;
	private static String originalProxyPort;
	private static String originalNonProxyHosts;
	private static String originalToken;

	private static int httpPort;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@BeforeClass
	public static void startServer() throws Exception {
		// 上报线程打向黑洞，避免测试访问生产门户；localhost 不走代理
		originalProxyHost = System.getProperty("http.proxyHost");
		originalProxyPort = System.getProperty("http.proxyPort");
		originalNonProxyHosts = System.getProperty("http.nonProxyHosts");
		System.setProperty("http.proxyHost", "127.0.0.1");
		System.setProperty("http.proxyPort", "1");
		System.setProperty("http.nonProxyHosts", "127.0.0.1|localhost");
		// 基础用例验证 GET/POST 行为，显式关闭鉴权；token 用例单独设置
		originalToken = System.getProperty("jacoco.httpToken");
		System.setProperty("jacoco.httpToken", "off");

		final int base = freeBasePort();
		final AgentOptions options = new AgentOptions();
		options.setPort(base);
		JarDownloadServer.start(options);
		httpPort = base + 100;
	}

	@AfterClass
	public static void restoreProxy() {
		restoreProperty("http.proxyHost", originalProxyHost);
		restoreProperty("http.proxyPort", originalProxyPort);
		restoreProperty("http.nonProxyHosts", originalNonProxyHosts);
		restoreProperty("jacoco.httpToken", originalToken);
	}

	private static void restoreProperty(final String key, final String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}

	private static int freeBasePort() throws IOException {
		final Random random = new Random();
		for (int i = 0; i < 50; i++) {
			final int base = 20000 + random.nextInt(20000);
			if (isFree(base) && isFree(base + 100)) {
				return base;
			}
		}
		throw new IOException("无法找到可用端口");
	}

	private static boolean isFree(final int port) {
		try (ServerSocket socket = new ServerSocket(port)) {
			return true;
		} catch (final IOException e) {
			return false;
		}
	}

	private static int request(final String method, final String path,
			final String body, final String contentType,
			final StringBuilder responseBody) throws IOException {
		return request(method, path, body, contentType, null, responseBody);
	}

	private static int request(final String method, final String path,
			final String body, final String contentType, final String token,
			final StringBuilder responseBody) throws IOException {
		final HttpURLConnection connection = (HttpURLConnection) new URL(
				"http://127.0.0.1:" + httpPort + path).openConnection();
		connection.setRequestMethod(method);
		connection.setConnectTimeout(3000);
		connection.setReadTimeout(5000);
		if (token != null) {
			connection.setRequestProperty("X-Agent-Token", token);
		}
		if (body != null) {
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", contentType);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(body.getBytes(StandardCharsets.UTF_8));
			}
		}
		final int code = connection.getResponseCode();
		final InputStream in = code >= 400 ? connection.getErrorStream()
				: connection.getInputStream();
		if (in != null) {
			try (InputStream stream = in) {
				responseBody.append(readAll(stream));
			}
		}
		return code;
	}

	private static String readAll(final InputStream in) throws IOException {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		final byte[] chunk = new byte[4096];
		int n;
		while ((n = in.read(chunk)) != -1) {
			buffer.write(chunk, 0, n);
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}

	private static String multipart(final String field, final String value) {
		return "--" + MULTIPART_BOUNDARY + "\r\n"
				+ "Content-Disposition: form-data; name=\"" + field
				+ "\"\r\n\r\n" + value + "\r\n--" + MULTIPART_BOUNDARY
				+ "--\r\n";
	}

	private static String multipartContentType() {
		return "multipart/form-data; boundary=" + MULTIPART_BOUNDARY;
	}

	@Test
	public void testGetDownloadServesFile() throws Exception {
		final File jar = tmp.newFile("app.jar");
		final StringBuilder body = new StringBuilder();

		final int code = request("GET",
				"/download?path=" + jar.getAbsolutePath(), null, null, body);

		assertEquals(200, code);
	}

	@Test
	public void testPostDownloadRejected() throws Exception {
		final File jar = tmp.newFile("app.jar");
		final StringBuilder body = new StringBuilder();

		final int code = request("POST", "/download",
				multipart("path", jar.getAbsolutePath()),
				multipartContentType(), body);

		assertEquals(400, code);
		assertTrue(body.toString().contains("not allowed")
				|| body.toString().contains("method"));
	}

	@Test
	public void testGetBrowseReturnsJson() throws Exception {
		final StringBuilder body = new StringBuilder();

		final int code = request("GET",
				"/browse?path=" + tmp.getRoot().getAbsolutePath() + "&depth=1",
				null, null, body);

		assertEquals(200, code);
		assertTrue(body.toString().startsWith("{\"root\""));
	}

	@Test
	public void testTokenRequiredWhenConfigured() throws Exception {
		final String previous = System.getProperty("jacoco.httpToken");
		System.setProperty("jacoco.httpToken", "secret");
		try {
			final File jar = tmp.newFile("app.jar");
			StringBuilder body = new StringBuilder();

			assertEquals(401,
					request("GET", "/download?path=" + jar.getAbsolutePath(),
							null, null, body));
			assertEquals(200,
					request("GET", "/download?path=" + jar.getAbsolutePath(),
							null, null, "secret", new StringBuilder()));
			assertEquals(401,
					request("GET", "/download?path=" + jar.getAbsolutePath(),
							null, null, "wrong", new StringBuilder()));

			body = new StringBuilder();
			assertEquals(401,
					request("GET",
							"/browse?path=" + tmp.getRoot().getAbsolutePath(),
							null, null, body));
			assertEquals(200,
					request("GET",
							"/browse?path=" + tmp.getRoot().getAbsolutePath(),
							null, null, "secret", new StringBuilder()));
		} finally {
			if (previous == null) {
				System.clearProperty("jacoco.httpToken");
			} else {
				System.setProperty("jacoco.httpToken", previous);
			}
		}
	}

	@Test
	public void testPostBrowseRejected() throws Exception {
		final StringBuilder body = new StringBuilder();

		final int code = request("POST", "/browse",
				multipart("path", tmp.getRoot().getAbsolutePath()),
				multipartContentType(), body);

		assertEquals(400, code);
		assertTrue(body.toString().contains("not allowed")
				|| body.toString().contains("method"));
	}
}
