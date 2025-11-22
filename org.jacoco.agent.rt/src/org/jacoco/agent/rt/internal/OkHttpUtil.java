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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.*;

/**
 * @author kevin
 */
public class OkHttpUtil {

	private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
			.connectTimeout(60, TimeUnit.SECONDS).build();

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static Request getRequest(String urlString,
			Map<String, Object> bodyMap) {
		String bodyJsonString;
		try {
			bodyJsonString = OBJECT_MAPPER.writeValueAsString(bodyMap);
		} catch (JsonProcessingException e) {
			System.out.println("Convert Map To JsonString Error: " + e);
			return null;
		}
		MediaType mediatype = MediaType
				.parse("application/json; charset=utf-8");
		RequestBody requestbody = RequestBody.create(bodyJsonString, mediatype);
		return new Request.Builder().url(urlString).post(requestbody)
				.addHeader("Content-Type", "application/json").build();
	}

	public static String post(String urlString, Map<String, Object> bodyMap,
			String action) {
		Request request = getRequest(urlString, bodyMap);
		try (Response response = CLIENT.newCall(request).execute()) {
			if (response.isSuccessful() && response.body() != null) {
				System.out.println(action + " - 成功: " + response.body()
						+ ", 请求参数: " + bodyMap);
				return response.body().string();
			} else {
				System.out.println(action + " - 失败: " + response.message()
						+ ", 请求参数: " + bodyMap);
				return null;
			}
		} catch (Exception e) {
			System.out.println(action + " - 失败: " + e + ", 请求参数: " + bodyMap);
			return null;
		}

	}

	public static void asyncPost(String urlString, Map<String, Object> bodyMap,
			String action) {
		Request request = getRequest(urlString, bodyMap);
		CLIENT.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				System.out
						.println(action + " - 失败: " + e + ", 请求参数: " + bodyMap);
			}

			@Override
			public void onResponse(Call call, Response response) {
				if (response.isSuccessful() && response.body() != null) {
					try {
						System.out.println(
								action + " - 成功: " + response.body().string()
										+ ", 请求参数: " + bodyMap);
					} catch (IOException e) {
						System.out.println(action + " - 解析响应失败: " + e);
					}
				} else {
					System.out.println(action + " - 失败: " + response.message()
							+ ", 请求参数: " + bodyMap);
				}
				response.close();
			}
		});
	}

}
