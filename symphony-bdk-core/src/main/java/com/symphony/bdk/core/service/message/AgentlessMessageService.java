package com.symphony.bdk.core.service.message;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

import com.symphony.bdk.core.auth.AuthSession;
import com.symphony.bdk.core.retry.RetryWithRecoveryBuilder;
import com.symphony.bdk.core.service.message.model.Message;
import com.symphony.bdk.gen.api.AttachmentsApi;
import com.symphony.bdk.gen.api.DefaultApi;
import com.symphony.bdk.gen.api.MessageApi;
import com.symphony.bdk.gen.api.MessageSuppressionApi;
import com.symphony.bdk.gen.api.MessagesApi;
import com.symphony.bdk.gen.api.PodApi;
import com.symphony.bdk.gen.api.StreamsApi;
import com.symphony.bdk.gen.api.model.V4Message;
import com.symphony.bdk.http.api.ApiClient;
import com.symphony.bdk.http.api.ApiException;
import com.symphony.bdk.http.api.util.TypeReference;
import com.symphony.bdk.template.api.TemplateEngine;
import com.symphony.scrypto.lib.Scrypto;
import com.symphony.scrypto.lib.ScryptoFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apiguardian.api.API;
import org.symphonyoss.symphony.messageml.MessageMLContext;
import org.symphonyoss.symphony.messageml.util.NoOpDataProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * Sending messages without using the Agent.
 */
@Slf4j
@API(status = API.Status.STABLE)
public class AgentlessMessageService extends MessageService {

  private static final String MESSAGE_TEMPLATE = "{"
      + "    \"version\": \"SOCIALMESSAGE\","
      + "    \"sendingApp\": \"lc\","
      + "    \"threadId\": \"%s\","
      + "    \"clientVersionInfo\": \"Agent-Unknown-Mac OS X-10.16\","
      + "    \"attachments\": [],"
      + "    \"format\": \"com.symphony.messageml.v2\","
      + "    \"text\": \"%s\","
      + "    \"entities\": {},"
      + "    \"presentationML\": \"%s\","
      + "    \"enforceExpressionFiltering\": true,"
      + "    \"msgFeatures\": 3,"
      + "    \"tokenIds\": []"
      + "}";

  public AgentlessMessageService(MessagesApi messagesApi, MessageApi messageApi,
      MessageSuppressionApi messageSuppressionApi, StreamsApi streamsApi, PodApi podApi, AttachmentsApi attachmentsApi,
      DefaultApi defaultApi, AuthSession authSession, TemplateEngine templateEngine,
      RetryWithRecoveryBuilder<?> retryBuilder) {
    super(messagesApi, messageApi, messageSuppressionApi, streamsApi, podApi, attachmentsApi, defaultApi, authSession,
        templateEngine, retryBuilder);
  }

  @Override
  protected V4Message doSendMessage(@Nonnull String streamId, @Nonnull Message message) throws ApiException {
    try {
      // BDK manipulates URL safe base64, here we want base64
      streamId = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(streamId));

      ApiClient apiClient = messagesApi.getApiClient();
      final ScryptoFactory scryptoFactory = new ScryptoFactory(apiClient.getBasePath(), apiClient.getBasePath());
      Scrypto scrypto = scryptoFactory.newScrypto(authSession.getSessionToken(), authSession.getKeyManagerToken());

      Map<String, String> authHeaders = new HashMap<>();
      authHeaders.put("Cookie", "skey=" + retrieveSkey());
      authHeaders.put("x-symphony-csrf-token", String.valueOf(System.currentTimeMillis()));

      MessageMLContext messageMl = new MessageMLContext(new NoOpDataProvider());
      messageMl.parseMessageML(message.getContent(), "", null);

      String encryptedText = Base64.getEncoder()
          .encodeToString(scrypto.encrypt(streamId, messageMl.getText().getBytes(StandardCharsets.UTF_8)));
      String encryptedMessage = Base64.getEncoder()
          .encodeToString(scrypto.encrypt(streamId, messageMl.getPresentationML().getBytes(StandardCharsets.UTF_8)));

      Map<String, Object> form = new HashMap<>();
      form.put("messagepayload", String.format(MESSAGE_TEMPLATE, streamId, encryptedText, encryptedMessage));

      return apiClient.invokeAPI(
          "/webcontroller/ingestor/v2/MessageService",
          "POST",
          emptyList(),
          null, // for 'multipart/form-data', body can be null
          authHeaders,
          emptyMap(),
          form,
          apiClient.selectHeaderAccept("application/json"),
          apiClient.selectHeaderContentType("application/x-www-form-urlencoded"),
          new String[0],
          new TypeReference<V4Message>() {}
      ).getData();

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String retrieveSkey() throws IOException {
    // brute parsing without checking the signature
    String content = authSession.getSessionToken().split("\\.")[1];
    JsonNode jsonNode = new ObjectMapper().readTree(Base64.getDecoder().decode(content));
    return jsonNode.path("sessionId").asText();
  }

}
