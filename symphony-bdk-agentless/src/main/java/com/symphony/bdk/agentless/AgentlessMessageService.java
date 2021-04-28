package com.symphony.bdk.agentless;

import static java.util.Base64.getDecoder;
import static java.util.Base64.getUrlEncoder;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

import com.symphony.bdk.core.auth.AuthSession;
import com.symphony.bdk.core.client.ApiClientFactory;
import com.symphony.bdk.core.retry.RetryWithRecovery;
import com.symphony.bdk.core.retry.RetryWithRecoveryBuilder;
import com.symphony.bdk.core.service.message.MessageService;
import com.symphony.bdk.core.service.message.OboMessageService;
import com.symphony.bdk.core.service.message.model.Message;
import com.symphony.bdk.core.service.pagination.model.PaginationAttribute;
import com.symphony.bdk.core.service.stream.constant.AttachmentSort;
import com.symphony.bdk.core.util.function.SupplierWithApiException;
import com.symphony.bdk.gen.api.DefaultApi;
import com.symphony.bdk.gen.api.MessageApi;
import com.symphony.bdk.gen.api.MessageSuppressionApi;
import com.symphony.bdk.gen.api.PodApi;
import com.symphony.bdk.gen.api.StreamsApi;
import com.symphony.bdk.gen.api.model.MessageMetadataResponse;
import com.symphony.bdk.gen.api.model.MessageReceiptDetailResponse;
import com.symphony.bdk.gen.api.model.MessageStatus;
import com.symphony.bdk.gen.api.model.MessageSuppressionResponse;
import com.symphony.bdk.gen.api.model.StreamAttachmentItem;
import com.symphony.bdk.gen.api.model.V4ImportResponse;
import com.symphony.bdk.gen.api.model.V4ImportedMessage;
import com.symphony.bdk.gen.api.model.V4Message;
import com.symphony.bdk.gen.api.model.V4MessageBlastResponse;
import com.symphony.bdk.gen.api.model.V4Stream;
import com.symphony.bdk.http.api.ApiClient;
import com.symphony.bdk.http.api.ApiException;
import com.symphony.bdk.http.api.Pair;
import com.symphony.bdk.http.api.util.ApiUtils;
import com.symphony.bdk.http.api.util.TypeReference;
import com.symphony.bdk.template.api.TemplateEngine;
import com.symphony.scrypto.lib.Scrypto;
import com.symphony.scrypto.lib.ScryptoFactory;
import com.symphony.scrypto.lib.http.HttpClientException;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apiguardian.api.API;
import org.symphonyoss.symphony.messageml.MessageMLContext;
import org.symphonyoss.symphony.messageml.util.NoOpDataProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sending messages without using the Agent.
 * <p>
 * TODO
 * - for all non agent API are we going to reimplement all of them in BFB or just proxy? or still hit BFB?
 * - split MessageServiceImpl with non agent api so we can reuse it?
 * - why do we have so many *Api... and not V1PodApi, V1AgentApi and so on
 */
@Slf4j
@API(status = API.Status.INTERNAL)
public class AgentlessMessageService implements MessageService {

  private final AuthSession authSession;

  private final ScryptoFactory scryptoFactory;
  private final Scrypto scrypto;

  private final ApiClient bfbClient;
  private final MessageApi messageApi;
  private final PodApi podApi;
  private final StreamsApi streamsApi;
  private final DefaultApi defaultApi;
  private final MessageSuppressionApi messageSuppressionApi;

  private final RetryWithRecoveryBuilder<?> retryBuilder;
  private final TemplateEngine templateEngine;

  public AgentlessMessageService(AuthSession authSession, ApiClientFactory apiClientFactory,
      RetryWithRecoveryBuilder<?> retryBuilder)
      throws HttpClientException, IOException {
    this.authSession = authSession;
    // we assume BFB is running in place of the Agent, right now it is not running under any specific path
    this.bfbClient = apiClientFactory.getAgentBaseClient("");
    this.messageApi = new MessageApi(apiClientFactory.getPodClient());
    this.podApi = new PodApi(apiClientFactory.getPodClient());
    this.streamsApi = new StreamsApi(apiClientFactory.getPodClient());
    this.defaultApi = new DefaultApi(apiClientFactory.getPodClient());
    this.messageSuppressionApi = new MessageSuppressionApi(apiClientFactory.getPodClient());

    // TODO should we access the BDK config rather than creating clients?
    this.scryptoFactory =
        new ScryptoFactory(bfbClient.getBasePath(), apiClientFactory.getKeyManagerClient().getBasePath());
    // TODO how do we handle expiration with scrypto?
    this.scrypto = scryptoFactory.newScrypto(authSession.getSessionToken(), authSession.getKeyManagerToken());
    this.templateEngine = TemplateEngine.getDefaultImplementation();
    this.retryBuilder = RetryWithRecoveryBuilder.copyWithoutRecoveryStrategies(retryBuilder)
        .recoveryStrategy(ApiException::isUnauthorized, authSession::refresh);
  }

