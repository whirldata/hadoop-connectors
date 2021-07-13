/*
 * Copyright 2021 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.util.authentication;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpExecuteInterceptor;
import com.google.api.client.http.HttpRequest;
import com.google.cloud.hadoop.util.CredentialFactory;
import java.io.IOException;

public class AuthenticationInterceptor implements HttpExecuteInterceptor {

  private final Credential defaultCredential;
  private final AccessTokenProvider accessTokenProvider;
  private final boolean useNewCredentialsOnIntercept;

  public AuthenticationInterceptor(Credential defaultCredential, AccessTokenProvider accessTokenProvider, boolean useNewCredentialsOnIntercept) {
    this.defaultCredential = defaultCredential;
    this.accessTokenProvider = accessTokenProvider;
    this.useNewCredentialsOnIntercept = useNewCredentialsOnIntercept;
  }

  public AuthenticationInterceptor(Credential defaultCredential) {
    this(defaultCredential, null, false);
  }

  public Credential getCredential() {
    if (useNewCredentialsOnIntercept && accessTokenProvider != null) {
      return CredentialFromAccessTokenProviderClassFactory.credential(
          this.accessTokenProvider, CredentialFactory.DEFAULT_SCOPES, /* HttpRequest */null);
    }

    return this.defaultCredential;
  }

  @Override
  public void intercept(HttpRequest httpRequest) throws IOException {
    Credential credential = getCredential();
    if (credential != null) {
      credential.intercept(httpRequest);
    }
  }
}
