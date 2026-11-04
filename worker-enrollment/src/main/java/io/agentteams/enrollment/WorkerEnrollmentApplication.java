package io.agentteams.enrollment;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkerEnrollmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerEnrollmentApplication.class, args);
    }

    @Bean
    Clock enrollmentClock() {
        return Clock.systemUTC();
    }
}