  public AgentlessMessageService(AuthSession authSession, ApiClient bfbClient,
      MessageApi messageApi, PodApi podApi, StreamsApi streamsApi, DefaultApi defaultApi,
      MessageSuppressionApi messageSuppressionApi,
      TemplateEngine templateEngine, ScryptoFactory scryptoFactory, RetryWithRecoveryBuilder<?> retryBuilder)
      throws HttpClientException, IOException {
    this.authSession = authSession;
    this.bfbClient = bfbClient;
    this.messageApi = messageApi;
    this.podApi = podApi;
    this.streamsApi = streamsApi;
    this.defaultApi = defaultApi;
    this.messageSuppressionApi = messageSuppressionApi;
    this.templateEngine = templateEngine;
    this.scryptoFactory = scryptoFactory;
    this.scrypto = scryptoFactory.newScrypto(authSession.getSessionToken(), authSession.getKeyManagerToken());
    this.retryBuilder = RetryWithRecoveryBuilder.copyWithoutRecoveryStrategies(retryBuilder)
        .recoveryStrategy(ApiException::isUnauthorized, authSession::refresh);
  }

  @Override
  @SneakyThrows
  public OboMessageService obo(AuthSession oboSession) {
    return new AgentlessMessageService(oboSession, bfbClient, messageApi, podApi, streamsApi, defaultApi,
        messageSuppressionApi,
        templateEngine, scryptoFactory, retryBuilder);
  }

  @Override
  public TemplateEngine templates() {
    return templateEngine;
  }

  @Override
  public List<V4Message> listMessages(@Nonnull V4Stream stream, @Nonnull Instant since,
      @Nonnull PaginationAttribute pagination) {
    return listMessages(stream.getStreamId(), since, pagination);
  }

  @Override
  public List<V4Message> listMessages(@Nonnull V4Stream stream, @Nonnull Instant since) {
    return doListMessages(stream.getStreamId(), since, null, null);
  }

  @Override
  public List<V4Message> listMessages(@Nonnull String streamId, @Nonnull Instant since,
      @Nonnull PaginationAttribute pagination) {
    return doListMessages(streamId, since, pagination.getSkip(), pagination.getLimit());
  }

  @Override
  public List<V4Message> listMessages(@Nonnull String streamId, @Nonnull Instant since) {
    return doListMessages(streamId, since, null, null);
  }

  @SneakyThrows
  private List<V4Message> doListMessages(String streamId, Instant since, Integer skip, Integer limit) {
    List<Pair> queryParams = new ArrayList<>();
    queryParams.add(new Pair("since", getEpochMillis(since)));
    if (skip != null) {
      queryParams.add(new Pair("skip", skip));
    }
    if (limit != null) {
      queryParams.add(new Pair("limit", limit));
    }

    return bfbClient.invokeAPI(
        String.format("/api/v1/streams/%s/messages", streamId),
        "GET",
        queryParams,
        null, // for 'multipart/form-data', body can be null
        Collections.singletonMap("Authorization", "Bearer " + authSession.getSessionToken()),
        emptyMap(),
        null,
        bfbClient.selectHeaderAccept("application/json"),
        bfbClient.selectHeaderContentType("application/x-www-form-urlencoded"),
        new String[0],
        new TypeReference<List<V4Message>>() {}
    ).getData().stream()
        .map(this::decrypt) // could be optimized since it is the same thread (although scrypto is caching)
        .collect(Collectors.toList());
  }

  private static Long getEpochMillis(Instant instant) {
    return instant == null ? null : instant.toEpochMilli();
  }

  @Override
  public V4Message send(@Nonnull V4Stream stream, @Nonnull String message) {
    return send(stream.getStreamId(), message);
  }

  @Override
  @SneakyThrows
  public V4Message send(@Nonnull String streamId, @Nonnull String message) {
    // TODO retry + exception handling

    // TODO how do we provide the required data?
    MessageMLContext messageMl = new MessageMLContext(new NoOpDataProvider());
    messageMl.parseMessageML(message, "", null);

    String encryptedText = Base64.getEncoder()
        .encodeToString(scrypto.encrypt(streamId, messageMl.getText().getBytes(StandardCharsets.UTF_8)));
    String encryptedMessage = Base64.getEncoder()
        .encodeToString(scrypto.encrypt(streamId, messageMl.getPresentationML().getBytes(StandardCharsets.UTF_8)));

    Map<String, Object> form = new HashMap<>();
    form.put("message", encryptedMessage);
    form.put("text", encryptedText);

    // keeping it a multipart request for attachments
    return bfbClient.invokeAPI(
        String.format("/api/v1/streams/%s/messages", streamId),
        "POST",
        emptyList(),
        null, // for 'multipart/form-data', body can be null
        Collections.singletonMap("Authorization", "Bearer " + authSession.getSessionToken()),
        emptyMap(),
        form,
        bfbClient.selectHeaderAccept("application/json"),
        bfbClient.selectHeaderContentType("application/x-www-form-urlencoded"),
        new String[0],
        new TypeReference<V4Message>() {}
    ).getData();
  }

