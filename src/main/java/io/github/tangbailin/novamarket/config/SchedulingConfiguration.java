package io.github.tangbailin.novamarket.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="novamarket.scheduling.enabled", havingValue="true", matchIfMissing=true)
public class SchedulingConfiguration {}
