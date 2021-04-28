package com.symphony.bdk.agentless;

import static com.symphony.bdk.core.config.BdkConfigLoader.loadFromSymphonyDir;
import static java.util.Base64.getDecoder;
import static java.util.Base64.getUrlEncoder;

import com.symphony.bdk.core.SymphonyBdk;
import com.symphony.bdk.core.auth.AuthSession;
import com.symphony.bdk.core.client.ApiClientFactory;
import com.symphony.bdk.core.config.model.BdkConfig;
import com.symphony.bdk.core.module.BdkModule;
import com.symphony.bdk.core.retry.RetryWithRecoveryBuilder;
import com.symphony.bdk.core.service.datafeed.EventException;
import com.symphony.bdk.core.service.datafeed.RealTimeEventListener;
import com.symphony.bdk.gen.api.model.V4Initiator;
import com.symphony.bdk.gen.api.model.V4Message;
import com.symphony.bdk.gen.api.model.V4MessageSent;
import com.symphony.bdk.gen.api.model.V5EventList;
import com.symphony.scrypto.lib.Scrypto;
import com.symphony.scrypto.lib.ScryptoFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.symphonyoss.symphony.messageml.MessageMLContext;
import org.symphonyoss.symphony.messageml.util.NoOpDataProvider;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.ClientResponseContext;
import javax.ws.rs.client.ClientResponseFilter;

/**
 * Agentless implemented at the API level, as a proxy
 */
public class AgentlessProxyStyleMain {

  private static final String BFB_URL = "http://localhost:9090";

  public static void main(String[] args) throws Exception {
    BdkConfig config = loadFromSymphonyDir("config.yaml");
    config.setModule(new BdkModule() {
      @SneakyThrows
      @Override
      @SuppressWarnings("unchecked")
      public <T> T getService(AuthSession authSession, ApiClientFactory apiClientFactory,
          RetryWithRecoveryBuilder<?> retryBuilder, Class<T> serviceClass) {
        return null;
      }

      @Override
      public Object getWriterInterceptor() {
        return new MyClientRequestFilter(config);
      }
    });

    final SymphonyBdk bdk = new SymphonyBdk(config);

    bdk.datafeed().subscribe(new RealTimeEventListener() {
      @Override
      public void onMessageSent(V4Initiator initiator, V4MessageSent event) throws EventException {
        System.out.println("received: " + event.getMessage().getMessage());
      }
    });

    bdk.datafeed().start();
  }

  // we could abstract this concept of filters to support webclient too
  private static class MyClientRequestFilter implements ClientRequestFilter, ClientResponseFilter {
    private final BdkConfig config;

    public MyClientRequestFilter(BdkConfig config) {
      this.config = config;
    }

    /*
    What we need in the filters:
     - URL/HTTP method matcher
     - read body / re write body
     - a generic way to decrypt fields?
     - cache scrypto instances
     */
    @Override
    @SneakyThrows
    public void filter(ClientRequestContext requestContext) {

      if (requestContext.getUri().toString().contains("/agent/")) {
        // BFB is using Auth
        String sessionToken = requestContext.getHeaderString("sessionToken");
        requestContext.getHeaders().add("Authorization", "Bearer " + sessionToken);

        if (requestContext.getUri().toString().contains("message/create")) {

          FormDataMultiPart entity = (FormDataMultiPart) requestContext.getEntity();
          String message = (String) entity.getField("message").getEntity();

          MessageMLContext messageMl = new MessageMLContext(new NoOpDataProvider());
          messageMl.parseMessageML(message, "", null);

          String kmToken = requestContext.getHeaderString("keyManagerToken");

          String streamId = extractStreamId(requestContext.getUri().getPath());

          Scrypto scrypto = new ScryptoFactory(BFB_URL, config.getKeyManager().getBasePath())
              .newScrypto(sessionToken, kmToken);
          String encryptedText = Base64.getEncoder()
              .encodeToString(scrypto.encrypt(streamId, messageMl.getText().getBytes(StandardCharsets.UTF_8)));
          String encryptedMessage = Base64.getEncoder()
              .encodeToString(
                  scrypto.encrypt(streamId, messageMl.getPresentationML().getBytes(StandardCharsets.UTF_8)));

          entity.getField("message").setEntity(encryptedMessage);
          entity.bodyPart(new FormDataBodyPart("text", encryptedText));
        }
      }
    }

    @SneakyThrows
    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
      if (requestContext.getUri().toString().contains("v1/message")) {

        String body = IOUtils.toString(responseContext.getEntityStream());
        ObjectMapper mapper = new ObjectMapper();
        V4Message message = mapper.readValue(body, V4Message.class);

        String sessionToken = requestContext.getHeaderString("sessionToken");
        String kmToken = requestContext.getHeaderString("keyManagerToken");
        Scrypto scrypto = new ScryptoFactory(BFB_URL, config.getKeyManager().getBasePath())
            .newScrypto(sessionToken, kmToken);

        String streamId = getUrlEncoder().encodeToString(getDecoder().decode(message.getStream().getStreamId()));
        byte[] decrypted = scrypto.decrypt(streamId, getDecoder().decode(message.getMessage()));
        message.setMessage(new String(decrypted));

        String clearBody = mapper.writeValueAsString(message);
        responseContext.setEntityStream(new ByteArrayInputStream(clearBody.getBytes()));

      }

      if (requestContext.getUri().toString().matches(".*/v5/datafeeds/.*/read")) {
        String sessionToken = requestContext.getHeaderString("sessionToken");
        String kmToken = requestContext.getHeaderString("keyManagerToken");
        Scrypto scrypto = new ScryptoFactory(BFB_URL, config.getKeyManager().getBasePath())
            .newScrypto(sessionToken, kmToken);

        String body = IOUtils.toString(responseContext.getEntityStream());
        ObjectMapper mapper = new ObjectMapper();
        V5EventList events = mapper.readValue(body, V5EventList.class);

        events.getEvents().forEach(e -> {
          V4Message message = e.getPayload().getMessageSent().getMessage();
          String streamId = getUrlEncoder().encodeToString(getDecoder().decode(message.getStream().getStreamId()));
          byte[] decrypted = scrypto.decrypt(streamId, getDecoder().decode(message.getMessage()));
          message.setMessage(new String(decrypted));
        });

        String clearBody = mapper.writeValueAsString(events);
        responseContext.setEntityStream(new ByteArrayInputStream(clearBody.getBytes()));
      }
    }
  }

  private static String extractStreamId(String uri) {
    Pattern pattern = Pattern.compile("/v4/stream/(.*?)/message/create");
    Matcher matcher = pattern.matcher(uri);
    matcher.find();
    return matcher.group(1);
  }
}
