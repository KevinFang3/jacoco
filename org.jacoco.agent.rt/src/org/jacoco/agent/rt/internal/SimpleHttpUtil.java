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
import java.util.Map;

/**
 * @author kevin
 */
public class SimpleHttpUtil {

	private static final String BASE_URL = "http://qa.fzzqft.com/portaljava/codeCoverage";

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

	public static String upload(String serverUrl, String filePath)
			throws IOException {
		File file = new File(filePath);
		String boundary = "----" + System.currentTimeMillis();
		String lineFeed = "\r\n";
		// 构造请求
		HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl)
				.openConnection();
		conn.setDoOutput(true);
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type",
				"multipart/form-data; boundary=" + boundary);
		// 发起请求
		try (OutputStream os = conn.getOutputStream();
				FileInputStream fis = new FileInputStream(file)) {
			// 写入文件头部
			String header = "--" + boundary + lineFeed
					+ "Content-Disposition: form-data; name=\"file\"; filename=\""
					+ file.getName() + "\"" + lineFeed
					+ "Content-Type: application/octet-stream" + lineFeed
					+ lineFeed;
			os.write(header.getBytes());
			// 流式传输文件
			byte[] buffer = new byte[4096];
			int bytesRead;
			while ((bytesRead = fis.read(buffer)) != -1) {
				os.write(buffer, 0, bytesRead);
			}
			// 结束标记
			os.write((lineFeed + "--" + boundary + "--" + lineFeed).getBytes());
		}
		// 读取响应
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				conn.getResponseCode() >= 400 ? conn.getErrorStream()
						: conn.getInputStream()))) {
			StringBuilder response = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				response.append(line);
			}
			return response.toString();
		} finally {
			conn.disconnect();
		}
	}

	public static void asyncUpload(String serverUrl, String filePath) {
		new Thread(() -> {
			try {
				upload(serverUrl, filePath);
			} catch (IOException e) {
				System.out.println("上传失败: " + e);
			}
		}).start();
	}

	public static void main(String[] args) throws IOException {
		String result = upload(BASE_URL + "/upload",
				"/Users/kevin/Downloads/FSTORE性能测试文件.rar");
		System.out.println(result);
	}

}
