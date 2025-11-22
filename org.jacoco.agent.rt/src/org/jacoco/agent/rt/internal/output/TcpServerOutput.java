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
package org.jacoco.agent.rt.internal.output;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import org.jacoco.agent.rt.internal.IExceptionLogger;
import org.jacoco.agent.rt.internal.OkHttpUtil;
import org.jacoco.core.runtime.AgentOptions;
import org.jacoco.core.runtime.RuntimeData;

/**
 * Output that opens TCP server socket. This controller uses the following agent
 * options:
 * <ul>
 * <li>address</li>
 * <li>port</li>
 * </ul>
 */
public class TcpServerOutput implements IAgentOutput {

	private TcpConnection connection;

	private final IExceptionLogger logger;

	private ServerSocket serverSocket;

	private Thread worker;

	/**
	 * New controller instance.
	 *
	 * @param logger
	 *            logger to use in case of exceptions is spawned threads
	 */
	public TcpServerOutput(final IExceptionLogger logger) {
		this.logger = logger;
	}

	public void startup(final AgentOptions options, final RuntimeData data)
			throws IOException {
		// 被占用则端口号+1，解决宝兰德BES的端口冲突问题（address in use）
		try (ServerSocket socket = new ServerSocket(options.getPort())) {
			socket.setReuseAddress(true);
		} catch (IOException e) {
			options.setPort(options.getPort() + 1);
			String urlString = "http://qa.fzzqft.com/portaljava/codeCoverage/agent";
			Map<String, Object> bodyMap = new HashMap<>();
			bodyMap.put("action", "updatePort");
			bodyMap.put("port", options.getPort());
			OkHttpUtil.post(urlString, bodyMap, "更新代码覆盖率服务端口");
		}

		serverSocket = createServerSocket(options);
		worker = new Thread(new Runnable() {
			public void run() {
				while (!serverSocket.isClosed()) {
					try {
						synchronized (serverSocket) {
							connection = new TcpConnection(
									serverSocket.accept(), data);
						}
						connection.init();
						connection.run();
					} catch (final IOException e) {
						// If the serverSocket is closed while accepting
						// connections a SocketException is expected.
						if (!serverSocket.isClosed()) {
							logger.logExeption(e);
						}
					}
				}
			}
		});
		worker.setName(getClass().getName());
		worker.setDaemon(true);
		worker.start();
	}

	public void shutdown() throws Exception {
		serverSocket.close();
		synchronized (serverSocket) {
			if (connection != null) {
				connection.close();
			}
		}
		worker.join();
	}

	public void writeExecutionData(final boolean reset) throws IOException {
		if (connection != null) {
			connection.writeExecutionData(reset);
		}
	}

	/**
	 * Open a server socket based on the given configuration.
	 *
	 * @param options
	 *            address and port configuration
	 * @return opened server socket
	 * @throws IOException
	 */
	protected ServerSocket createServerSocket(final AgentOptions options)
			throws IOException {
		final InetAddress inetAddr = getInetAddress(options.getAddress());
		return new ServerSocket(options.getPort(), 1, inetAddr);
	}

	/**
	 * Returns the {@link InetAddress} object to open the server socket on.
	 *
	 * @param address
	 *            address specified as a string
	 * @return address to open the server socket
	 * @throws UnknownHostException
	 */
	protected InetAddress getInetAddress(final String address)
			throws UnknownHostException {
		if ("*".equals(address)) {
			return null;
		} else {
			return InetAddress.getByName(address);
		}
	}

}
