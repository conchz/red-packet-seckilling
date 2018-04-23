package com.github.owlgvt.rps;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RedPacketSeckillingVerticle extends AbstractVerticle {

    private static Logger log = LoggerFactory.getLogger(RedPacketSeckillingVerticle.class);

    private final DeliveryOptions deliveryOptions = new DeliveryOptions(new JsonObject().put("timeout", 5000));
    private final Map<String, ServerWebSocket> connectionMap = new ConcurrentHashMap<>();
    private final Map<String, RedPacket> redPacketMap = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(RedPacketSeckillingVerticle.class.getName());
    }

    @Override
    public void start() throws Exception {
        EventBus eventBus = vertx.eventBus();
        Router router = Router.router(vertx);

        router.route("/eventbus/*").handler(eventBusHandler());
        router.mountSubRouter("/api", apiRouter());
        router.route().failureHandler(ErrorHandler.create(true));
        router.route().handler(StaticHandler.create().setCachingEnabled(false));

        HttpServer server = vertx.createHttpServer();
        server.websocketHandler(serverWebSocket -> {
            String clientId = serverWebSocket.query().replaceAll("clientId", "");
            connectionMap.putIfAbsent(clientId, serverWebSocket);
            serverWebSocket.closeHandler(handler -> connectionMap.remove(clientId));
            log.info("The number of currently established connections: {}", connectionMap.size());
        });

        server.requestHandler(router::accept).listen(8080, "0.0.0.0", res -> {
            if (res.succeeded()) {
                log.info("Server start successful");
            } else {
                log.error("Server start failed", res.cause());
                System.exit(1);
            }
        });

        //注册地址,然后对接收到客户端来的抢红包消息进行处理
        eventBus.consumer("channel.redPacketSeckillingServer", message -> {
            log.info("Received message from client: {}", message.body().toString());
            JsonObject requestBody = JsonObject.mapFrom(message.body());

            // 模拟延迟
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.error("Exception on consuming message", e);
            }

            if (!redPacketMap.containsKey(requestBody.getString("id"))) {
                log.warn("Not found red packet info by id {}", requestBody.getString("id"));
                eventBus.publish("channel." + requestBody.getString("clientId"), "Not found red packet info", deliveryOptions);
            }

            String resMsg = null;
            log.info("{}", redPacketMap.get(requestBody.getString("id")).toString());
            if (redPacketMap.get(requestBody.getString("id")).remainSize == 0) {
                resMsg = "红包已被抢完:(";
            } else {
                double money = getRandomMoney(redPacketMap.get(requestBody.getString("id")));
                resMsg = "您抢到了" + money + "元";
                log.info("Client {} grabbed {} 元", requestBody.getString("clientId"), money);
            }
            eventBus.publish("channel." + requestBody.getString("clientId"), resMsg, deliveryOptions);
        });
    }

    private SockJSHandler eventBusHandler() {
        SockJSHandlerOptions sockJSHandlerOptions = new SockJSHandlerOptions();
        BridgeOptions options = new BridgeOptions()
                .addInboundPermitted(new PermittedOptions().setAddressRegex("channel\\.[A-Za-z0-9]+"))
                .addOutboundPermitted(new PermittedOptions().setAddressRegex("channel\\.[A-Za-z0-9]+"));
        return SockJSHandler.create(vertx, sockJSHandlerOptions).bridge(options, bridgeEvent -> {
            switch (bridgeEvent.type()) {
                case SOCKET_CREATED:
                    log.info("A new socket is created");
                    break;
                case SOCKET_IDLE:
                    log.info("The socket is on idle for longer period of time than initially configured");
                    break;
                case SOCKET_PING:
//                    log.info("The last ping timestamp is updated for the socket");
                    break;
                case SOCKET_CLOSED:
                    log.info("A socket is closed");
                    break;
                case SEND:
                    log.info("A message is attempted to be sent from the client to the server");
                    break;
                case PUBLISH:
                    log.info("A message is attempted to be published from the client to the server");
                    break;
                case RECEIVE:
                    log.info("A message is attempted to be delivered from the server to the client");
                    break;
                case REGISTER:
                    log.info("A client attempts to register a handler");
                    break;
                case UNREGISTER:
                    log.info("A client attempts to unregister a handler");
                    break;
                default:
                    log.warn("Unknown Bridge Event Type: {}", bridgeEvent.type());
                    break;
            }

            bridgeEvent.complete(true);
        });
    }

    private Router apiRouter() {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route("/").handler(StaticHandler.create().setIndexPage("index.html"));
        router.route().consumes("application/json");
        router.route().produces("application/json");

        // distribute a red packet
        router.route("/distributeRedPacket").handler(routingContext -> {
            double money = routingContext.request().getParam("money") == null ? 10.0D
                    : Double.parseDouble(routingContext.request().getParam("money"));
            int count = routingContext.request().getParam("count") == null ? 5
                    : Integer.parseInt(routingContext.request().getParam("count"));
            String id = UUID.randomUUID().toString().replaceAll("-", "");
            JsonObject data = new JsonObject()
                    .put("id", id)
                    .put("time", System.currentTimeMillis());
            redPacketMap.put(id, new RedPacket(id, count, money));
            vertx.eventBus().publish("channel.redPacketSeckillingClient", data, deliveryOptions);
            log.info("Distributed a red packet at {}", LocalDateTime.now());

            routingContext.response().putHeader("Content-Type", "application/json; charset=utf-8")
                    .end(Json.encodePrettily(new JsonObject().put("status", "OK")));
        });

        return router;
    }


    class RedPacket {
        private String id;
        /**
         * 剩余的红包数量
         */
        private int remainSize;
        /**
         * 剩余的钱
         */
        private double remainMoney;

        RedPacket(String id, int remainSize, double remainMoney) {
            this.id = id;
            this.remainSize = remainSize;
            this.remainMoney = remainMoney;
        }

        @Override
        public String toString() {
            return "RedPacket{" +
                    "id='" + id + '\'' +
                    ", remainSize=" + remainSize +
                    ", remainMoney=" + new BigDecimal(remainMoney)
                    .setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue() +
                    '}';
        }
    }

    private double getRandomMoney(RedPacket redPacket) {
        if (redPacket.remainSize == 1) {
            redPacket.remainSize--;
            return (double) Math.round(redPacket.remainMoney * 100) / 100;
        }
        Random r = new Random();
        double min = 0.01;
        double max = redPacket.remainMoney / redPacket.remainSize * 2;
        double money = r.nextDouble() * max;
        money = money <= min ? 0.01 : money;
        money = Math.floor(money * 100) / 100;
        redPacket.remainSize--;
        redPacket.remainMoney -= money;
        return money;
    }
}
