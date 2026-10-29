package com.example.tcpprobe;

import com.example.tcpprobe.config.ProbeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ProbeProperties.class)
public class TcpProbeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TcpProbeServiceApplication.class, args);
    }
}
