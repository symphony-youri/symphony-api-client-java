package com.symphony.bdk.core.auth.exception;

import com.symphony.bdk.core.api.invoker.ApiException;

import org.apiguardian.api.API;

import javax.annotation.Nonnull;

/**
 * When thrown, it means that authentication cannot be performed for several raisons depending on the context :
 * <ul>
 *   <li>Regular Bot authentication</li>
 *   <li>OBO authentication</li>
 * </ul>
 */
@API(status = API.Status.STABLE)
public class AuthUnauthorizedException extends Exception {

  public AuthUnauthorizedException(@Nonnull String message, @Nonnull ApiException source) {
    super(message, source);
  }
}
