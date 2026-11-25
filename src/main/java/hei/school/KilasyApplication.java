package hei.school;

import java.util.function.BiConsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class KilasyApplication {

    public static void main(String[] args) {
        SpringApplication.run(KilasyApplication.class, args);
    }

    // Simulated SMS provider, called with (phone, text): the real provider is not chosen yet,
    // so the SMS is only logged. To switch provider later, only this bean changes, not the grading code.
    @Bean
    public BiConsumer<String, String> smsSender() {
        return (phone, text) -> System.out.println("[SMS] " + phone + " : " + text);
    }
}
