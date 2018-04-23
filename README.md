red-packet-seckilling
=====================

### Usage

1. 运行时替换webroot下`index.html`的EventBus地址为当前局域网下的Server IP地址。
```javascript
var eventBus = new EventBus('http://10.8.10.65:8080/eventbus?clientId=' + clientId);
```

2. 直接运行`RedPacketSeckillingVerticle.java`, 当然也可以通过`io.vertx.core.Launcher`指定`Verticle`来启动。

3. 在任意浏览器中输入`http://10.8.10.65:8080/`即可访问服务器并自动建立Socket连接。

4. 模拟发送一个红包是通过调用API接口来实现的, `http://localhost:8080/api/distributeRedPacket?money=10&count=2`, 触发EventBus。

5. 在客户端监听到有红包时, 自动发起抢红包动作。