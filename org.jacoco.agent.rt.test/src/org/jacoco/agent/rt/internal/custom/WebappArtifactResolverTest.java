/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * SPDX-License-Identifier: EPL-2.0
 * Contributors:
 *    kevin - WAR / 解压目录自动识别测试
 *******************************************************************************/
package org.jacoco.agent.rt.internal.custom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link WebappArtifactResolver}.
 */
public class WebappArtifactResolverTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File ensureDir(final String path) {
		final File dir = new File(tmp.getRoot(), path);
		assertTrue(dir.isDirectory() || dir.mkdirs());
		return dir;
	}

	private File newWar() throws Exception {
		final File war = new File(ensureDir("webapps"), "app.war");
		assertTrue(war.createNewFile());
		return war;
	}

	private File webappDir(final String name) {
		ensureDir("webapps/" + name + "/WEB-INF/classes");
		return new File(tmp.getRoot(), "webapps/" + name);
	}

	@Test
	public void testResolvePackedWarFromCodeSource() throws Exception {
		final File war = newWar();
		final String url = "jar:" + war.toURI().toURL().toExternalForm()
				+ "!/WEB-INF/classes/com/Foo.class";

		final WebappArtifactResolver.Artifact artifact = WebappArtifactResolver
				.resolve(Collections.singletonList(url), "app",
						Collections.emptyList());

		assertNotNull(artifact);
		assertEquals(WebappArtifactResolver.Kind.WAR, artifact.getKind());
		assertEquals(war.getCanonicalPath(), artifact.getPath().getPath());
	}

	@Test
	public void testResolveExplodedDirFromCodeSource() throws Exception {
		final File webapp = webappDir("app");
		final String url = new File(webapp, "WEB-INF/classes").toURI().toURL()
				.toExternalForm();

		final WebappArtifactResolver.Artifact artifact = WebappArtifactResolver
				.resolve(Collections.singletonList(url), "app",
						Collections.emptyList());

		assertNotNull(artifact);
		assertEquals(WebappArtifactResolver.Kind.WEBAPP_DIR,
				artifact.getKind());
		assertEquals(webapp.getCanonicalPath(), artifact.getPath().getPath());
	}

	@Test
	public void testPrefersSiblingWarOverExplodedDir() throws Exception {
		final File webapp = webappDir("app");
		final File war = newWar();
		final String url = new File(webapp, "WEB-INF/classes").toURI().toURL()
				.toExternalForm();

		final WebappArtifactResolver.Artifact artifact = WebappArtifactResolver
				.resolve(Collections.singletonList(url), "app",
						Collections.emptyList());

		assertNotNull(artifact);
		assertEquals(WebappArtifactResolver.Kind.WAR, artifact.getKind());
		assertEquals(war.getCanonicalPath(), artifact.getPath().getPath());
	}

	@Test
	public void testResolveFromLibJarUrl() throws Exception {
		final File webapp = webappDir("app");
		final File lib = ensureDir("webapps/app/WEB-INF/lib");
		final File jar = new File(lib, "app-core.jar");
		assertTrue(jar.createNewFile());
		final String url = jar.toURI().toURL().toExternalForm();

		final WebappArtifactResolver.Artifact artifact = WebappArtifactResolver
				.resolve(Collections.singletonList(url), "app",
						Collections.emptyList());

		assertNotNull(artifact);
		assertEquals(WebappArtifactResolver.Kind.WEBAPP_DIR,
				artifact.getKind());
		assertEquals(webapp.getCanonicalPath(), artifact.getPath().getPath());
	}

	@Test
	public void testMultipleWebappsMatchAppName() throws Exception {
		final File first = webappDir("app-one");
		final File second = webappDir("app-two");
		final List<String> locations = Arrays.asList(
				new File(first, "WEB-INF/classes").toURI().toURL()
						.toExternalForm(),
				new File(second, "WEB-INF/classes").toURI().toURL()
						.toExternalForm());

		final WebappArtifactResolver.Artifact artifact = WebappArtifactResolver
				.resolve(locations, "group_app-two", Collections.emptyList());

		assertNotNull(artifact);
		assertEquals(second.getCanonicalPath(), artifact.getPath().getPath());
	}

	@Test
	public void testMultipleWebappsWithoutMatchReturnsNull() throws Exception {
		final File first = webappDir("app-one");
		final File second = webappDir("app-two");
		final List<String> locations = Arrays.asList(
				new File(first, "WEB-INF/classes").toURI().toURL()
						.toExternalForm(),
				new File(second, "WEB-INF/classes").toURI().toURL()
						.toExternalForm());

		assertNull(WebappArtifactResolver.resolve(locations, "other-app",
				Collections.emptyList()));
	}

	@Test
	public void testNonWebappLocationsIgnored() throws Exception {
		final File jar = new File(ensureDir("lib"), "foo.jar");
		assertTrue(jar.createNewFile());
		final String url = jar.toURI().toURL().toExternalForm();

		assertNull(
				WebappArtifactResolver.resolve(Collections.singletonList(url),
						"app", Collections.emptyList()));
	}

	@Test
	public void testScanFallbackFindsWar() throws Exception {
		final File war = newWar();

		final WebappArtifactResolver.Artifact artifact = WebappArtifactResolver
				.resolve(Collections.emptyList(), "app",
						Collections.singletonList(tmp.getRoot()));

		assertNotNull(artifact);
		assertEquals(WebappArtifactResolver.Kind.WAR, artifact.getKind());
		assertEquals(war.getCanonicalPath(), artifact.getPath().getPath());
	}

	@Test
	public void testScanFallbackFindsExplodedDir() throws Exception {
		final File webapp = webappDir("app");

		final WebappArtifactResolver.Artifact artifact = WebappArtifactResolver
				.resolve(Collections.emptyList(), "app",
						Collections.singletonList(tmp.getRoot()));

		assertNotNull(artifact);
		assertEquals(WebappArtifactResolver.Kind.WEBAPP_DIR,
				artifact.getKind());
		assertEquals(webapp.getCanonicalPath(), artifact.getPath().getPath());
	}

	@Test
	public void testZipWebappKeepsWarLayout() throws Exception {
		final File webapp = webappDir("app");
		final File classes = new File(webapp, "WEB-INF/classes");
		final File classFile = new File(classes, "com/Foo.class");
		assertTrue(classFile.getParentFile().mkdirs());
		try (FileOutputStream out = new FileOutputStream(classFile)) {
			out.write("CLASS".getBytes(StandardCharsets.UTF_8));
		}
		final File lib = ensureDir("webapps/app/WEB-INF/lib");
		final File jar = new File(lib, "app-core.jar");
		try (FileOutputStream out = new FileOutputStream(jar)) {
			out.write("JAR".getBytes(StandardCharsets.UTF_8));
		}

		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
			WebappArtifactResolver.zipWebapp(webapp, zip);
		}

		final File zipFile = tmp.newFile("synthetic.war");
		try (FileOutputStream out = new FileOutputStream(zipFile)) {
			out.write(buffer.toByteArray());
		}
		try (ZipFile zf = new ZipFile(zipFile)) {
			assertNotNull(zf.getEntry("WEB-INF/classes/com/Foo.class"));
			assertNotNull(zf.getEntry("WEB-INF/lib/app-core.jar"));
			final byte[] content = readAll(zf.getInputStream(
					zf.getEntry("WEB-INF/classes/com/Foo.class")));
			assertEquals("CLASS", new String(content, StandardCharsets.UTF_8));
			assertEquals(ZipEntry.STORED,
					zf.getEntry("WEB-INF/lib/app-core.jar").getMethod());
		}
	}

	@Test
	public void testResolveCacheReusesSuccess() {
		WebappArtifactResolver.resetCache();
		final int[] calls = { 0 };
		final WebappArtifactResolver.Artifact artifact = new WebappArtifactResolver.Artifact(
				WebappArtifactResolver.Kind.WAR, tmp.getRoot());
		final WebappArtifactResolver.ArtifactResolver resolver = () -> {
			calls[0]++;
			return artifact;
		};
		try {
			assertSame(artifact,
					WebappArtifactResolver.resolveWithCache(1000L, resolver));
			assertSame(artifact,
					WebappArtifactResolver.resolveWithCache(2000L, resolver));
			assertEquals(1, calls[0]);
		} finally {
			WebappArtifactResolver.resetCache();
		}
	}

	@Test
	public void testResolveCacheRetriesAfterFailureInterval() {
		WebappArtifactResolver.resetCache();
		final int[] calls = { 0 };
		final WebappArtifactResolver.ArtifactResolver resolver = () -> {
			calls[0]++;
			return null;
		};
		try {
			assertNull(
					WebappArtifactResolver.resolveWithCache(1000L, resolver));
			assertEquals(1, calls[0]);
			assertNull(
					WebappArtifactResolver.resolveWithCache(2000L, resolver));
			assertEquals(1, calls[0]);
			assertNull(
					WebappArtifactResolver.resolveWithCache(12000L, resolver));
			assertEquals(2, calls[0]);
		} finally {
			WebappArtifactResolver.resetCache();
		}
	}

	private static byte[] readAll(final InputStream in) throws IOException {
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		final byte[] chunk = new byte[4096];
		int n;
		while ((n = in.read(chunk)) != -1) {
			buffer.write(chunk, 0, n);
		}
		return buffer.toByteArray();
	}
}
