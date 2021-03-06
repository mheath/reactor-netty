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

package reactor.ipc.netty.http.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.AsciiString;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.http.Cookies;
import reactor.ipc.netty.http.HttpOperations;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;

/**
 * Conversion between Netty types  and Reactor types ({@link HttpOperations}.
 *
 * @author Stephane Maldini
 */
class HttpServerOperations extends HttpOperations<HttpServerRequest, HttpServerResponse>
		implements HttpServerRequest, HttpServerResponse {

	static HttpServerOperations bindHttp(Channel channel,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		return new HttpServerOperations(channel, handler, context);
	}

	final HttpResponse nettyResponse;
	final HttpHeaders  responseHeaders;

	Cookies                                       cookieHolder;
	HttpRequest                                   nettyRequest;
	Function<? super String, Map<String, String>> paramsResolver;

	HttpServerOperations(Channel ch, HttpServerOperations replaced) {
		super(ch, replaced);
		this.cookieHolder = replaced.cookieHolder;
		this.responseHeaders = replaced.responseHeaders;
		this.nettyResponse = replaced.nettyResponse;
		this.paramsResolver = replaced.paramsResolver;
	}

	HttpServerOperations(Channel ch,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		super(ch, handler, context);
		this.nettyResponse =
				new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		this.responseHeaders = nettyResponse.headers();
		responseHeaders.add(HttpHeaderNames.DATE, new Date());
	}

	@Override
	public HttpServerOperations addDecoder(ChannelHandler handler) {
		super.addDecoder(handler);
		return this;
	}

	@Override
	public HttpServerOperations addDecoder(String name, ChannelHandler handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		channel().pipeline()
		         .addBefore(NettyPipeline.HttpCodecHandler, name, handler);

		onClose(() -> removeHandler(name));
		return this;
	}

	@Override
	public HttpServerResponse addCookie(Cookie cookie) {
		if (!hasSentHeaders()) {
			this.responseHeaders.add(HttpHeaderNames.SET_COOKIE,
					ServerCookieEncoder.STRICT.encode(cookie));
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse addHeader(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.responseHeaders.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse chunkedTransfer(boolean chunked) {
		if (!hasSentHeaders()) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			HttpUtil.setTransferEncodingChunked(nettyResponse, chunked);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public Map<CharSequence, Set<Cookie>> cookies() {
		if (cookieHolder != null) {
			return cookieHolder.getCachedCookies();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpServerResponse disableChunkedTransfer() {
		HttpUtil.setTransferEncodingChunked(nettyResponse, false);
		return this;
	}

	@Override
	public HttpServerResponse header(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.responseHeaders.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public boolean isKeepAlive() {
		return HttpUtil.isKeepAlive(nettyRequest);
	}

	@Override
	public boolean isWebsocket() {
		return requestHeaders().contains(HttpHeaderNames.UPGRADE,
				HttpHeaderValues.WEBSOCKET,
				true);
	}

	@Override
	public HttpServerResponse keepAlive(boolean keepAlive) {
		HttpUtil.setKeepAlive(nettyResponse, keepAlive);
		return this;
	}

	@Override
	public HttpMethod method() {
		return nettyRequest.method();
	}

	@Override
	public String param(CharSequence key) {
		Objects.requireNonNull(key, "key");
		Map<String, String> params = null;
		if (paramsResolver != null) {
			params = this.paramsResolver.apply(uri());
		}
		return null != params ? params.get(key) : null;
	}

	@Override
	public Map<String, String> params() {
		return null != paramsResolver ? paramsResolver.apply(uri()) : null;
	}

	@Override
	public HttpServerRequest paramsResolver(Function<? super String, Map<String, String>> headerResolver) {
		this.paramsResolver = headerResolver;
		return this;
	}

	@Override
	public Flux<?> receiveObject() {
		// Handle the 'Expect: 100-continue' header if necessary.
		// TODO: Respond with 413 Request Entity Too Large
		//   and discard the traffic or close the connection.
		//       No need to notify the upstream handlers - just log.
		//       If decoding a response, just throw an error.
		if (HttpUtil.is100ContinueExpected(nettyRequest)) {
			return FutureMono.deferFuture(() -> channel().writeAndFlush(CONTINUE))
			                 .thenMany(super.receiveObject());
		}
		else {
			return super.receiveObject();
		}
	}

	@Override
	public HttpHeaders requestHeaders() {
		if (nettyRequest != null) {
			return nettyRequest.headers();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpHeaders responseHeaders() {
		return responseHeaders;
	}

	@Override
	public Mono<Void> send() {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		if (markHeadersAsSent()) {
			disableChunkedTransfer();
			responseHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
			return FutureMono.deferFuture(() -> channel().writeAndFlush(new DefaultFullHttpResponse(
					version(),
					status(),
					EMPTY_BUFFER,
					responseHeaders,
					EmptyHttpHeaders.INSTANCE)));
		}
		else {
			return Mono.empty();
		}
	}

	@Override
	public NettyOutbound sendFile(Path file) {
		try {
			return sendFile(file, 0L, Files.size(file));
		}
		catch (IOException e) {
			if(log.isDebugEnabled()){
				log.debug("Path not resolved",e);
			}
			return then(sendNotFound());
		}
	}

	@Override
	public Mono<Void> sendNotFound() {
		return this.status(HttpResponseStatus.NOT_FOUND)
		           .send();
	}

	@Override
	public Mono<Void> sendRedirect(String location) {
		Objects.requireNonNull(location, "location");
		return this.status(HttpResponseStatus.FOUND)
		           .header(HttpHeaderNames.LOCATION, location)
		           .send();
	}

	/**
	 * @return the Transfer setting SSE for this http connection (e.g. event-stream)
	 */
	@Override
	public HttpServerResponse sse() {
		header(HttpHeaderNames.CONTENT_TYPE, EVENT_STREAM);
		return this;
	}

	@Override
	public HttpResponseStatus status() {
		return HttpResponseStatus.valueOf(this.nettyResponse.status()
		                                                    .code());
	}

	@Override
	public HttpServerResponse status(HttpResponseStatus status) {
		if (!hasSentHeaders()) {
			this.nettyResponse.setStatus(status);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public Mono<Void> sendWebsocket(String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		return withWebsocketSupport(uri(), protocols, websocketHandler);
	}

	@Override
	public String uri() {
		if (nettyRequest != null) {
			return nettyRequest.uri();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpVersion version() {
		if (nettyRequest != null) {
			return nettyRequest.protocolVersion();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	protected void onChannelActive(ChannelHandlerContext ctx) {

		addHandler(NettyPipeline.HttpCodecHandler, new HttpServerCodec());
		if (ctx.pipeline()
		       .context(NettyPipeline.HttpKeepAlive) == null) {
			ctx.pipeline()
			   .addBefore(NettyPipeline.ReactiveBridge,
					   NettyPipeline.HttpKeepAlive,
					   new HttpServerKeepAliveHandler());
		}
		ctx.read();
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpRequest) {
			nettyRequest = (HttpRequest) msg;
			cookieHolder = Cookies.newServerRequestHolder(requestHeaders());

			if (nettyRequest.decoderResult()
			                .isFailure()) {
				onOutboundError(nettyRequest.decoderResult()
				                            .cause());
				return;
			}

			if (isWebsocket() && ctx.pipeline()
			                        .context(NettyPipeline.HttpAggregator) == null) {
				HttpObjectAggregator agg = new HttpObjectAggregator(65536);
				channel().pipeline()
				         .addBefore(NettyPipeline.ReactiveBridge,
						         NettyPipeline.HttpAggregator,
						         agg);
			}

			applyHandler();

			if (!(msg instanceof FullHttpRequest)) {
				return;
			}
		}
		if (msg instanceof HttpContent) {
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onInboundNext(ctx, msg);
			}
			if (msg instanceof LastHttpContent) {
				if (isTerminated()) {
					release();
				}
				else {
					onInboundComplete();
				}
				return;
			}
			if (isTerminated()) {
				ctx.read();
			}
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	protected void onOutboundComplete() {
		if(!channel().isOpen() || isDisposed()){
			return;
		}

		if (log.isDebugEnabled()) {
			log.debug("User Handler requesting a last HTTP frame write", formatName());
		}
		if (markInboundDone()) {
			release();
		}
		else {
			if (log.isDebugEnabled()) {
				log.debug("Consuming keep-alive connection, prepare to ignore extra " + "frames");
			}
			channel().read();
		}
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if (discreteRemoteClose(err)) {
			return;
		}
		if (markHeadersAsSent()) {
			log.error("Error starting response. Replying error status", err);

			HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.INTERNAL_SERVER_ERROR);
			response.headers()
			        .setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
			channel().writeAndFlush(response)
			         .addListener(r -> onChannelTerminate());
			return;
		}
		log.error("Error processing response. Sending last HTTP frame", err);

		if (HttpUtil.isContentLengthSet(nettyResponse)) {
			channel().writeAndFlush(EMPTY_BUFFER)
			         .addListener(r -> onChannelTerminate());
			return;
		}
		channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
		         .addListener(r -> onChannelTerminate());
	}

	@Override
	protected HttpMessage outboundHttpMessage() {
		return nettyResponse;
	}

	final void release() {
		ChannelFuture f = null;
		if (!isWebsocket()) {
			if (log.isDebugEnabled()) {
				log.debug("Last HTTP response frame");
			}
			if (markHeadersAsSent()) {
				if (!HttpUtil.isTransferEncodingChunked(nettyResponse) && !HttpUtil.isContentLengthSet(
						nettyResponse)) {
					HttpUtil.setContentLength(nettyResponse, 0);
				}
				if (HttpUtil.isContentLengthSet(nettyResponse)) {
					channel().writeAndFlush(nettyResponse);
				}
				else {
					channel().write(nettyResponse);
					f = channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
				}
			}
			else if (!HttpUtil.isContentLengthSet(nettyResponse)) {
				f = channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
			}

			if (isKeepAlive()) {
				if (f != null) {
					f.addListener(s -> {
						super.onChannelTerminate();
						if (!s.isSuccess() && log.isDebugEnabled()) {
							log.error("Failed flushing last frame", s.cause());
						}
					});
					return;
				}
				super.onChannelTerminate();
			}
		}
	}

	final Mono<Void> withWebsocketSupport(String url,
			String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		Objects.requireNonNull(websocketHandler, "websocketHandler");
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		if (markHeadersAsSent()) {
			HttpServerWSOperations ops = new HttpServerWSOperations(url, protocols, this);

			if (channel().attr(OPERATIONS_ATTRIBUTE_KEY)
			             .compareAndSet(this, ops)) {
				return FutureMono.from(ops.handshakerResult)
				                 .then(() -> Mono.from(websocketHandler.apply(ops, ops)))
				                 .doAfterTerminate(ops);
			}
		}
		else {
			log.error("Cannot enable websocket if headers have already been sent");
		}
		return Mono.error(new IllegalStateException("Failed to upgrade to websocket"));
	}

	static final Logger log = Loggers.getLogger(HttpServerOperations.class);

	final static AsciiString      EVENT_STREAM = new AsciiString("text/event-stream");
	final static FullHttpResponse CONTINUE     =
			new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.CONTINUE,
					EMPTY_BUFFER);
}
