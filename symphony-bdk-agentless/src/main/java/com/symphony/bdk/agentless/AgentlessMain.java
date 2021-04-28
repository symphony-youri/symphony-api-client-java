package com.symphony.bdk.agentless;

import static com.symphony.bdk.core.config.BdkConfigLoader.loadFromSymphonyDir;

import com.symphony.bdk.core.SymphonyBdk;
import com.symphony.bdk.core.auth.AuthSession;
import com.symphony.bdk.core.client.ApiClientFactory;
import com.symphony.bdk.core.config.model.BdkConfig;
import com.symphony.bdk.core.module.BdkModule;
import com.symphony.bdk.core.retry.RetryWithRecoveryBuilder;

import lombok.SneakyThrows;

/**
 * Agentless implemented at the service level.
 */
public class AgentlessMain {

  public static void main(String[] args) throws Exception {
    BdkConfig config = loadFromSymphonyDir("config.yaml");
    config.setModule(new BdkModule() {
      @SneakyThrows
      @Override
      @SuppressWarnings("unchecked")
      public <T> T getService(AuthSession authSession, ApiClientFactory apiClientFactory,
          RetryWithRecoveryBuilder<?> retryBuilder, Class<T> serviceClass) {
         return (T) new AgentlessMessageService(authSession, apiClientFactory, retryBuilder);
      }

      @Override
      public Object getWriterInterceptor() {
        return null;
      }
    });

    final SymphonyBdk bdk = new SymphonyBdk(config);

    String streamId = "v7ZTHzpNvFu2ADUrwIq0AH___or-SZVqdA";

    bdk.messages().send("v7ZTHzpNvFu2ADUrwIq0AH___or-SZVqdA", "<messageML>Hello</messageML>");
//    V4Message message = bdk.messages().getMessage("WjNB6FZjiSVCX0jHBUrTin___obou8lQbQ");
//    System.out.println(message.getMessage());
//    List<V4Message> messages = bdk.messages()
//        .listMessages("v7ZTHzpNvFu2ADUrwIq0AH___or-SZVqdA", Instant.now().minus(30, ChronoUnit.DAYS),
//            new PaginationAttribute(0, 50));
//
//    for (V4Message message : messages) {
//      System.out.println(message.getMessage());
//    }

//
//    String fileId = "internal_13056700579841%2F2pRoOBbClmHpJnr9gUGeCA%3D%3D";
//    byte[] attachment = Base64.getDecoder()
//        .decode(bdk.messages().getAttachment("v7ZTHzpNvFu2ADUrwIq0AH___or-SZVqdA", "1bjaqdwFJMbhMqmpd44mSn___oboPXdRbQ", fileId));
//    System.out.println(new String(attachment));
  }

}
