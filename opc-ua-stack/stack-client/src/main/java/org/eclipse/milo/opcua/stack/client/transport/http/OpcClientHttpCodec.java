/*
 * Copyright (c) 2018 Kevin Herron
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *   http://www.eclipse.org/org/documents/edl-v10.html.
 */

package org.eclipse.milo.opcua.stack.client.transport.http;

import java.net.MalformedURLException;
import java.util.List;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.SOAPBody;
import javax.xml.soap.SOAPHeader;
import javax.xml.soap.SOAPMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import org.eclipse.milo.opcua.stack.client.UaStackClientConfig;
import org.eclipse.milo.opcua.stack.client.transport.UaTransportRequest;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.serialization.OpcUaBinaryStreamDecoder;
import org.eclipse.milo.opcua.stack.core.serialization.OpcUaBinaryStreamEncoder;
import org.eclipse.milo.opcua.stack.core.serialization.OpcUaXmlStreamDecoder;
import org.eclipse.milo.opcua.stack.core.serialization.OpcUaXmlStreamEncoder;
import org.eclipse.milo.opcua.stack.core.serialization.UaResponseMessage;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

public class OpcClientHttpCodec extends MessageToMessageCodec<HttpResponse, UaTransportRequest> {

    private static final AttributeKey<UaTransportRequest> KEY_PENDING_REQUEST =
        AttributeKey.newInstance("pendingRequest");

    private static final String UABINARY_CONTENT_TYPE =
        HttpHeaderValues.APPLICATION_OCTET_STREAM.toString();

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final EndpointDescription endpoint;
    private final TransportProfile transportProfile;

    private final UaStackClientConfig config;

    OpcClientHttpCodec(UaStackClientConfig config) throws MalformedURLException {
        this.config = config;

        endpoint = config.getEndpoint();
        transportProfile = TransportProfile.fromUri(endpoint.getTransportProfileUri());
    }

    @Override
    protected void encode(
        ChannelHandlerContext ctx,
        UaTransportRequest transportRequest,
        List<Object> out) throws Exception {

        logger.debug("encoding: " + transportRequest.getRequest());

        ctx.channel().attr(KEY_PENDING_REQUEST).set(transportRequest);

        ByteBuf content = Unpooled.buffer();

        switch (transportProfile) {
            case HTTPS_UABINARY: {
                OpcUaBinaryStreamEncoder encoder = new OpcUaBinaryStreamEncoder(content);
                encoder.writeMessage(null, transportRequest.getRequest());
                break;
            }

            case HTTPS_UAXML: {
                OpcUaXmlStreamEncoder encoder = new OpcUaXmlStreamEncoder();
                encoder.writeMessage(null, transportRequest.getRequest());

                MessageFactory messageFactory = MessageFactory.newInstance();
                SOAPMessage soapMessage = messageFactory.createMessage();

                SOAPHeader soapHeader = soapMessage.getSOAPHeader();
                soapHeader.detachNode();

                SOAPBody soapBody = soapMessage.getSOAPBody();
                soapBody.addDocument(encoder.getDocument());

                soapMessage.writeTo(new ByteBufOutputStream(content));
                break;
            }

            default:
                throw new UaException(StatusCodes.Bad_InternalError,
                    "no encoder for transport: " + transportProfile);
        }

        String endpointUrl = endpoint.getEndpointUrl();

        FullHttpRequest httpRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.POST,
            EndpointUtil.getPath(endpointUrl),
            content
        );

        httpRequest.headers().set(HttpHeaderNames.HOST, EndpointUtil.getHost(endpointUrl));
        httpRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        httpRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, UABINARY_CONTENT_TYPE);
        httpRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        httpRequest.headers().set("OPCUA-SecurityPolicy", config.getEndpoint().getSecurityPolicyUri());

        out.add(httpRequest);
    }

    @Override
    protected void decode(
        ChannelHandlerContext ctx,
        HttpResponse httpResponse,
        List<Object> out) throws Exception {

        logger.trace("channelRead0: " + httpResponse);

        UaTransportRequest transportRequest = ctx.channel()
            .attr(KEY_PENDING_REQUEST)
            .getAndSet(null);

        if (httpResponse instanceof FullHttpResponse) {
            String contentType = httpResponse.headers().get(HttpHeaderNames.CONTENT_TYPE);

            FullHttpResponse fullHttpResponse = (FullHttpResponse) httpResponse;
            ByteBuf content = fullHttpResponse.content();

            UaResponseMessage responseMessage;

            switch (transportProfile) {
                case HTTPS_UABINARY: {
                    if (!UABINARY_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
                        throw new UaException(StatusCodes.Bad_DecodingError,
                            "unexpected content-type: " + contentType);
                    }

                    OpcUaBinaryStreamDecoder decoder = new OpcUaBinaryStreamDecoder(content);
                    responseMessage = (UaResponseMessage) decoder.readMessage(null);
                    break;
                }

                case HTTPS_UAXML: {
                    MessageFactory messageFactory = MessageFactory.newInstance();

                    SOAPMessage soapMessage = messageFactory.createMessage(
                        null,
                        new ByteBufInputStream(content)
                    );

                    Document document = soapMessage.getSOAPBody().extractContentAsDocument();

                    OpcUaXmlStreamDecoder decoder = new OpcUaXmlStreamDecoder(document);
                    responseMessage = (UaResponseMessage) decoder.readMessage(null);
                    break;
                }

                default:
                    throw new UaException(StatusCodes.Bad_InternalError,
                        "no decoder for transport: " + transportProfile);
            }

            transportRequest.getFuture().complete(responseMessage);
        } else {
            HttpResponseStatus status = httpResponse.status();

            if (status.equals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE)) {
                transportRequest.getFuture().completeExceptionally(
                    new UaException(StatusCodes.Bad_ResponseTooLarge));
            } else {
                transportRequest.getFuture().completeExceptionally(
                    new UaException(StatusCodes.Bad_UnexpectedError,
                        String.format("%s: %s", status.code(), status.reasonPhrase())));
            }
        }
    }

}