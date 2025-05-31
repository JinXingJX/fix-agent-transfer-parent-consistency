/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.models.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.ToolConfig;
import com.google.genai.types.Type;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.reactivex.rxjava3.core.Flowable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class LangChain4j extends BaseLlm {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {
    };

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;

    public LangChain4j(ChatModel chatModel) { // TODO
        super(chatModel.defaultRequestParameters().modelName());
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel = null;
        this.objectMapper = new ObjectMapper();
    }

    public LangChain4j(ChatModel chatModel, String modelName) { // TODO
        super(modelName);
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel = null;
        this.objectMapper = new ObjectMapper();
    }

    public LangChain4j(StreamingChatModel streamingChatModel) { // TODO
        super(streamingChatModel.defaultRequestParameters().modelName());
        this.chatModel = null;
        this.streamingChatModel = Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
    }

    public LangChain4j(StreamingChatModel streamingChatModel, String modelName) { // TODO
        super(modelName);
        this.chatModel = null;
        this.streamingChatModel = Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (stream) {
            if (this.streamingChatModel == null) {
                return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
            }

            // TODO implement streaming
            throw new UnsupportedOperationException("Streaming is not supported for LangChain4j models yet.");
        } else {
            if (this.chatModel == null) {
                return Flowable.error(new IllegalStateException("ChatModel is not configured"));
            }

            ChatRequest chatRequest = toChatRequest(llmRequest);
            ChatResponse chatResponse = chatModel.chat(chatRequest);
            LlmResponse llmResponse = toLlmResponse(chatResponse);
            return Flowable.just(llmResponse);
        }
    }

    private ChatRequest toChatRequest(LlmRequest llmRequest) {
        // TODO llmRequest.model() ?
        ChatRequest.Builder requestBuilder = ChatRequest.builder();

        List<ToolSpecification> toolSpecifications = toToolSpecifications(llmRequest);
        requestBuilder.toolSpecifications(toolSpecifications);

        if (llmRequest.config().isPresent()) {
            GenerateContentConfig generateContentConfig = llmRequest.config().get();

            generateContentConfig.temperature().ifPresent(temp ->
                requestBuilder.temperature(temp.doubleValue()));
            generateContentConfig.topP().ifPresent(topP ->
                requestBuilder.topP(topP.doubleValue()));
            generateContentConfig.topK().ifPresent(topK ->
                requestBuilder.topK(topK.intValue()));
            generateContentConfig.maxOutputTokens().ifPresent(requestBuilder::maxOutputTokens);
            generateContentConfig.stopSequences().ifPresent(requestBuilder::stopSequences);
            generateContentConfig.frequencyPenalty().ifPresent(freqPenalty ->
                requestBuilder.frequencyPenalty(freqPenalty.doubleValue()));
            generateContentConfig.presencePenalty().ifPresent(presPenalty ->
                requestBuilder.presencePenalty(presPenalty.doubleValue()));

            if (generateContentConfig.toolConfig().isPresent()) {
                ToolConfig toolConfig = generateContentConfig.toolConfig().get();
                toolConfig.functionCallingConfig().ifPresent(functionCallingConfig -> {
                    functionCallingConfig.mode().ifPresent(functionMode -> {
                        // TODO
                        if (functionMode.knownEnum().equals(FunctionCallingConfigMode.Known.AUTO)) {
                            requestBuilder.toolChoice(ToolChoice.AUTO);
                        } else if (functionMode.knownEnum().equals(FunctionCallingConfigMode.Known.ANY)) {
                            // TODO check if it's the correct mapping
                            requestBuilder.toolChoice(ToolChoice.REQUIRED);
                            functionCallingConfig.allowedFunctionNames().ifPresent(allowedFunctionNames -> {
                                requestBuilder.toolSpecifications(
                                toolSpecifications.stream()
                                    .filter(toolSpecification ->
                                        allowedFunctionNames.contains(toolSpecification.name()))
                                    .toList());
                            });
                        } else if (functionMode.knownEnum().equals(FunctionCallingConfigMode.Known.NONE)) {
                            requestBuilder.toolSpecifications(List.of());
                        }
                    });
                });
                toolConfig.retrievalConfig().ifPresent(retrievalConfig -> {
                    // TODO? It exposes Latitude / Longitude, what to do with this?
                });
            }
        }

        return requestBuilder
            .messages(toMessages(llmRequest))
            // TODO?
            .build();
    }

    private List<ChatMessage> toMessages(LlmRequest llmRequest) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.addAll(llmRequest.getSystemInstructions().stream().map(SystemMessage::from).toList());
        messages.addAll(llmRequest.contents().stream().map(this::toChatMessage).toList());
        return messages;
    }

    private ChatMessage toChatMessage(Content content) {
        String role = content.role().orElseThrow().toLowerCase(); // TODO
        return switch (role) {
            case "user" -> toUserOrToolResultMessage(content);
            case "model", "assistant" -> toAiMessage(content);
            default -> throw new IllegalStateException("Unexpected role: " + role);
        };
    }

    private ChatMessage toUserOrToolResultMessage(Content content) {
        List<String> texts = new ArrayList<>();
        ToolExecutionResultMessage toolExecutionResultMessage = null;

        for (Part part : content.parts().orElse(List.of())) {
            if (part.text().isPresent()) {
                texts.add(part.text().get());
            } else if (part.functionResponse().isPresent()) {
                // TODO multiple tool calls?
                FunctionResponse functionResponse = part.functionResponse().get();
                toolExecutionResultMessage = ToolExecutionResultMessage.from(
                    functionResponse.id().orElseThrow(),
                    functionResponse.name().orElseThrow(),
                    toJson(functionResponse.response().orElseThrow())
                );
            } else {
                throw new IllegalStateException("Either text or functionCall is expected, but was: " + part);
            }
        }

        if (toolExecutionResultMessage != null) {
            return toolExecutionResultMessage;
        } else {
            return UserMessage.from(String.join("\n", texts));
        }
    }

    private AiMessage toAiMessage(Content content) {
        List<String> texts = new ArrayList<>();
        List<ToolExecutionRequest> toolExecutionRequests = new ArrayList<>();

        content.parts().orElse(List.of()).forEach(part -> {
            if (part.text().isPresent()) {
                texts.add(part.text().get());
            } else if (part.functionCall().isPresent()) {
                FunctionCall functionCall = part.functionCall().get();
                ToolExecutionRequest toolExecutionRequest = ToolExecutionRequest.builder()
                    .id(functionCall.id().orElseThrow())
                    .name(functionCall.name().orElseThrow())
                    .arguments(toJson(functionCall.args().orElseThrow()))
                    .build();
                toolExecutionRequests.add(toolExecutionRequest);
            } else {
                throw new IllegalStateException("Either text or functionCall is expected, but was: " + part);
            }
        });

        return AiMessage.builder()
            .text(String.join("\n", texts))
            .toolExecutionRequests(toolExecutionRequests)
            .build();
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private List<ToolSpecification> toToolSpecifications(LlmRequest llmRequest) {
        List<ToolSpecification> toolSpecifications = new ArrayList<>();

        llmRequest.tools().values()
            .forEach(baseTool -> {
                if (baseTool.declaration().isPresent()) {
                    FunctionDeclaration functionDeclaration = baseTool.declaration().get();
                    if (functionDeclaration.parameters().isPresent()) {
                        Schema schema = functionDeclaration.parameters().get();
                        ToolSpecification toolSpecification = ToolSpecification.builder()
                            .name(baseTool.name())
                            .description(baseTool.description())
                            .parameters(toParameters(schema)) // TODO
                            .build();
                        toolSpecifications.add(toolSpecification);
                    } else {
                        // TODO exception or something else?
                        throw new IllegalStateException("Tool lacking parameters: " + baseTool);
                    }
                } else {
                    // TODO exception or something else?
                    throw new IllegalStateException("Tool lacking declaration: " + baseTool);
                }
            });

        return toolSpecifications; // TODO
    }

    private JsonObjectSchema toParameters(Schema schema) {
        if (schema.type().isPresent() && schema.type().get().knownEnum().equals(Type.Known.OBJECT)) {
            return JsonObjectSchema.builder()
                .addProperties(toProperties(schema))
                .required(schema.required().orElse(List.of()))
                .build(); // TODO
        } else {
            throw new UnsupportedOperationException("LangChain4jLlm does not support schema of type: " + schema.type());
        }
    }

    private Map<String, JsonSchemaElement> toProperties(Schema schema) {
        Map<String, Schema> properties = schema.properties().orElse(Map.of());
        Map<String, JsonSchemaElement> result = new HashMap<>();
        properties.forEach((k, v) -> result.put(k, toJsonSchemaElement(v)));
        return result;
    }

    private JsonSchemaElement toJsonSchemaElement(Schema schema) {
        Type type = schema.type().get(); // TODO
        return switch (type.knownEnum()) {
            case STRING -> JsonStringSchema.builder()
                .description(schema.description().orElse(null))
                .build();
            case NUMBER -> JsonNumberSchema.builder()
                .description(schema.description().orElse(null))
                .build();
            case INTEGER -> JsonIntegerSchema.builder()
                .description(schema.description().orElse(null))
                .build();
            case BOOLEAN -> JsonBooleanSchema.builder()
                .description(schema.description().orElse(null))
                .build();
            case ARRAY -> JsonArraySchema.builder()
                .description(schema.description().orElse(null))
                .items(toJsonSchemaElement(schema.items().orElseThrow()))
                .build();
            case OBJECT -> toParameters(schema);
            case TYPE_UNSPECIFIED ->
                throw new UnsupportedFeatureException("LangChain4jLlm does not support schema of type: " + type);
        };
    }

    private LlmResponse toLlmResponse(ChatResponse chatResponse) {
        Content content = Content.builder()
            .role("model")
            .parts(toParts(chatResponse.aiMessage()))
            .build();

        return LlmResponse.builder()
            .content(content)
            .build();
    }

    private List<Part> toParts(AiMessage aiMessage) {
        if (aiMessage.hasToolExecutionRequests()) {
            List<Part> parts = new ArrayList<>();
            aiMessage.toolExecutionRequests().forEach(toolExecutionRequest -> {
                FunctionCall functionCall = FunctionCall.builder()
                    .id(toolExecutionRequest.id() != null ? toolExecutionRequest.id() : UUID.randomUUID().toString())
                    .name(toolExecutionRequest.name())
                    .args(toArgs(toolExecutionRequest))
                    .build();
                Part part = Part.builder()
                    .functionCall(functionCall)
                    .build();
                parts.add(part);
            });
            return parts;
        } else {
            Part part = Part.builder()
                .text(aiMessage.text())
                .build();
            return List.of(part);
        }
    }

    private Map<String, Object> toArgs(ToolExecutionRequest toolExecutionRequest) {
        try {
            return objectMapper.readValue(toolExecutionRequest.arguments(), MAP_TYPE_REFERENCE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException("Live connection is not supported for LangChain4j models.");
    }
}