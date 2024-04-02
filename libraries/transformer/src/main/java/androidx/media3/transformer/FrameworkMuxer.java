/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.SDK_INT;
import static androidx.media3.common.util.Util.castNonNull;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.SparseLongArray;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.Util;
import androidx.media3.container.Mp4LocationData;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/** {@link Muxer} implementation that uses a {@link MediaMuxer}. */
/* package */ final class FrameworkMuxer implements Muxer {
  // MediaMuxer supported sample formats are documented in MediaMuxer.addTrack(MediaFormat).
  private static final ImmutableList<String> SUPPORTED_VIDEO_SAMPLE_MIME_TYPES =
      getSupportedVideoSampleMimeTypes();
  private static final ImmutableList<String> SUPPORTED_AUDIO_SAMPLE_MIME_TYPES =
      ImmutableList.of(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_AMR_NB, MimeTypes.AUDIO_AMR_WB);

  /** {@link Muxer.Factory} for {@link FrameworkMuxer}. */
  public static final class Factory implements Muxer.Factory {

    private final long maxDelayBetweenSamplesMs;
    private final long videoDurationMs;

    public Factory(long maxDelayBetweenSamplesMs, long videoDurationMs) {
      this.maxDelayBetweenSamplesMs = maxDelayBetweenSamplesMs;
      this.videoDurationMs = videoDurationMs;
    }

    @Override
    public FrameworkMuxer create(String path) throws MuxerException {
      MediaMuxer mediaMuxer;
      try {
        mediaMuxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      } catch (IOException e) {
        throw new MuxerException("Error creating muxer", e);
      }
      return new FrameworkMuxer(mediaMuxer, maxDelayBetweenSamplesMs, videoDurationMs);
    }

    @Override
    public ImmutableList<String> getSupportedSampleMimeTypes(@C.TrackType int trackType) {
      if (trackType == C.TRACK_TYPE_VIDEO) {
        return SUPPORTED_VIDEO_SAMPLE_MIME_TYPES;
      } else if (trackType == C.TRACK_TYPE_AUDIO) {
        return SUPPORTED_AUDIO_SAMPLE_MIME_TYPES;
      }
      return ImmutableList.of();
    }
  }

  private final MediaMuxer mediaMuxer;
  private final long maxDelayBetweenSamplesMs;
  private final long videoDurationUs;
  private final MediaCodec.BufferInfo bufferInfo;
  private final SparseLongArray trackIndexToLastPresentationTimeUs;
  private final SparseLongArray trackIndexToPresentationTimeOffsetUs;

  private int videoTrackIndex;

  private boolean isStarted;

  private FrameworkMuxer(
      MediaMuxer mediaMuxer, long maxDelayBetweenSamplesMs, long videoDurationMs) {
    this.mediaMuxer = mediaMuxer;
    this.maxDelayBetweenSamplesMs = maxDelayBetweenSamplesMs;
    this.videoDurationUs = Util.msToUs(videoDurationMs);
    bufferInfo = new MediaCodec.BufferInfo();
    trackIndexToLastPresentationTimeUs = new SparseLongArray();
    trackIndexToPresentationTimeOffsetUs = new SparseLongArray();
    videoTrackIndex = C.INDEX_UNSET;
  }

  @Override
  public int addTrack(Format format) throws MuxerException {
    String sampleMimeType = checkNotNull(format.sampleMimeType);
    MediaFormat mediaFormat;
    boolean isVideo = MimeTypes.isVideo(sampleMimeType);
    if (isVideo) {
      mediaFormat = MediaFormat.createVideoFormat(sampleMimeType, format.width, format.height);
      MediaFormatUtil.maybeSetColorInfo(mediaFormat, format.colorInfo);
      if (sampleMimeType.equals(MimeTypes.VIDEO_DOLBY_VISION) &&
          android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        mediaFormat.setInteger(MediaFormat.KEY_PROFILE, getDvProfile(format));
        mediaFormat.setInteger(MediaFormat.KEY_LEVEL, getDvLevel(format));
      }
      try {
        mediaMuxer.setOrientationHint(format.rotationDegrees);
      } catch (RuntimeException e) {
        throw new MuxerException(
            "Failed to set orientation hint with rotationDegrees=" + format.rotationDegrees, e);
      }
    } else {
      mediaFormat =
          MediaFormat.createAudioFormat(sampleMimeType, format.sampleRate, format.channelCount);
      MediaFormatUtil.maybeSetString(mediaFormat, MediaFormat.KEY_LANGUAGE, format.language);
    }
    MediaFormatUtil.setCsdBuffers(mediaFormat, format.initializationData);
    int trackIndex;
    try {
      trackIndex = mediaMuxer.addTrack(mediaFormat);
    } catch (RuntimeException e) {
      throw new MuxerException("Failed to add track with format=" + format, e);
    }

    if (isVideo) {
      videoTrackIndex = trackIndex;
    }

    return trackIndex;
  }

  @Override
  public void writeSampleData(
      int trackIndex, ByteBuffer data, long presentationTimeUs, @C.BufferFlags int flags)
      throws MuxerException {

    if (videoDurationUs != C.TIME_UNSET
        && trackIndex == videoTrackIndex
        && presentationTimeUs > videoDurationUs) {
      return;
    }

    if (!isStarted) {
      isStarted = true;
      if (Util.SDK_INT < 30 && presentationTimeUs < 0) {
        trackIndexToPresentationTimeOffsetUs.put(trackIndex, -presentationTimeUs);
      }
      try {
        mediaMuxer.start();
      } catch (RuntimeException e) {
        throw new MuxerException("Failed to start the muxer", e);
      }
    }

    int offset = data.position();
    int size = data.limit() - offset;

    long presentationTimeOffsetUs = trackIndexToPresentationTimeOffsetUs.get(trackIndex);
    presentationTimeUs += presentationTimeOffsetUs;

    bufferInfo.set(offset, size, presentationTimeUs, TransformerUtil.getMediaCodecFlags(flags));
    long lastSamplePresentationTimeUs = trackIndexToLastPresentationTimeUs.get(trackIndex);
    // writeSampleData blocks on old API versions, so check here to avoid calling the method.
    checkState(
        Util.SDK_INT > 24 || presentationTimeUs >= lastSamplePresentationTimeUs,
        "Samples not in presentation order ("
            + presentationTimeUs
            + " < "
            + lastSamplePresentationTimeUs
            + ") unsupported on this API version");
    trackIndexToLastPresentationTimeUs.put(trackIndex, presentationTimeUs);

    checkState(
        presentationTimeOffsetUs == 0 || presentationTimeUs >= lastSamplePresentationTimeUs,
        "Samples not in presentation order ("
            + presentationTimeUs
            + " < "
            + lastSamplePresentationTimeUs
            + ") unsupported when using negative PTS workaround");

    try {
      mediaMuxer.writeSampleData(trackIndex, data, bufferInfo);
    } catch (RuntimeException e) {
      throw new MuxerException(
          "Failed to write sample for trackIndex="
              + trackIndex
              + ", presentationTimeUs="
              + presentationTimeUs
              + ", size="
              + size,
          e);
    }
  }

  @Override
  public void addMetadata(Metadata metadata) {
    for (int i = 0; i < metadata.length(); i++) {
      Metadata.Entry entry = metadata.get(i);
      if (entry instanceof Mp4LocationData) {
        mediaMuxer.setLocation(
            ((Mp4LocationData) entry).latitude, ((Mp4LocationData) entry).longitude);
      }
    }
  }

  @Override
  public void release(boolean forCancellation) throws MuxerException {
    if (!isStarted) {
      mediaMuxer.release();
      return;
    }

    if (videoDurationUs != C.TIME_UNSET && videoTrackIndex != C.INDEX_UNSET) {
      writeSampleData(
          videoTrackIndex,
          ByteBuffer.allocateDirect(0),
          videoDurationUs,
          C.BUFFER_FLAG_END_OF_STREAM);
    }

    isStarted = false;
    try {
      stopMuxer(mediaMuxer);
    } catch (RuntimeException e) {
      // It doesn't matter that stopping the muxer throws if the export is being cancelled.
      if (!forCancellation) {
        throw new MuxerException("Failed to stop the muxer", e);
      }
    } finally {
      mediaMuxer.release();
    }
  }

  @Override
  public long getMaxDelayBetweenSamplesMs() {
    return maxDelayBetweenSamplesMs;
  }

  // Accesses MediaMuxer state via reflection to ensure that muxer resources can be released even
  // if stopping fails.
  @SuppressLint("PrivateApi")
  private static void stopMuxer(MediaMuxer mediaMuxer) {
    try {
      mediaMuxer.stop();
    } catch (RuntimeException e) {
      if (SDK_INT < 30) {
        // Set the muxer state to stopped even if mediaMuxer.stop() failed so that
        // mediaMuxer.release() doesn't attempt to stop the muxer and therefore doesn't throw the
        // same exception without releasing its resources. This is already implemented in MediaMuxer
        // from API level 30. See also b/80338884.
        try {
          Field muxerStoppedStateField = MediaMuxer.class.getDeclaredField("MUXER_STATE_STOPPED");
          muxerStoppedStateField.setAccessible(true);
          int muxerStoppedState = castNonNull((Integer) muxerStoppedStateField.get(mediaMuxer));
          Field muxerStateField = MediaMuxer.class.getDeclaredField("mState");
          muxerStateField.setAccessible(true);
          muxerStateField.set(mediaMuxer, muxerStoppedState);
        } catch (Exception reflectionException) {
          // Do nothing.
        }
      }
      // Rethrow the original error.
      throw e;
    }
  }

  private static ImmutableList<String> getSupportedVideoSampleMimeTypes() {
    ImmutableList.Builder<String> supportedMimeTypes =
        new ImmutableList.Builder<String>()
            .add(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H263, MimeTypes.VIDEO_MP4V);
    if (SDK_INT >= 24) {
      supportedMimeTypes.add(MimeTypes.VIDEO_H265);
    }
    if (SDK_INT >= 33) {
      supportedMimeTypes.add(MimeTypes.VIDEO_DOLBY_VISION);
    }
    if (SDK_INT >= 34) {
      supportedMimeTypes.add(MimeTypes.VIDEO_AV1);
    }
    return supportedMimeTypes.build();
  }

  // Get Dolby Vision profile
  // Refer to https://professionalsupport.dolby.com/s/article/What-is-Dolby-Vision-Profile
  @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
  private static int getDvProfile(Format format) {
    // Currently, only profile 8 is supported for encoding
    // TODO: set profile ID based on format.
    return MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt;
  }

  // Get Dolby Vision level
  // Refer to https://professionalsupport.dolby.com/s/article/What-is-Dolby-Vision-Profile
  @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
  private static int getDvLevel(Format format) {
    int level = -1;
    int maxWidthHeight = Math.max(format.width, format.height);
    float pps = format.width * format.height * format.frameRate;

    if (maxWidthHeight <= 1280) {
      if (pps <= 22118400) {
        level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd24;  // Level 01
      } else if (pps <= 27648000) {
        level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd30;  // Level 02
      }
    } else if (maxWidthHeight <= 1920 && pps <= 49766400) {
      level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd24;  // Level 03
    } else if (maxWidthHeight <= 2560 && pps <= 62208000) {
      level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd30;  // Level 04
    } else if (maxWidthHeight <= 3840) {
      if (pps <= 124416000) {
        level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd60;  // Level 05
      } else if (pps <= 199065600) {
        level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd24;  // Level 06
      } else if (pps <= 248832000) {
        level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30;  // Level 07
      } else if (pps <= 398131200) {
        level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd48;  // Level 08
      } else if (pps <= 497664000) {
        level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60;  // Level 09
      } else if (pps <= 995328000) {
        level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd120;  // Level 10
      }
    } else if (maxWidthHeight <= 7680) {
      if (pps <= 995328000) {
        level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k30;  // Level 11
      } else if (pps <= 1990656000) {
        level = MediaCodecInfo.CodecProfileLevel.DolbyVisionLevel8k60;  // Level 12
      }
    }

    return level;
  }
}
