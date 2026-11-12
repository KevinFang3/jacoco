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
package org.jacoco.agent.rt.internal.custom;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 门户口径的 HTTP 客户端工具：json 上报（reportDumpPort/reportHttpPort）， 尽力而为、异常不外传；上报线程为
 * daemon，不阻塞进程退出。
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
	 *            上报动作（如 reportDumpPort / reportHttpPort）
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
		final Map<String, String> fields = new HashMap<String, String>();
		fields.put(portField, portValue);
		return buildReportBody(action, appName, envValue, fields);
	}

	/**
	 * 组装上报 body（通用版）。
	 * <p>
	 * 身份信息为 {@code appName+env} 或 {@code ip} 至少一组：APP_NAME/FOUNDERSC_ENV
	 * 与库中记录不一致或缺失时，门户仍可按 agent 自身 IP 匹配应用。
	 *
	 * @param action
	 *            上报动作
	 * @param appName
	 *            应用名（可为空）
	 * @param envValue
	 *            环境（可为空，发送时统一小写）
	 * @param fields
	 *            附加字段（jarPath / ip / 端口字段等）
	 * @return 组装好的 body；无任何身份信息时返回 null
	 */
	static Map<String, String> buildReportBody(final String action,
			final String appName, final String envValue,
			final Map<String, String> fields) {
		final Map<String, String> bodyMap = new HashMap<String, String>();
		bodyMap.put("action", action);
		if (!isMissing(appName)) {
			bodyMap.put("appName", appName);
		}
		if (!isMissing(envValue)) {
			bodyMap.put("env", envValue.toLowerCase());
		}
		if (fields != null) {
			for (final Map.Entry<String, String> entry : fields.entrySet()) {
				if (!isMissing(entry.getValue())) {
					bodyMap.put(entry.getKey(), entry.getValue());
				}
			}
		}
		final boolean hasName = !isMissing(appName) && !isMissing(envValue);
		final boolean hasIp = !isMissing(bodyMap.get("ip"));
		return hasName || hasIp ? bodyMap : null;
	}

	/**
	 * 本机可用 IPv4 地址（逗号分隔）。k8s 场景即 pod IP，门户优先按它匹配 deploy_ip。
	 */
	static String localIps() {
		final Set<String> ips = new LinkedHashSet<String>();
		try {
			final Enumeration<NetworkInterface> nics = NetworkInterface
					.getNetworkInterfaces();
			if (nics != null) {
				while (nics.hasMoreElements()) {
					final NetworkInterface nic = nics.nextElement();
					if (!nic.isUp() || nic.isLoopback()) {
						continue;
					}
					final Enumeration<InetAddress> addrs = nic
							.getInetAddresses();
					while (addrs.hasMoreElements()) {
						final InetAddress addr = addrs.nextElement();
						if (addr instanceof Inet4Address
								&& !addr.isLoopbackAddress()
								&& !addr.isLinkLocalAddress()) {
							ips.add(addr.getHostAddress());
						}
					}
				}
			}
		} catch (final Exception e) {
			System.out.println("[jacoco] 获取本机 IP 失败: " + e);
		}
		final StringJoiner joiner = new StringJoiner(",");
		for (final String ip : ips) {
			joiner.add(ip);
		}
		return joiner.toString();
	}

	/**
	 * 端口变更上报（尽力而为）：从环境变量取 appName/env 组装 body 后异步 POST； appName/env
	 * 缺失时跳过并打日志（避免向门户发送无法匹配的请求）。
	 *
	 * @param action
	 *            上报动作（如 reportDumpPort / reportHttpPort）
	 * @param portField
	 *            端口字段名（agentPort / httpPort）
	 * @param portValue
	 *            端口值
	 */
	public static void asyncReportPort(final String action,
			final String portField, final String portValue) {
		final Map<String, String> fields = new HashMap<String, String>();
		fields.put(portField, portValue);
		fields.put("ip", localIps());
		asyncReport(action, fields, portValue);
	}

	/**
	 * 应用本体 jar 路径上报（agent 自动识别，尽力而为）。
	 *
	 * @param jarPath
	 *            本体 jar 绝对路径
	 */
	public static void asyncReportJarPath(final String jarPath) {
		final Map<String, String> fields = new HashMap<String, String>();
		fields.put("jarPath", jarPath);
		fields.put("ip", localIps());
		asyncReport("reportJarPath", fields, jarPath);
	}

	private static void asyncReport(final String action,
			final Map<String, String> fields, final String logValue) {
		final String appName = System.getenv("APP_NAME");
		final String envValue = System.getenv("FOUNDERSC_ENV");
		final Map<String, String> bodyMap = buildReportBody(action, appName,
				envValue, fields);
		if (bodyMap == null) {
			System.out.println("[jacoco-" + action + "] 上报(" + logValue
					+ ")跳过: APP_NAME/FOUNDERSC_ENV/IP 均未获取到");
			return;
		}
		asyncPost(baseUrl(), bodyMap, action, logValue);
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
			joiner.add(escapeJson(entry.getKey()) + ":"
					+ (value == null ? "null" : escapeJson(value)));
		}
		return joiner.toString();
	}

	/** JSON 字符串转义（含首尾引号）。 */
	static String escapeJson(final String value) {
		if (value == null) {
			return "null";
		}
		final StringBuilder escaped = new StringBuilder(value.length() + 2);
		escaped.append('"');
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
		return escaped.append('"').toString();
	}

	public static String post(String urlString, Map<String, String> bodyMap,
			String action, String portValue) throws IOException {
		// 构造请求
		URL url = new URL(urlString);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type",
				"application/json; charset=utf-8");
		connection.setRequestProperty("User-Agent", "Java/HttpURLConnection");
		connection.setConnectTimeout(3000);
		// 门户若接受连接但不响应，避免上报线程无限阻塞
		connection.setReadTimeout(3000);
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
				System.out.println("[jacoco-" + action + "] 上报(" + portValue
						+ ")成功: " + response);
				return response.toString();
			}
		} else {
			System.out.println("[jacoco-" + action + "] 上报(" + portValue
					+ ")失败: " + connection.getResponseMessage());
			return null;
		}
	}

	public static void asyncPost(String urlString, Map<String, String> bodyMap,
			String action, String portValue) {
		Thread t = new Thread(() -> {
			try {
				post(urlString, bodyMap, action, portValue);
			} catch (IOException e) {
				System.out.println("[jacoco-" + action + "] 上报(" + portValue
						+ ")失败: " + e);
			}
		});
		t.setDaemon(true);
		t.start();
	}

}
