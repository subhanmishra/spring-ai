package com.example.subhanmishra.controller;

import com.example.subhanmishra.dto.ChatAnswerDto;
import com.example.subhanmishra.event.TurnOrigin;
import com.example.subhanmishra.service.ChatFeedbackPublisher;
import com.example.subhanmishra.service.ChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code X-Eval-Origin} header is how the golden suite keeps its turns out of the live metrics, so
 * a header that is misread must fail loudly rather than quietly count a golden turn as live.
 */
@WebMvcTest(controllers = ChatController.class)
class ChatControllerOriginTest {

    private static final String BODY = "{\"prompt\":\"How do I change the port?\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private ChatFeedbackPublisher feedbackPublisher;

    @Test
    @DisplayName("a request without the header is a live turn")
    void absentIsLive() throws Exception {
        when(chatService.generate(anyString(), anyString(), any())).thenReturn(answer());

        mvc.perform(post("/ai/generate").contentType(MediaType.APPLICATION_JSON).content(BODY)
                                        .header(ConversationIdInterceptor.HEADER, "conv-1"))
           .andExpect(status().isOk());

        verify(chatService).generate("How do I change the port?", "conv-1", TurnOrigin.LIVE);
    }

    @Test
    @DisplayName("a supplied conversation id is used and echoed back")
    void suppliedConversationIdEchoed() throws Exception {
        when(chatService.generate(anyString(), anyString(), any())).thenReturn(answer());

        mvc.perform(post("/ai/generate").contentType(MediaType.APPLICATION_JSON).content(BODY)
                                        .header(ConversationIdInterceptor.HEADER, "conv-1"))
           .andExpect(status().isOk())
           .andExpect(header().string(ConversationIdInterceptor.HEADER, "conv-1"));

        verify(chatService).generate(anyString(), eq("conv-1"), any());
    }

    @Test
    @DisplayName("without a conversation id one is generated, used and returned")
    void missingConversationIdGenerated() throws Exception {
        when(chatService.generate(anyString(), anyString(), any())).thenReturn(answer());

        String returned = mvc.perform(post("/ai/generate").contentType(MediaType.APPLICATION_JSON).content(BODY))
                             .andExpect(status().isOk())
                             .andReturn().getResponse().getHeader(ConversationIdInterceptor.HEADER);

        assertThat(returned).isNotBlank();
        verify(chatService).generate(anyString(), eq(returned), any());
    }

    @Test
    @DisplayName("the golden suite's header marks the turn golden")
    void goldenHeader() throws Exception {
        when(chatService.generate(anyString(), anyString(), any())).thenReturn(answer());

        mvc.perform(post("/ai/generate").contentType(MediaType.APPLICATION_JSON).content(BODY)
                                        .header(ConversationIdInterceptor.HEADER, "conv-1")
                                        .header(TurnOrigin.HEADER, "GOLDEN"))
           .andExpect(status().isOk());

        verify(chatService).generate(anyString(), eq("conv-1"), eq(TurnOrigin.GOLDEN));
    }

    @Test
    @DisplayName("an unrecognised value is rejected, not read as live")
    void unknownValueRejected() throws Exception {
        mvc.perform(post("/ai/generate").contentType(MediaType.APPLICATION_JSON).content(BODY)
                                        .header(TurnOrigin.HEADER, "golden"))
           .andExpect(status().isBadRequest());

        verify(chatService, never()).generate(anyString(), anyString(), any());
    }

    private static ChatAnswerDto answer() {
        return new ChatAnswerDto(UUID.randomUUID(), "An answer.", false, List.of(), List.of(), null);
    }
}
