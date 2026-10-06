package com.example.subhanmishra.controller;

import com.example.subhanmishra.dto.FeedbackRequestDto;
import com.example.subhanmishra.event.ChatFeedbackSubmitted.Rating;
import com.example.subhanmishra.service.ChatFeedbackPublisher;
import com.example.subhanmishra.service.ChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The rating endpoint: accepted and handed on as given, or rejected before anything is published. */
@WebMvcTest(controllers = ChatController.class)
class ChatControllerFeedbackTest {

    private static final UUID TURN = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private ChatFeedbackPublisher feedbackPublisher;

    @Test
    @DisplayName("a rating is accepted and published against its turn")
    void accepted() throws Exception {
        mvc.perform(post("/ai/turns/{turnId}/feedback", TURN).contentType(MediaType.APPLICATION_JSON)
                                                             .content("{\"rating\":\"DOWN\",\"reason\":\"wrong page\"}"))
           .andExpect(status().isAccepted());

        verify(feedbackPublisher).submit(TURN, new FeedbackRequestDto(Rating.DOWN, "wrong page"));
    }

    @Test
    @DisplayName("a missing rating is rejected")
    void missingRating() throws Exception {
        mvc.perform(post("/ai/turns/{turnId}/feedback", TURN).contentType(MediaType.APPLICATION_JSON)
                                                             .content("{\"reason\":\"meh\"}"))
           .andExpect(status().isBadRequest());

        verify(feedbackPublisher, never()).submit(any(), any());
    }

    @Test
    @DisplayName("a turn id that is not a UUID is rejected")
    void badTurnId() throws Exception {
        mvc.perform(post("/ai/turns/not-a-uuid/feedback").contentType(MediaType.APPLICATION_JSON)
                                                         .content("{\"rating\":\"UP\"}"))
           .andExpect(status().isBadRequest());

        verify(feedbackPublisher, never()).submit(any(), any());
    }
}
