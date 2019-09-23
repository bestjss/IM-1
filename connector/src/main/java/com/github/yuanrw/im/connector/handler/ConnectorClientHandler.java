package com.github.yuanrw.im.connector.handler;

import com.github.yuanrw.im.common.domain.ack.ClientAckWindow;
import com.github.yuanrw.im.common.parse.AbstractMsgParser;
import com.github.yuanrw.im.common.parse.InternalParser;
import com.github.yuanrw.im.connector.domain.ClientConnContext;
import com.github.yuanrw.im.connector.service.ConnectorService;
import com.github.yuanrw.im.connector.service.UserOnlineService;
import com.github.yuanrw.im.protobuf.generate.Ack;
import com.github.yuanrw.im.protobuf.generate.Chat;
import com.github.yuanrw.im.protobuf.generate.Internal;
import com.google.inject.Inject;
import com.google.protobuf.Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

import static com.github.yuanrw.im.common.parse.AbstractMsgParser.checkDest;
import static com.github.yuanrw.im.common.parse.AbstractMsgParser.checkFrom;

/**
 * 处理客户端的消息
 * Date: 2019-02-09
 * Time: 23:26
 *
 * @author yrw
 */
public class ConnectorClientHandler extends SimpleChannelInboundHandler<Message> {
    private Logger logger = LoggerFactory.getLogger(ConnectorClientHandler.class);

    private ConnectorService connectorService;
    private UserOnlineService userOnlineService;
    private ClientConnContext clientConnContext;
    private FromClientParser fromClientParser;

    private ClientAckWindow clientAckWindow;

    @Inject
    public ConnectorClientHandler(ConnectorService connectorService, UserOnlineService userOnlineService, ClientConnContext clientConnContext) {
        this.fromClientParser = new FromClientParser();
        this.connectorService = connectorService;
        this.userOnlineService = userOnlineService;
        this.clientConnContext = clientConnContext;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.clientAckWindow = new ClientAckWindow(500);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
        logger.debug("[connector] get msg: {}", msg.toString());

        checkFrom(msg, Internal.InternalMsg.Module.CLIENT);
        checkDest(msg, Internal.InternalMsg.Module.CONNECTOR);

        fromClientParser.parse(msg, ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        //remove connection and update user's status
        userOnlineService.userOffline(ctx);
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("[IM ConnectorClientHandler] has error: ", cause);
        clientConnContext.removeConn(ctx);
    }

    class FromClientParser extends AbstractMsgParser {

        @Override
        public void registerParsers() {
            InternalParser parser = new InternalParser(3);
            parser.register(Internal.InternalMsg.MsgType.GREET, (m, ctx) ->
                offer(m.getId(), m, ctx, ignore -> userOnlineService.userOnline(m.getMsgBody(), ctx)));

            register(Chat.ChatMsg.class, (m, ctx) ->
                offer(m.getId(), m, ctx, ignore -> connectorService.doChatToClientOrTransferAndFlush(m)));

            register(Ack.AckMsg.class, (m, ctx) ->
                offer(m.getId(), m, ctx, ignore -> connectorService.doSendAckToClientOrTransferAndFlush(m))
            );
            register(Internal.InternalMsg.class, parser.generateFun());
        }

        private void offer(Long id, Message m, ChannelHandlerContext ctx, Consumer<Message> consumer) {
            clientAckWindow.offer(id,
                Internal.InternalMsg.Module.CONNECTOR,
                Internal.InternalMsg.Module.CLIENT,
                ctx, m, consumer);
        }
    }

    public void setClientAckWindow(ClientAckWindow clientAckWindow) {
        this.clientAckWindow = clientAckWindow;
    }
}
