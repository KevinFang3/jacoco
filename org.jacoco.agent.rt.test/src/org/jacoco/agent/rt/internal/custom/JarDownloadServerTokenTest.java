/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * SPDX-License-Identifier: EPL-2.0
 * Contributors:
 *    kevin - token 解析（默认值 / 显式覆盖 / off 关闭）测试
 *******************************************************************************/
package org.jacoco.agent.rt.internal.custom;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link JarDownloadServer#resolveToken(String, String)}.
 */
public class JarDownloadServerTokenTest {

	@Test
	public void testDefaultTokenWhenNothingConfigured() {
		assertEquals("albert-einstein",
				JarDownloadServer.resolveToken(null, null));
		assertEquals("albert-einstein",
				JarDownloadServer.resolveToken("  ", ""));
	}

	@Test
	public void testExplicitPropertyWinsOverEnv() {
		assertEquals("from-property",
				JarDownloadServer.resolveToken("from-property", "from-env"));
	}

	@Test
	public void testEnvUsedWhenPropertyBlank() {
		assertEquals("from-env",
				JarDownloadServer.resolveToken(null, "from-env"));
	}

	@Test
	public void testOffDisablesAuthentication() {
		assertEquals("", JarDownloadServer.resolveToken("off", null));
		assertEquals("", JarDownloadServer.resolveToken(null, "OFF"));
	}

	@Test
	public void testBlankEnvFallsBackToDefault() {
		assertEquals("albert-einstein",
				JarDownloadServer.resolveToken(null, "   "));
	}
}
