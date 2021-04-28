package com.symphony.bdk.core.module;

import com.symphony.bdk.core.auth.AuthSession;
import com.symphony.bdk.core.client.ApiClientFactory;
import com.symphony.bdk.core.retry.RetryWithRecoveryBuilder;

public interface BdkModule {

  // TODO how do we make it extendable without breaking compat, i.e introduce parameter objects or an init method
  // to access config and factories or interact with the builder
  <T> T getService(AuthSession authSession, ApiClientFactory apiClientFactory, RetryWithRecoveryBuilder<?> retryBuilder,
      Class<T> messageServiceClass);

    Object getWriterInterceptor();
}
