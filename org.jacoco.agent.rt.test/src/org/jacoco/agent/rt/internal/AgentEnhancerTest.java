/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    kevin - 端口冲突处理（宝兰德BES）单元测试
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.ServerSocket;

import org.jacoco.core.runtime.AgentOptions;
import org.junit.Test;

/**
 * Unit tests for {@link AgentEnhancer#adjustPortOnConflict}.
 */
public class AgentEnhancerTest {

	/**
	 * port=0 由系统分配空闲端口，不应被调整。
	 */
	@Test
	public void testFreePortIsNotChanged() {
		final AgentOptions options = new AgentOptions("port=0");
		assertFalse(AgentEnhancer.adjustPortOnConflict(options));
		assertEquals(0, options.getPort());
	}

	/**
	 * 端口被占用（如宝兰德BES先占），应 +1 调整（address in use 缓解）。
	 */
	@Test
	public void testOccupiedPortIsAdjusted() throws Exception {
		final int port;
		try (ServerSocket occupied = new ServerSocket(0)) {
			port = occupied.getLocalPort();
			final AgentOptions options = new AgentOptions("port=" + port);
			assertTrue(AgentEnhancer.adjustPortOnConflict(options));
			assertEquals(port + 1, options.getPort());
		}
	}

	/**
	 * 非法端口（越界）探测不得抛异常（增强失败不得影响 agent 启动）。
	 */
	@Test
	public void testInvalidPortIsNotAdjustedAndDoesNotThrow() {
		final AgentOptions options = new AgentOptions("port=70000");
		assertFalse(AgentEnhancer.adjustPortOnConflict(options));
		assertEquals(70000, options.getPort());
	}

}
