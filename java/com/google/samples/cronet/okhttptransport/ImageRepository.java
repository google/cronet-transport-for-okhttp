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

package com.google.samples.cronet.okhttptransport;

import com.google.common.collect.ImmutableList;

class ImageRepository {

  private static final ImmutableList<String> IMAGE_URLS =
      ImmutableList.of(
          "https://storage.googleapis.com/cronet/sun.jpg",
          "https://storage.googleapis.com/cronet/flower.jpg",
          "https://storage.googleapis.com/cronet/chair.jpg",
          "https://storage.googleapis.com/cronet/white.jpg",
          "https://storage.googleapis.com/cronet/moka.jpg",
          "https://storage.googleapis.com/cronet/walnut.jpg");

  public static int numberOfImages() {
    return IMAGE_URLS.size();
  }

  public static String getImage(int position) {
    return IMAGE_URLS.get(position);
  }

  private ImageRepository() {}
}
