/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.channel;

import java.net.InetSocketAddress;
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.ProxyHandler;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.options.ClientOptions;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @param <CHANNEL> the channel type
 *
 * @author Stephane Maldini
 */
final class ClientContextHandler<CHANNEL extends Channel>
		extends CloseableContextHandler<CHANNEL> {

	static final Logger log = Loggers.getLogger(ClientContextHandler.class);

	final ClientOptions clientOptions;
	final boolean       secure;


	ClientContextHandler(BiFunction<? super CHANNEL,? super ContextHandler<CHANNEL>, ? extends ChannelOperations<?, ?>> channelOpSelector,
			ClientOptions options,
			MonoSink<NettyContext> sink,
			LoggingHandler loggingHandler,
			boolean secure) {
		super(channelOpSelector, options, sink, loggingHandler);
		this.clientOptions = options;
		this.secure = secure;
	}

	@Override
	public final void fireContextActive(NettyContext context) {
		if(!fired) {
			fired = true;
			sink.success(context);
		}
	}

	@Override
	protected void doDropped(Channel channel) {
		channel.close();
		if(!fired) {
			fired = true;
			sink.success();
		}
	}

	@Override
	protected void doPipeline(Channel channel) {
		ChannelPipeline pipeline = channel.pipeline();
		InetSocketAddress remoteAddress = channel.remoteAddress() instanceof InetSocketAddress ? (InetSocketAddress) channel.remoteAddress() : null;

		addSslAndLogHandlers(clientOptions, sink, loggingHandler, secure, remoteAddress, pipeline);
		addProxyHandler(clientOptions, pipeline);
	}

	static void addProxyHandler(ClientOptions clientOptions, ChannelPipeline pipeline) {
		ProxyHandler proxy = clientOptions.getProxyHandler();
		if (proxy != null) {
			pipeline.addFirst(NettyPipeline.ProxyHandler, proxy);
		}
	}
}
