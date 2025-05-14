package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LongRunningFunctionToolTest {
  // TODO: Add test for raw string return style.
  // FunctionTool currently only supports a map. We need to add functionality of returning raw
  // string.

  private TestLlm testLlm;
  private LlmAgent agent;
  private InMemoryRunner runner;
  private Session session;

  @Before
  public void setUp() {
    TestFunctions.reset();
  }

  @Test
  public void asyncFunction_handlesPendingAndResults() throws Exception {
    FunctionTool longRunningTool =
        LongRunningFunctionTool.create(
            TestFunctions.class.getMethod("increaseByOne", int.class, ToolContext.class));

    FunctionCall modelRequestForFunctionCall =
        FunctionCall.builder().name("increase_by_one").args(ImmutableMap.of("x", 1)).build();

    LlmResponse funcCallResponse = createFuncCallLlmResponse(modelRequestForFunctionCall);
    LlmResponse textResponse1 = createTextLlmResponse("response1");
    LlmResponse textResponse2 = createTextLlmResponse("response2");
    LlmResponse textResponse3 = createTextLlmResponse("response3");
    LlmResponse textResponse4 = createTextLlmResponse("response4");

    List<LlmResponse> allLlmResponses =
        ImmutableList.of(
            funcCallResponse, textResponse1, textResponse2, textResponse3, textResponse4);

    setUpAgentAndRunner(
        allLlmResponses, longRunningTool, "test description for pending and results");

    Content firstUserContent =
        Content.builder().role("user").parts(ImmutableList.of(Part.fromText("test1"))).build();

    ImmutableMap<String, Object> expectedInitialToolResponseMap;
    expectedInitialToolResponseMap = ImmutableMap.of("status", "pending");

    assertInitialInteractionAndEvents(
        firstUserContent, modelRequestForFunctionCall, expectedInitialToolResponseMap, "response1");

    assertSubsequentInteraction(
        "increase_by_one", ImmutableMap.of("status", "still waiting"), "response2", 3);

    assertSubsequentInteraction("increase_by_one", ImmutableMap.of("result", 2), "response3", 4);

    assertSubsequentInteraction("increase_by_one", ImmutableMap.of("result", 3), "response4", 5);

    assertThat(TestFunctions.functionCalledCount.get()).isEqualTo(1);
  }

  private static class TestFunctions {
    static AtomicInteger functionCalledCount = new AtomicInteger(0);

    static void reset() {
      functionCalledCount.set(0);
    }

    @Annotations.Schema(name = "increase_by_one", description = "Test func: increases by one")
    public static Maybe<Map<String, Object>> increaseByOne(int x, ToolContext toolContext) {
      functionCalledCount.incrementAndGet();
      return Maybe.just(ImmutableMap.of("status", "pending"));
    }
  }

  private LlmResponse createTextLlmResponse(String text) {
    return LlmResponse.builder()
        .content(
            Content.builder().role("model").parts(ImmutableList.of(Part.fromText(text))).build())
        .build();
  }

  private LlmResponse createFuncCallLlmResponse(FunctionCall functionCall) {
    return LlmResponse.builder()
        .content(
            Content.builder()
                .role("model")
                .parts(
                    ImmutableList.of(
                        Part.fromFunctionCall(
                            functionCall.name().get(), functionCall.args().get())))
                .build())
        .build();
  }

  private void setUpAgentAndRunner(
      List<LlmResponse> llmResponses, FunctionTool tool, String description) {
    testLlm = new TestLlm(llmResponses);
    agent =
        LlmAgent.builder()
            .name("root_agent")
            .model(testLlm)
            .tools(ImmutableList.of(tool))
            .description(description)
            .build();
    runner = new InMemoryRunner(agent, "test-user");
    session =
        runner
            .sessionService()
            .createSession(runner.appName(), "test-user-id", new ConcurrentHashMap<>(), null)
            .blockingGet();
  }

  private void assertModelFunctionCallInHistory(
      Content modelFcContent, FunctionCall expectedFc, String expectedFcId) {
    assertThat(modelFcContent.role()).hasValue("model");
    Part modelFcPartFromHistory = modelFcContent.parts().get().get(0);
    assertThat(modelFcPartFromHistory.functionCall()).isPresent();
    FunctionCall actualFcInHistory = modelFcPartFromHistory.functionCall().get();
    assertThat(actualFcInHistory.name()).isEqualTo(expectedFc.name());
    assertThat(actualFcInHistory.args()).isEqualTo(expectedFc.args());
    assertThat(actualFcInHistory.id()).hasValue(expectedFcId);
  }

  private void assertToolFunctionResponseInHistory(
      Content toolFrContent,
      String expectedName,
      Map<String, Object> expectedResponse,
      String expectedFcId) {
    assertThat(toolFrContent.role()).hasValue("user");
    Part toolFrPartFromHistory = toolFrContent.parts().get().get(0);
    assertThat(toolFrPartFromHistory.functionResponse()).isPresent();
    FunctionResponse actualFrInHistory = toolFrPartFromHistory.functionResponse().get();
    assertThat(actualFrInHistory.name()).hasValue(expectedName);
    assertThat(actualFrInHistory.response()).isEqualTo(Optional.of(expectedResponse));
    assertThat(actualFrInHistory.id()).hasValue(expectedFcId);
  }

  private void assertFunctionCallEvent(Event event, String expectedFcId) {
    FunctionCall eventFc = event.content().get().parts().get().get(0).functionCall().get();
    assertThat(eventFc.id()).hasValue(expectedFcId);
    assertThat(event.longRunningToolIds()).isPresent();
    assertThat(event.longRunningToolIds().get()).isNotEmpty();
  }

  private void assertFunctionResponseEvent(
      Event event, Map<String, Object> expectedResponse, String expectedFcId) {
    FunctionResponse eventFr = event.content().get().parts().get().get(0).functionResponse().get();
    assertThat(eventFr.response()).isEqualTo(Optional.of(expectedResponse));
    assertThat(eventFr.id()).hasValue(expectedFcId);
  }

  private void assertTextEvent(Event event, String expectedText) {
    Optional<String> textOptional = event.content().get().parts().get().get(0).text();
    assertThat(textOptional).isPresent();
    assertThat(textOptional.get()).isEqualTo(expectedText);
  }

  private void assertSubsequentInteraction(
      String functionName,
      Map<String, Object> responseMap,
      String expectedTextResponse,
      int expectedRequestCount) {
    Part responsePart = Part.fromFunctionResponse(functionName, responseMap);
    Content responseContent =
        Content.builder().role("user").parts(ImmutableList.of(responsePart)).build();
    List<Event> events =
        runner.runAsync(session.userId(), session.id(), responseContent).toList().blockingGet();
    assertThat(testLlm.getRequests()).hasSize(expectedRequestCount);
    assertThat(events).hasSize(1);
    assertTextEvent(events.get(0), expectedTextResponse);
  }

  private void assertInitialInteractionAndEvents(
      Content firstUserContent,
      FunctionCall modelRequestForFunctionCall,
      Map<String, Object> expectedInitialToolResponseMap,
      String expectedFirstLlmTextResponse) {

    List<Event> events =
        runner.runAsync(session.userId(), session.id(), firstUserContent).toList().blockingGet();

    // Assert LLM request history
    List<LlmRequest> requests = testLlm.getRequests();
    assertThat(requests).hasSize(2); // Initial user message, then user_msg + model_fc + tool_fr
    assertThat(requests.get(0).contents()).containsExactly(firstUserContent);

    List<Content> secondRequestContents = requests.get(1).contents();
    assertThat(secondRequestContents).hasSize(3);
    assertThat(secondRequestContents.get(0)).isEqualTo(firstUserContent);

    // Extract FunctionCall ID from history for subsequent assertions
    FunctionCall actualFcInHistory =
        secondRequestContents.get(1).parts().get().get(0).functionCall().get();
    String populatedFcId = actualFcInHistory.id().get();

    // Assert model's function call and tool's response in history
    assertModelFunctionCallInHistory(
        secondRequestContents.get(1), modelRequestForFunctionCall, populatedFcId);
    assertToolFunctionResponseInHistory(
        secondRequestContents.get(2),
        modelRequestForFunctionCall.name().get(),
        expectedInitialToolResponseMap,
        populatedFcId);

    // Assert function was called once
    assertThat(TestFunctions.functionCalledCount.get()).isEqualTo(1);

    // Assert events
    assertThat(events).hasSize(3); // FC event, FR event, Text event
    assertFunctionCallEvent(events.get(0), populatedFcId);
    assertFunctionResponseEvent(events.get(1), expectedInitialToolResponseMap, populatedFcId);
    assertTextEvent(events.get(2), expectedFirstLlmTextResponse);
  }
}
