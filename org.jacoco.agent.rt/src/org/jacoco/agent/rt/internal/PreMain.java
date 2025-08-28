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
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.jacoco.core.runtime.AgentOptions;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.InjectedClassRuntime;
import org.jacoco.core.runtime.ModifiedSystemClassRuntime;

/**
 * The agent which is referred as the <code>Premain-Class</code>. The agent
 * configuration is provided with the agent parameters in the command line.
 */
public final class PreMain {

	private PreMain() {
		// no instances
	}

	/**
	 * This method is called by the JVM to initialize Java agents.
	 *
	 * @param options
	 *            agent options
	 * @param inst
	 *            instrumentation callback provided by the JVM
	 * @throws Exception
	 *             in case initialization fails
	 */
	public static void premain(final String options, final Instrumentation inst)
			throws Exception {
		// 新增逻辑
		try {
			// 获取ip
			String localIp = InetAddress.getLocalHost().getHostAddress();
			System.out.println("LOCAL_IP: " + localIp);
			// 获取环境变量APP_NAME
			String appName = System.getenv("APP_NAME");
			System.out.println("APP_NAME: " + appName);
			// 获取环境变量FOUNDERSC_ENV
			String env = System.getenv("FOUNDERSC_ENV");
			System.out.println("FOUNDERSC_ENV: " + env);
			// 注册到Ftest
			String url;
			if (appName.contains("fone-test")) {
				url = "http://ftest-qa.foundersc-inc.com/portaljava/codeCoverage/addAppByAgent";
			} else {
				url = "http://qa.fzzqft.com/portaljava/codeCoverage/addAppByAgent";
			}
			String postData = "{\"appName\":\"" + appName + "\", \"env\":\""
					+ env + "\", \"ip\":\"" + localIp + "\"}";
			String response = sendPostRequest(url, postData);
			System.out.println("Registration Successful: " + response);
		} catch (Exception e) {
			System.out.println("Registration Failed: " + e.getMessage());
		}

		final AgentOptions agentOptions = new AgentOptions(options);

		final Agent agent = Agent.getInstance(agentOptions);

		final IRuntime runtime = createRuntime(inst);
		runtime.startup(agent.getData());
		inst.addTransformer(new CoverageTransformer(runtime, agentOptions,
				IExceptionLogger.SYSTEM_ERR));
	}

	private static IRuntime createRuntime(final Instrumentation inst)
			throws Exception {

		if (AgentModule.isSupported()) {
			final AgentModule module = new AgentModule();
			module.openPackage(inst, Object.class);
			final Class<InjectedClassRuntime> clazz = module
					.loadClassInModule(InjectedClassRuntime.class);
			return clazz.getConstructor(Class.class, String.class)
					.newInstance(Object.class, "$JaCoCo");
		}

		return ModifiedSystemClassRuntime.createFor(inst,
				"java/lang/UnknownError");
	}

	// 新增逻辑
	public static String sendPostRequest(String urlString, String postData)
			throws IOException {
		URL url = new URL(urlString);
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();

		// 配置请求
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type",
				"application/json; charset=utf-8");
		connection.setRequestProperty("User-Agent", "Java/HttpURLConnection");
		// fone实例部署或重启时，短时间内同时存在新旧实例，需加长超时时间，以便等待旧实例关闭
		connection.setConnectTimeout(60000);
		connection.setDoOutput(true);

		// 写入请求
		try (OutputStream os = connection.getOutputStream()) {
			byte[] input = postData.getBytes(StandardCharsets.UTF_8);
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
				return response.toString();
			}
		} else {
			String errorMessage = connection.getResponseMessage();
			throw new IOException("Registration Failed: " + errorMessage);
		}
	}

}
