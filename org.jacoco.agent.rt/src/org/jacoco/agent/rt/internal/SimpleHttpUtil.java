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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * @author kevin
 */
public class SimpleHttpUtil {

	public static String mapToJson(Map<String, String> map) {
		if (map == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, String> entry : map.entrySet()) {
			if (!first) {
				sb.append(",");
			}
			sb.append("\"").append(entry.getKey()).append("\":\"")
					.append(entry.getValue()).append("\"");
			first = false;
		}
		return sb.append("}").toString();
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
		new Thread(() -> {
			try {
				post(urlString, bodyMap, action);
			} catch (IOException e) {
				System.out.println(action + " -> 失败: " + e);
			}
		}).start();
	}

}
