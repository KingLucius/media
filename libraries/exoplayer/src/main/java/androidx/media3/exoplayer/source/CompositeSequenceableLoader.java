/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.source;

import static java.lang.Math.min;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.LoadingInfo;

/** A {@link SequenceableLoader} that encapsulates multiple other {@link SequenceableLoader}s. */
@UnstableApi
public final class CompositeSequenceableLoader implements SequenceableLoader {

  private final SequenceableLoader[] loaders;

  public CompositeSequenceableLoader(SequenceableLoader[] loaders) {
    this.loaders = loaders;
  }

  @Override
  public long getBufferedPositionUs() {
    long bufferedPositionUs = Long.MAX_VALUE;
    for (SequenceableLoader loader : loaders) {
      long loaderBufferedPositionUs = loader.getBufferedPositionUs();
      if (loaderBufferedPositionUs != C.TIME_END_OF_SOURCE) {
        bufferedPositionUs = min(bufferedPositionUs, loaderBufferedPositionUs);
      }
    }
    return bufferedPositionUs == Long.MAX_VALUE ? C.TIME_END_OF_SOURCE : bufferedPositionUs;
  }

  @Override
  public long getNextLoadPositionUs() {
    long nextLoadPositionUs = Long.MAX_VALUE;
    for (SequenceableLoader loader : loaders) {
      long loaderNextLoadPositionUs = loader.getNextLoadPositionUs();
      if (loaderNextLoadPositionUs != C.TIME_END_OF_SOURCE) {
        nextLoadPositionUs = min(nextLoadPositionUs, loaderNextLoadPositionUs);
      }
    }
    return nextLoadPositionUs == Long.MAX_VALUE ? C.TIME_END_OF_SOURCE : nextLoadPositionUs;
  }

  @Override
  public void reevaluateBuffer(long positionUs) {
    for (SequenceableLoader loader : loaders) {
      loader.reevaluateBuffer(positionUs);
    }
  }

  @Override
  public boolean continueLoading(LoadingInfo loadingInfo) {
    boolean madeProgress = false;
    boolean madeProgressThisIteration;
    do {
      madeProgressThisIteration = false;
      long nextLoadPositionUs = getNextLoadPositionUs();
      if (nextLoadPositionUs == C.TIME_END_OF_SOURCE) {
        break;
      }
      for (SequenceableLoader loader : loaders) {
        long loaderNextLoadPositionUs = loader.getNextLoadPositionUs();
        boolean isLoaderBehind =
            loaderNextLoadPositionUs != C.TIME_END_OF_SOURCE
                && loaderNextLoadPositionUs <= loadingInfo.playbackPositionUs;
        if (loaderNextLoadPositionUs == nextLoadPositionUs || isLoaderBehind) {
          madeProgressThisIteration |= loader.continueLoading(loadingInfo);
        }
      }
      madeProgress |= madeProgressThisIteration;
    } while (madeProgressThisIteration);
    return madeProgress;
  }

  @Override
  public boolean isLoading() {
    for (SequenceableLoader loader : loaders) {
      if (loader.isLoading()) {
        return true;
      }
    }
    return false;
  }
}
