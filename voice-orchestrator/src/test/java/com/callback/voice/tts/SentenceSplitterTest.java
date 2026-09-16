package com.callback.voice.tts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceSplitterTest {

    @Test
    void splitsOnSentenceTerminalPunctuation() {
        assertThat(SentenceSplitter.split("Tell me about yourself. What brings you here?"))
                .containsExactly("Tell me about yourself.", "What brings you here?");
    }

    @Test
    void singleSentenceWithoutTrailingPunctuationIsKeptAsIs() {
        assertThat(SentenceSplitter.split("Let's move to the next question")).containsExactly("Let's move to the next question");
    }

    @Test
    void blankTextProducesNoSentences() {
        assertThat(SentenceSplitter.split("   ")).isEmpty();
    }

}
