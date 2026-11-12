/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    kevin - 端口上报 body 组装（缺失显性化、env 小写化）测试
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal.custom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link SimpleHttpUtil#buildReportBody}.
 */
public class SimpleHttpUtilTest {

	@Test
	public void testBuildReportBodyLowercasesEnv() {
		final Map<String, String> body = SimpleHttpUtil.buildReportBody(
				"reportDumpPort", "myApp", "PROD", "agentPort", "6301");

		assertEquals("reportDumpPort", body.get("action"));
		assertEquals("myApp", body.get("appName"));
		assertEquals("prod", body.get("env"));
		assertEquals("6301", body.get("agentPort"));
	}

	@Test
	public void testBuildReportBodyWithHttpPort() {
		final Map<String, String> body = SimpleHttpUtil.buildReportBody(
				"reportHttpPort", "myApp", "test", "httpPort", "6400");

		assertEquals("6400", body.get("httpPort"));
	}

	@Test
	public void testBuildReportBodyReturnsNullWhenAppNameMissing() {
		assertNull(SimpleHttpUtil.buildReportBody("reportDumpPort", null,
				"prod", "agentPort", "6301"));
	}

	@Test
	public void testBuildReportBodyReturnsNullWhenEnvMissing() {
		assertNull(SimpleHttpUtil.buildReportBody("reportDumpPort", "myApp",
				null, "agentPort", "6301"));
		assertNull(SimpleHttpUtil.buildReportBody("reportDumpPort", "myApp",
				"  ", "agentPort", "6301"));
	}

	@Test
	public void testBuildReportBodyWithExtraFields() {
		final Map<String, String> fields = new HashMap<String, String>();
		fields.put("jarPath", "/opt/app/lib/app.jar");
		fields.put("ip", "100.116.57.175");

		final Map<String, String> body = SimpleHttpUtil
				.buildReportBody("reportJarPath", "myApp", "QA", fields);

		assertEquals("reportJarPath", body.get("action"));
		assertEquals("myApp", body.get("appName"));
		assertEquals("qa", body.get("env"));
		assertEquals("/opt/app/lib/app.jar", body.get("jarPath"));
		assertEquals("100.116.57.175", body.get("ip"));
	}

	@Test
	public void testBuildReportBodyAllowsIpOnlyWhenNamesMissing() {
		final Map<String, String> fields = new HashMap<String, String>();
		fields.put("ip", "100.116.57.175");

		final Map<String, String> body = SimpleHttpUtil
				.buildReportBody("reportJarPath", null, null, fields);

		assertEquals("100.116.57.175", body.get("ip"));
		assertNull(body.get("appName"));
		assertNull(body.get("env"));
	}

	@Test
	public void testBuildReportBodyReturnsNullWithoutAnyIdentity() {
		assertNull(SimpleHttpUtil.buildReportBody("reportJarPath", null, null,
				new HashMap<String, String>()));
	}

	@Test
	public void testMapToJsonEscapesSpecialChars() {
		final Map<String, String> body = new HashMap<String, String>();
		body.put("jarPath", "/opt/a\"b\\c.jar");
		body.put("ip", "1.2.3.4");

		final String json = SimpleHttpUtil.mapToJson(body);

		assertTrue(json.contains("a\\\"b\\\\c.jar"));
		assertTrue(json.contains("\"ip\":\"1.2.3.4\""));
	}

}
