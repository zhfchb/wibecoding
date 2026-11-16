package com.example.agent.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InterviewClockConfig {
    @Bean public Clock interviewSchedulingClock() { return Clock.systemUTC(); }
}
