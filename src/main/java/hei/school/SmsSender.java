package hei.school;

import org.springframework.stereotype.Component;

// Simulated SMS provider: the real provider is not chosen yet, so the SMS is only logged.
// To switch provider later, only this class changes, not the grading code.
@Component
public class SmsSender {

    public void send(String phone, String text) {
        System.out.println("[SMS] " + phone + " : " + text);
    }
}
