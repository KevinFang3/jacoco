/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * SPDX-License-Identifier: EPL-2.0
 * Contributors:
 *    kevin - 目录树浏览测试
 *******************************************************************************/
package org.jacoco.agent.rt.internal.custom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link DirectoryBrowser}.
 */
public class DirectoryBrowserTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private void dir(final String path) {
		final File dir = new File(tmp.getRoot(), path);
		assertTrue(dir.isDirectory() || dir.mkdirs());
	}

	private File file(final String path) throws Exception {
		final File file = new File(tmp.getRoot(), path);
		assertTrue(file.getParentFile().isDirectory()
				|| file.getParentFile().mkdirs());
		assertTrue(file.createNewFile());
		return file;
	}

	private static DirectoryBrowser.Entry entry(
			final DirectoryBrowser.Result result, final String relative) {
		for (final DirectoryBrowser.Entry e : result.getEntries()) {
			if (relative.equals(e.getRelative())) {
				return e;
			}
		}
		return null;
	}

	@Test
	public void testDefaultDepthIsTwo() throws Exception {
		file("a.txt");
		file("L1/f1.jar");
		file("L1/L2/f2.jar");
		file("L1/L2/L3/f3.jar");

		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), null, null, null,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		assertEquals(DirectoryBrowser.DEFAULT_DEPTH, result.getDepth());
		assertNotNull(entry(result, "a.txt"));
		assertNotNull(entry(result, "L1"));
		assertNotNull(entry(result, "L1/f1.jar"));
		assertNotNull(entry(result, "L1/L2"));
		assertNull(entry(result, "L1/L2/f2.jar"));
	}

	@Test
	public void testFilterKeepsOnlyMatchingFiles() throws Exception {
		file("a.jar");
		file("b.txt");
		file("c.war");
		file("sub/d.jar");

		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), 2, "jar,war", null,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		assertNotNull(entry(result, "a.jar"));
		assertNotNull(entry(result, "c.war"));
		assertNotNull(entry(result, "sub/d.jar"));
		assertNotNull(entry(result, "sub"));
		assertNull(entry(result, "b.txt"));
	}

	@Test
	public void testDepthClampedToMax() {
		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), 99, null, null,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		assertEquals(DirectoryBrowser.MAX_DEPTH, result.getDepth());
	}

	@Test
	public void testLimitClampedToMax() {
		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), null, null, 9999,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		assertEquals(DirectoryBrowser.MAX_LIMIT, result.getLimit());
	}

	@Test
	public void testLimitTruncates() throws Exception {
		for (int i = 0; i < 10; i++) {
			file("f" + i + ".jar");
		}

		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), 1, null, 3,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		assertEquals(3, result.getEntries().size());
		assertTrue(result.isTruncated());
	}

	@Test
	public void testNonexistentOrFileReturnsNull() throws Exception {
		assertNull(DirectoryBrowser.browse(
				new File(tmp.getRoot(), "missing").getAbsolutePath(), null,
				null, null, tmp.getRoot().getAbsolutePath()));

		final File plain = file("plain.txt");
		assertNull(DirectoryBrowser.browse(plain.getAbsolutePath(), null, null,
				null, tmp.getRoot().getAbsolutePath()));
	}

	@Test
	public void testRelativePathResolvedAgainstWorkDir() throws Exception {
		dir("sub");

		final DirectoryBrowser.Result result = DirectoryBrowser.browse("sub",
				null, null, null, tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		assertEquals(new File(tmp.getRoot(), "sub").getCanonicalPath(),
				result.getRoot());
	}

	@Test
	public void testSymlinkNotFollowed() throws Exception {
		dir("real");
		file("real/inside.jar");
		final File link = new File(tmp.getRoot(), "link");
		Files.createSymbolicLink(link.toPath(),
				new File(tmp.getRoot(), "real").toPath());

		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), 3, null, null,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		final DirectoryBrowser.Entry linkEntry = entry(result, "link");
		assertNotNull(linkEntry);
		assertEquals("link", linkEntry.getType());
		assertNull(entry(result, "link/inside.jar"));
	}

	@Test
	public void testJsonEscapesQuotes() throws Exception {
		file("a\"b.jar");

		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), 1, null, null,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		final String json = DirectoryBrowser.toJson(result);
		assertTrue(json.contains("a\\\"b.jar"));
		assertFalse(json.contains("a\"b.jar"));
	}

	@Test
	public void testEntriesCarryMetadata() throws Exception {
		final File jar = file("app.jar");
		assertTrue(jar.setLastModified(1700000000000L));

		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), 1, null, null,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		final DirectoryBrowser.Entry jarEntry = entry(result, "app.jar");
		assertNotNull(jarEntry);
		assertEquals("file", jarEntry.getType());
		assertEquals(jar.length(), jarEntry.getSize());
		assertEquals(1, jarEntry.getDepth());
		assertNotNull(jarEntry.getMtime());
	}

	@Test
	public void testDefaultLimitIsTwoHundred() {
		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), null, null, null,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		assertEquals(DirectoryBrowser.DEFAULT_LIMIT, result.getLimit());
	}

	@Test
	public void testEmptyResultWhenDepthZero() {
		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), 0, null, null,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		assertEquals(0, result.getEntries().size());
	}

	@Test
	public void testEntriesSortedDirsFirstThenName() throws Exception {
		file("b.jar");
		dir("zeta");
		file("a.jar");
		dir("alpha");

		final DirectoryBrowser.Result result = DirectoryBrowser.browse(
				tmp.getRoot().getAbsolutePath(), 1, null, null,
				tmp.getRoot().getAbsolutePath());
		assertNotNull(result);

		final List<DirectoryBrowser.Entry> entries = result.getEntries();
		assertEquals("alpha", entries.get(0).getRelative());
		assertEquals("zeta", entries.get(1).getRelative());
		assertEquals("a.jar", entries.get(2).getRelative());
		assertEquals("b.jar", entries.get(3).getRelative());
	}
}
