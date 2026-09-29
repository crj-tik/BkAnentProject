package com.bkanent.interview.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignalAndClosingTest {

    @Test
    void hardFarewellDetected() {
        SignalDetector.Signals s = SignalDetector.detect("好，那就先这样吧，拜拜");
        assertTrue(s.hardFarewell());
        assertFalse(s.softComplete());
    }

    @Test
    void softCompleteDetected() {
        SignalDetector.Signals s = SignalDetector.detect("我说完了，就这些");
        assertTrue(s.softComplete());
        assertFalse(s.hardFarewell());
    }

    @Test
    void repeatProtestDetected() {
        assertTrue(SignalDetector.detect("这个问题你不是问过了吗").repeatProtest());
    }

    @Test
    void confusionDetected() {
        assertTrue(SignalDetector.detect("你说的什么意思").confusion());
    }

    @Test
    void correctionDetected() {
        assertTrue(SignalDetector.detect("刚才说错了，其实不是这样的").correction());
    }

    @Test
    void shortTrailingTextWaitsSilently() {
        assertTrue(SignalDetector.detect("当时是这样的，然后").trailingOff());
    }

    @Test
    void longTextNotTrailing() {
        assertFalse(SignalDetector.detect("当时是这样的，然后我们又去看第二套房，中介说业主急着卖可以再谈谈价格").trailingOff());
    }

    @Test
    void closeOnHardFarewellUnconditionally() {
        assertTrue(ClosingDetector.shouldClose(true, false, false));
    }

    @Test
    void softCompleteOnlyClosesWhenAllDone() {
        assertFalse(ClosingDetector.shouldClose(false, true, false));
        assertTrue(ClosingDetector.shouldClose(false, true, true));
    }

    @Test
    void lockedReplyIsShortAndFixed() {
        String reply = ClosingDetector.lockedReplyText();
        assertTrue(reply.length() <= 40);
        assertTrue(reply.contains("感谢"));
    }
}
