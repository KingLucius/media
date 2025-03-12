/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.exoplayer.video;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoGraph;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Unit test for {@link PlaybackVideoGraphWrapper}. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackVideoGraphWrapperTest {
  @Test
  public void builder_calledMultipleTimes_throws() {
    Context context = ApplicationProvider.getApplicationContext();
    PlaybackVideoGraphWrapper.Builder builder =
        new PlaybackVideoGraphWrapper.Builder(context, createVideoFrameReleaseControl());

    builder.build();

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void initializeSink_calledTwice_throws() throws VideoSink.VideoSinkException {
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(new TestVideoGraphFactory());
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);
    sink.initialize(new Format.Builder().build());

    assertThrows(IllegalStateException.class, () -> sink.initialize(new Format.Builder().build()));
  }

  @Test
  public void onInputStreamChanged_setsVideoSinkVideoEffects() throws VideoSink.VideoSinkException {
    ImmutableList<Effect> firstEffects = ImmutableList.of(Mockito.mock(Effect.class));
    ImmutableList<Effect> secondEffects =
        ImmutableList.of(Mockito.mock(Effect.class), Mockito.mock(Effect.class));
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    Format format = new Format.Builder().build();
    long startPositionUs = 0;
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    sink.initialize(format);

    sink.onInputStreamChanged(VideoSink.INPUT_TYPE_SURFACE, format, startPositionUs, firstEffects);
    sink.onInputStreamChanged(VideoSink.INPUT_TYPE_SURFACE, format, startPositionUs, secondEffects);
    sink.onInputStreamChanged(
        VideoSink.INPUT_TYPE_SURFACE, format, startPositionUs, ImmutableList.of());
    testVideoGraphFactory.verifyRegisteredEffectsMatches(/* invocationTimes= */ 3);
    assertThat(testVideoGraphFactory.getCapturedEffects())
        .isEqualTo(ImmutableList.of(firstEffects, secondEffects, ImmutableList.of()));
  }

  private static PlaybackVideoGraphWrapper createPlaybackVideoGraphWrapper(
      VideoGraph.Factory videoGraphFactory) {
    Context context = ApplicationProvider.getApplicationContext();
    return new PlaybackVideoGraphWrapper.Builder(context, createVideoFrameReleaseControl())
        .setVideoGraphFactory(videoGraphFactory)
        .build();
  }

  private static VideoFrameReleaseControl createVideoFrameReleaseControl() {
    Context context = ApplicationProvider.getApplicationContext();
    VideoFrameReleaseControl.FrameTimingEvaluator frameTimingEvaluator =
        new VideoFrameReleaseControl.FrameTimingEvaluator() {
          @Override
          public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
            return false;
          }

          @Override
          public boolean shouldDropFrame(
              long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
            return false;
          }

          @Override
          public boolean shouldIgnoreFrame(
              long earlyUs,
              long positionUs,
              long elapsedRealtimeUs,
              boolean isLastFrame,
              boolean treatDroppedBuffersAsSkipped) {
            return false;
          }
        };
    return new VideoFrameReleaseControl(
        context, frameTimingEvaluator, /* allowedJoiningTimeMs= */ 0);
  }

  private static class TestVideoGraphFactory implements VideoGraph.Factory {
    // Using a mock but we don't assert mock interactions. If needed to assert interactions, we
    // should a fake instead.
    private final VideoGraph videoGraph = Mockito.mock(VideoGraph.class);

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<List<Effect>> effectsCaptor = ArgumentCaptor.forClass(List.class);

    @Override
    public VideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        VideoGraph.Listener listener,
        Executor listenerExecutor,
        VideoCompositorSettings videoCompositorSettings,
        List<Effect> compositionEffects,
        long initialTimestampOffsetUs,
        boolean renderFramesAutomatically) {

      when(videoGraph.registerInputFrame(anyInt())).thenReturn(true);
      return videoGraph;
    }

    @Override
    public boolean supportsMultipleInputs() {
      return false;
    }

    public void verifyRegisteredEffectsMatches(int invocationTimes) {
      verify(videoGraph, times(invocationTimes))
          .registerInputStream(
              /* inputIndex= */ anyInt(),
              /* inputType= */ eq(VideoSink.INPUT_TYPE_SURFACE),
              /* format= */ any(),
              effectsCaptor.capture(),
              /* offsetToAddUs= */ anyLong());
    }

    public List<List<Effect>> getCapturedEffects() {
      return effectsCaptor.getAllValues();
    }
  }
}
