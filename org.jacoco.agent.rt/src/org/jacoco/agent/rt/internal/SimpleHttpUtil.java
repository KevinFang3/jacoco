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
package org.jacoco.agent.rt.internal;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 门户口径的 HTTP 客户端工具：json 上报（updateDumpPort/updateHttpPort），
 * 尽力而为、异常不外传；上报线程为 daemon，不阻塞进程退出。
 * <p>
 * 所用地址见 {@link #baseUrl()}。
 *
 * @author kevin
 */
public class SimpleHttpUtil {

	private static final String BASE_URL = "http://qa.fzzqft.com/portaljava/codeCoverage";

	/** 门户口径的上报地址（JarDownloadServer 等复用） */
	static String baseUrl() {
		return BASE_URL + "/agent";
	}

	/**
	 * 组装端口上报 body（appName/env 任一缺失返回 {@code null}，不应发送）： env 统一转小写（门户按小写匹配
	 * {@code code_coverage_app.env}）。
	 *
	 * @param action
	 *            上报动作（如 updateDumpPort / updateHttpPort）
	 * @param appName
	 *            应用名（{@code APP_NAME} 环境变量）
	 * @param envValue
	 *            环境（{@code FOUNDERSC_ENV} 环境变量）
	 * @param portField
	 *            端口字段名（agentPort / httpPort）
	 * @param portValue
	 *            端口值
	 * @return 组装好的 body；appName/env 缺失时返回 null
	 */
	public static Map<String, String> buildReportBody(final String action,
			final String appName, final String envValue, final String portField,
			final String portValue) {
		if (isMissing(appName) || isMissing(envValue)) {
			return null;
		}
		final Map<String, String> bodyMap = new HashMap<>();
		bodyMap.put("action", action);
		bodyMap.put("appName", appName);
		bodyMap.put("env", envValue.toLowerCase());
		bodyMap.put(portField, portValue);
		return bodyMap;
	}

	/**
	 * 端口变更上报（尽力而为）：从环境变量取 appName/env 组装 body 后异步 POST； appName/env
	 * 缺失时跳过并打日志（避免向门户发送无法匹配的请求）。
	 *
	 * @param action
	 *            上报动作（如 updateDumpPort / updateHttpPort）
	 * @param portField
	 *            端口字段名（agentPort / httpPort）
	 * @param portValue
	 *            端口值
	 */
	public static void asyncReportPort(final String action,
			final String portField, final String portValue) {
		final String appName = System.getenv("APP_NAME");
		final String envValue = System.getenv("FOUNDERSC_ENV");
		final Map<String, String> bodyMap = buildReportBody(action, appName,
				envValue, portField, portValue);
		if (bodyMap == null) {
			System.out.println("[jacoco-report] " + action
					+ " 跳过：APP_NAME/FOUNDERSC_ENV 未设置");
			return;
		}
		asyncPost(baseUrl(), bodyMap, "代码覆盖率服务-" + action);
	}

	private static boolean isMissing(final String value) {
		return value == null || value.trim().isEmpty();
	}

	public static String mapToJson(Map<String, String> map) {
		if (map == null) {
			return "null";
		}
		StringJoiner joiner = new StringJoiner(",", "{", "}");
		for (Map.Entry<String, String> entry : map.entrySet()) {
			final String value = entry.getValue();
			joiner.add("\"" + entry.getKey() + "\":"
					+ (value == null ? "null" : "\"" + value + "\""));
		}
		return joiner.toString();
	}

	public static String post(String urlString, Map<String, String> bodyMap,
			String action) throws IOException {
		// 构造请求
		URL url = new URL(urlString);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type",
				"application/json; charset=utf-8");
		connection.setRequestProperty("User-Agent", "Java/HttpURLConnection");
		connection.setConnectTimeout(3000);
		connection.setDoOutput(true);
		// 发起请求
		String bodyString = mapToJson(bodyMap);
		try (OutputStream os = connection.getOutputStream()) {
			byte[] input = bodyString.getBytes(StandardCharsets.UTF_8);
			os.write(input, 0, input.length);
		}
		// 处理响应
		int responseCode = connection.getResponseCode();
		if (responseCode == HttpURLConnection.HTTP_OK) {
			try (BufferedReader br = new BufferedReader(new InputStreamReader(
					connection.getInputStream(), StandardCharsets.UTF_8))) {
				StringBuilder response = new StringBuilder();
				String responseLine;
				while ((responseLine = br.readLine()) != null) {
					response.append(responseLine.trim());
				}
				System.out.println(action + " -> 成功: " + response);
				return response.toString();
			}
		} else {
			System.out.println(
					action + " -> 失败: " + connection.getResponseMessage());
			return null;
		}
	}

	public static void asyncPost(String urlString, Map<String, String> bodyMap,
			String action) {
		Thread t = new Thread(() -> {
			try {
				post(urlString, bodyMap, action);
			} catch (IOException e) {
				System.out.println(action + " -> 失败: " + e);
			}
		});
		t.setDaemon(true);
		t.start();
	}

}
