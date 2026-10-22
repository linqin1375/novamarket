package io.github.tangbailin.novamarket.api;
import io.github.tangbailin.novamarket.adapter.InProcessMessageBus;
import io.github.tangbailin.novamarket.port.MessagePort;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@Profile("local")
@RequestMapping("/api/demo")
public class LocalDemoController {
    private final MessagePort messages;
    public LocalDemoController(MessagePort messages) { this.messages = messages; }
    @PostMapping("/message-failure")
    public Map<String,Object> failNextMessage(@RequestParam(defaultValue="1") int count) {
        if (count < 1 || count > 20) throw new IllegalArgumentException("故障次数须为 1-20");
        if (!(messages instanceof InProcessMessageBus bus)) throw new IllegalArgumentException("当前消息端口不支持本机故障演示");
        bus.failNext(count); return Map.of("armed",true,"count",count,"message","接下来 "+count+" 次消息投递将模拟失败，补偿任务随后重投");
    }
}
