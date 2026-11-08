package com.example.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class InterviewClockConfig {
    @Bean public Clock interviewSchedulingClock() { return Clock.systemUTC(); }
}
