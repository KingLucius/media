/*
 * Copyright (C) 2023 The Android Open Source Project
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

package androidx.media3.exoplayer.hls.e2etest;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.test.utils.CapturingRenderersFactory;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.ThrowingSubtitleParserFactory;
import androidx.media3.test.utils.robolectric.PlaybackOutput;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** End-to-end tests using HLS samples. */
@RunWith(AndroidJUnit4.class)
public final class HlsPlaybackTest {

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.forAllSupportedMimeTypes();

  @Test
  public void webvttStandaloneSubtitlesFile() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);

    player.setMediaItem(
        MediaItem.fromUri("asset:///media/hls/standalone-webvtt/multivariant_playlist.m3u8"));
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();
    surface.release();

    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/hls/standalone-webvtt.dump");
  }

  @Test
  public void webvttStandaloneSubtitles_loadError_playbackContinuesErrorReported()
      throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    ResolvingDataSource.Factory webvttNotFoundDataSourceFactory =
        new ResolvingDataSource.Factory(
            new DefaultDataSource.Factory(applicationContext),
            dataSpec ->
                dataSpec.uri.getPath().endsWith(".vtt")
                    ? dataSpec.buildUpon().setUri("asset:///file/not/found").build()
                    : dataSpec);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(webvttNotFoundDataSourceFactory))
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    AnalyticsListenerImpl analyticsListener = new AnalyticsListenerImpl();
    player.addAnalyticsListener(analyticsListener);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);

    player.setMediaItem(
        MediaItem.fromUri("asset:///media/hls/standalone-webvtt/multivariant_playlist.m3u8"));
    player.prepare();
    player.play();
    run(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    assertThat(analyticsListener.loadErrorEventInfo.uri)
        .isEqualTo(Uri.parse("asset:///file/not/found"));
    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/hls/standalone-webvtt_load-error.dump");
  }

  @Test
  public void webvttStandaloneSubtitles_parseError_playbackContinuesErrorReported()
      throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(applicationContext)
                    .setSubtitleParserFactory(
                        new ThrowingSubtitleParserFactory(
                            () -> new IllegalStateException("test subtitle parsing error"))))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    AnalyticsListenerImpl analyticsListener = new AnalyticsListenerImpl();
    player.addAnalyticsListener(analyticsListener);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);

    player.setMediaItem(
        MediaItem.fromUri("asset:///media/hls/standalone-webvtt/multivariant_playlist.m3u8"));
    player.prepare();
    player.play();
    run(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    assertThat(analyticsListener.loadError)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("test subtitle parsing error");
    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/hls/standalone-webvtt_parse-error.dump");
  }

  @Test
  public void ttmlInMp4() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);

    player.setMediaItem(
        MediaItem.fromUri("asset:///media/hls/ttml-in-mp4/multivariant_playlist.m3u8"));
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();
    surface.release();

    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/hls/ttml-in-mp4.dump");
  }

  @Test
  public void ttmlInMp4_loadError_playbackContinuesErrorReported() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    ResolvingDataSource.Factory ttmlNotFoundDataSourceFactory =
        new ResolvingDataSource.Factory(
            new DefaultDataSource.Factory(applicationContext),
            dataSpec ->
                dataSpec.uri.getPath().endsWith(".text.mp4")
                    ? dataSpec.buildUpon().setUri("asset:///file/not/found").build()
                    : dataSpec);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .setMediaSourceFactory(new DefaultMediaSourceFactory(ttmlNotFoundDataSourceFactory))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    AnalyticsListenerImpl analyticsListener = new AnalyticsListenerImpl();
    player.addAnalyticsListener(analyticsListener);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);

    player.setMediaItem(
        MediaItem.fromUri("asset:///media/hls/ttml-in-mp4/multivariant_playlist.m3u8"));
    player.prepare();
    player.play();
    run(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    assertThat(analyticsListener.loadErrorEventInfo.uri)
        .isEqualTo(Uri.parse("asset:///file/not/found"));
    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/hls/ttml-in-mp4_load-error.dump");
  }

  @Test
  public void ttmlInMp4_parseError_playbackContinuesErrorReported() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(applicationContext)
                    .setSubtitleParserFactory(
                        new ThrowingSubtitleParserFactory(
                            () -> new IllegalStateException("test subtitle parsing error"))))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    AnalyticsListenerImpl analyticsListener = new AnalyticsListenerImpl();
    player.addAnalyticsListener(analyticsListener);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);

    player.setMediaItem(
        MediaItem.fromUri("asset:///media/hls/ttml-in-mp4/multivariant_playlist.m3u8"));
    player.prepare();
    player.play();
    run(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    assertThat(analyticsListener.loadError)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("test subtitle parsing error");
    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/hls/ttml-in-mp4_parse-error.dump");
  }

  /**
   * This test and {@link #cea608_parseDuringExtraction()} use the same output dump file, to
   * demonstrate the flag has no effect on the resulting subtitles.
   */
  // Using deprecated MediaSource.Factory.experimentalParseSubtitlesDuringExtraction() method to
  // ensure legacy subtitle handling keeps working.
  @SuppressWarnings("deprecation")
  @Test
  public void cea608_parseDuringRendering() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setMediaSourceFactory(
                new HlsMediaSource.Factory(new DefaultDataSource.Factory(applicationContext))
                    .experimentalParseSubtitlesDuringExtraction(false))
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);

    player.setMediaItem(MediaItem.fromUri("asset:///media/hls/cea608/manifest.m3u8"));
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();
    surface.release();

    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/hls/cea608.dump");
  }

  /**
   * This test and {@link #cea608_parseDuringRendering()} use the same output dump file, to
   * demonstrate the flag has no effect on the resulting subtitles.
   */
  // Explicitly enable parsing during extraction (even though a) it's the default and b) currently
  // all CEA-608 parsing happens during rendering) to make this test clearer & more future-proof.
  @SuppressWarnings("deprecation")
  @Test
  public void cea608_parseDuringExtraction() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setMediaSourceFactory(
                new HlsMediaSource.Factory(new DefaultDataSource.Factory(applicationContext))
                    .experimentalParseSubtitlesDuringExtraction(true))
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);

    player.setMediaItem(MediaItem.fromUri("asset:///media/hls/cea608/manifest.m3u8"));
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();
    surface.release();

    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/hls/cea608.dump");
  }

  @Test
  public void multiSegment_withSeekToPrevSyncFrame_startsRenderingAtBeginningOfSegment()
      throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .setLoadControl(
                new DefaultLoadControl.Builder()
                    .setBackBuffer(
                        /* backBufferDurationMs= */ 10000, /* retainBackBufferFromKeyframe= */ true)
                    .build())
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    // Play media fully (with back buffer) to ensure we have all the segment data available.
    player.setMediaItem(MediaItem.fromUri("asset:///media/hls/multi-segment/playlist.m3u8"));
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);

    // Seek to beginning of second segment (at 500ms according to playlist)
    player.setSeekParameters(SeekParameters.PREVIOUS_SYNC);
    player.seekTo(600);
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();
    surface.release();

    // Output only starts at 550ms (the first sample in the second segment)
    DumpFileAsserts.assertOutput(
        applicationContext, playbackOutput, "playbackdumps/hls/multi-segment-with-seek.dump");
  }

  @Test
  public void cmcdEnabled_withInitSegment() throws Exception {
    Context applicationContext = ApplicationProvider.getApplicationContext();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(applicationContext);
    ExoPlayer player =
        new ExoPlayer.Builder(applicationContext, capturingRenderersFactory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(applicationContext)
                    .setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT))
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);

    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    player.setMediaItem(MediaItem.fromUri("asset:///media/hls/multi-segment/playlist.m3u8"));
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();
    surface.release();

    DumpFileAsserts.assertOutput(
        applicationContext,
        playbackOutput,
        "playbackdumps/hls/cmcd-enabled-with-init-segment.dump");
  }

  private static class AnalyticsListenerImpl implements AnalyticsListener {

    @Nullable private LoadEventInfo loadErrorEventInfo;
    @Nullable private IOException loadError;

    @Override
    public void onLoadError(
        EventTime eventTime,
        LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData,
        IOException error,
        boolean wasCanceled) {
      this.loadErrorEventInfo = loadEventInfo;
      this.loadError = error;
    }
  }
}