  @Override
  public V4Message send(@Nonnull V4Stream stream, @Nonnull Message message) {
    return send(stream.getStreamId(), message.getContent());
  }

  @Override
  public V4Message send(@Nonnull String streamId, @Nonnull Message message) {
    return send(streamId, message.getContent());
  }

  @Override
  public V4MessageBlastResponse send(@Nonnull List<String> streamIds, @Nonnull Message message) {
    return null; // TODO make it a single endpoint? POST with body?
  }

  @SneakyThrows
  @Override
  public byte[] getAttachment(@Nonnull String streamId, @Nonnull String messageId, @Nonnull String attachmentId) {
    byte[] encryptedAttachment = bfbClient.invokeAPI(
        String.format("/api/v1/streams/%s/messages/%s/attachments/%s", streamId, messageId, attachmentId),
        "GET",
        emptyList(),
        null, // for 'multipart/form-data', body can be null
        Collections.singletonMap("Authorization", "Bearer " + authSession.getSessionToken()),
        emptyMap(),
        null,
        bfbClient.selectHeaderAccept("application/json"),
        bfbClient.selectHeaderContentType("application/octet-stream"),
        new String[0],
        new TypeReference<byte[]>() {}
    ).getData();

    // test if there is an ephemeral key in the message

    // TODO also support decrypting with ephemeral key
    return Base64.getEncoder().encode(scrypto.decrypt(streamId, encryptedAttachment));
  }

  @Override
  public List<V4ImportResponse> importMessages(@Nonnull List<V4ImportedMessage> messages) {
    return null;
  }

  @Override
  public MessageSuppressionResponse suppressMessage(@Nonnull String messageId) {
    return executeAndRetry("suppressMessage", messageSuppressionApi.getApiClient().getBasePath(),
        () -> messageSuppressionApi.v1AdminMessagesuppressionIdSuppressPost(messageId, authSession.getSessionToken()));
  }

  @Override
  public MessageStatus getMessageStatus(@Nonnull String messageId) {
    return executeAndRetry("getMessageStatus", messageApi.getApiClient().getBasePath(),
        () -> messageApi.v1MessageMidStatusGet(messageId, authSession.getSessionToken()));
  }

  @Override
  public List<String> getAttachmentTypes() {
    return executeAndRetry("getAttachmentTypes", podApi.getApiClient().getBasePath(),
        () -> podApi.v1FilesAllowedTypesGet(authSession.getSessionToken()));
  }

  @Override
  @SneakyThrows
  public V4Message getMessage(@Nonnull String messageId) {
    V4Message encryptedMessage = bfbClient.invokeAPI(
        String.format("/api/v1/messages/%s", messageId),
        "GET",
        emptyList(),
        null, // for 'multipart/form-data', body can be null
        Collections.singletonMap("Authorization", "Bearer " + authSession.getSessionToken()),
        emptyMap(),
        null,
        bfbClient.selectHeaderAccept("application/json"),
        bfbClient.selectHeaderContentType("application/json"),
        new String[0],
        new TypeReference<V4Message>() {}
    ).getData();

    // TODO could we decrypt V4Message as part of the deserialization? i.e having a custom deserializer
    // is it worth it?
    return decrypt(encryptedMessage);
  }

  private V4Message decrypt(V4Message message) {
    String streamId = getUrlEncoder().encodeToString(getDecoder().decode(message.getStream().getStreamId()));
    byte[] decrypted = scrypto.decrypt(streamId, getDecoder().decode(message.getMessage()));
    message.setMessage(new String(decrypted));
    return message;
  }

  @Override
  public List<StreamAttachmentItem> listAttachments(@Nonnull String streamId, @Nullable Instant since,
      @Nullable Instant to, @Nullable Integer limit, @Nullable AttachmentSort sort) {
    final String sortDir = sort == null ? AttachmentSort.ASC.name() : sort.name();

    return executeAndRetry("listAttachments", streamsApi.getApiClient().getBasePath(),
        () -> streamsApi.v1StreamsSidAttachmentsGet(streamId, authSession.getSessionToken(), getEpochMillis(since),
            getEpochMillis(to), limit, sortDir));
  }

  @Override
  public MessageReceiptDetailResponse listMessageReceipts(@Nonnull String messageId) {
    return executeAndRetry("listMessageReceipts", defaultApi.getApiClient().getBasePath(), () ->
        defaultApi.v1AdminMessagesMessageIdReceiptsGet(authSession.getSessionToken(), messageId, null, null));
  }

  @Override
  public MessageMetadataResponse getMessageRelationships(@Nonnull String messageId) {
    return executeAndRetry("getMessageRelationships", defaultApi.getApiClient().getBasePath(),
        () -> defaultApi.v1AdminMessagesMessageIdMetadataRelationshipsGet(
            authSession.getSessionToken(), ApiUtils.getUserAgent(), messageId));
  }

  private <T> T executeAndRetry(String name, String address, SupplierWithApiException<T> supplier) {
    checkAuthSession(authSession);
    return RetryWithRecovery.executeAndRetry(retryBuilder, name, address, supplier);
  }

}
