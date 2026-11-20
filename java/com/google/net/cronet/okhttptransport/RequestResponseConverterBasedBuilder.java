/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.net.cronet.okhttptransport;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import org.chromium.net.CronetEngine;

abstract class RequestResponseConverterBasedBuilder<
    SubBuilderT extends RequestResponseConverterBasedBuilder<?, ? extends ObjectBeingBuiltT>,
    ObjectBeingBuiltT> {
  private final CronetEngine cronetEngine;
  // Not setting the default straight away to lazy initialize the object if it ends up not being
  // used.
  private RedirectStrategy redirectStrategy = null;
  private final SubBuilderT castedThis;

  @SuppressWarnings("unchecked") // checked as a precondition
  RequestResponseConverterBasedBuilder(CronetEngine cronetEngine, Class<SubBuilderT> clazz) {
    this.cronetEngine = checkNotNull(cronetEngine);
    checkArgument(this.getClass().equals(clazz));
    castedThis = (SubBuilderT) this;
  }

  /**
   * Sets the size of upload data provider executor.
   *
   * @deprecated This method is now a no-op; upload data providers are non-blocking and use a direct
   *     executor instead of a dedicated thread pool.
   */
  @Deprecated
  public final SubBuilderT setUploadDataProviderExecutorSize(int size) {
    checkArgument(size > 0, "The number of threads must be positive!");
    return castedThis;
  }

  /**
   * Sets the strategy for following redirects.
   *
   * <p>Note that the Cronet (i.e. Chromium) wide safeguards will still apply if one attempts to
   * follow redirects too many times.
   */
  public final SubBuilderT setRedirectStrategy(RedirectStrategy redirectStrategy) {
    checkNotNull(redirectStrategy);
    this.redirectStrategy = redirectStrategy;
    return castedThis;
  }

  abstract ObjectBeingBuiltT build(RequestResponseConverter converter);

  public final ObjectBeingBuiltT build() {
    if (redirectStrategy == null) {
      redirectStrategy = RedirectStrategy.defaultStrategy();
    }

    RequestResponseConverter converter =
        new RequestResponseConverter(cronetEngine, new ResponseConverter(), redirectStrategy);

    return build(converter);
  }
}
