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
package org.jacoco.agent.rt.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

}
