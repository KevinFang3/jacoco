/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    kevin - 本体 jar 自动识别测试
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal.custom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link ApplicationJarResolver}.
 */
public class ApplicationJarResolverTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void testResolveAbsoluteJarFromCommand() throws Exception {
		final File jar = tmp.newFile("app.jar");
		assertEquals(jar.getCanonicalPath(), ApplicationJarResolver.resolve(
				jar.getAbsolutePath(), null, tmp.getRoot().getAbsolutePath()));
	}

	@Test
	public void testResolveRelativeJarAgainstWorkDir() throws Exception {
		final File jar = tmp.newFile("app.jar");
		assertEquals(jar.getCanonicalPath(),
				ApplicationJarResolver.resolve(
						"app.jar --spring.profiles.active=qa", null,
						tmp.getRoot().getAbsolutePath()));
	}

	@Test
	public void testResolveFallsBackToSingleClasspathJar() throws Exception {
		final File jar = tmp.newFile("boot.jar");
		assertEquals(jar.getCanonicalPath(),
				ApplicationJarResolver.resolve("com.example.Main",
						jar.getAbsolutePath(),
						tmp.getRoot().getAbsolutePath()));
	}

	@Test
	public void testResolveReturnsNullForMultipleClasspathEntries()
			throws Exception {
		final File a = tmp.newFile("a.jar");
		final File b = tmp.newFile("b.jar");
		assertNull(ApplicationJarResolver.resolve("com.example.Main",
				a.getAbsolutePath() + File.pathSeparator + b.getAbsolutePath(),
				tmp.getRoot().getAbsolutePath()));
	}

	@Test
	public void testResolveReturnsNullForNonJarRuntime() {
		assertNull(ApplicationJarResolver.resolve("com.example.Main",
				"/tmp/classes", "/tmp"));
	}

	@Test
	public void testResolveReturnsNullWhenFileMissing() {
		assertNull(ApplicationJarResolver.resolve("/opt/app/lib/missing.jar",
				null, "/tmp"));
	}

	@Test
	public void testResolveReturnsNullWhenCommandEmpty() {
		assertNull(ApplicationJarResolver.resolve("", null, "/tmp"));
		assertNull(ApplicationJarResolver.resolve(null, null, "/tmp"));
	}
}
