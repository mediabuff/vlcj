/*
 * This file is part of VLCJ.
 *
 * VLCJ is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VLCJ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VLCJ.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Copyright 2009, 2010, 2011 Caprica Software Limited.
 */

package uk.co.caprica.vlcj.player;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

import uk.co.caprica.vlcj.binding.LibVlc;
import uk.co.caprica.vlcj.binding.internal.libvlc_callback_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_event_e;
import uk.co.caprica.vlcj.binding.internal.libvlc_event_manager_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_event_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_instance_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_logo_position_e;
import uk.co.caprica.vlcj.binding.internal.libvlc_media_list_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_media_player_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_media_stats_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_media_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_media_track_info_audio_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_media_track_info_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_media_track_info_video_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_navigate_mode_e;
import uk.co.caprica.vlcj.binding.internal.libvlc_state_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_track_description_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_video_adjust_option_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_video_logo_option_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_video_marquee_option_t;
import uk.co.caprica.vlcj.log.Logger;
import uk.co.caprica.vlcj.player.events.MediaPlayerEvent;
import uk.co.caprica.vlcj.player.events.MediaPlayerEventFactory;
import uk.co.caprica.vlcj.player.events.MediaPlayerEventType;
import uk.co.caprica.vlcj.player.events.VideoOutputEventListener;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Media player implementation.
 */
public abstract class DefaultMediaPlayer extends AbstractMediaPlayer implements MediaPlayer {

  /**
   * Amount of time to wait when looping for video output.
   * <p>
   * This is a reasonable default, but it can be overridden. 
   */
  private static final int DEFAULT_VIDEO_OUTPUT_WAIT_PERIOD = 50;
  
  /**
   * Maximum amount of time to wait when checking for video output.
   * <p>
   * This is a reasonable default, but it can be overridden. 
   */
  private static final int DEFAULT_VIDEO_OUTPUT_TIMEOUT = 5000;
  
  /**
   * Collection of media player event listeners.
   */
  private final List<MediaPlayerEventListener> eventListenerList = new ArrayList<MediaPlayerEventListener>();

  /**
   * Collection of video output event listeners.
   */
  private final List<VideoOutputEventListener> videoOutputEventListenerList = new ArrayList<VideoOutputEventListener>();
  
  /**
   * Factory to create media player events from native events.
   */
  private final MediaPlayerEventFactory eventFactory = new MediaPlayerEventFactory(this);
  
  /**
   * Background thread to event notifications. 
   */
  private final ExecutorService listenersService = Executors.newSingleThreadExecutor();

  /**
   * Background thread to handle video output notifications.
   */
  private final ExecutorService videoOutputService = Executors.newSingleThreadExecutor();
  
  /**
   * Native media player instance.
   */
  private libvlc_media_player_t mediaPlayerInstance;
  
  /**
   * Native media player event manager.
   */
  private libvlc_event_manager_t mediaPlayerEventManager;
  
  /**
   * Call-back to handle native media player events.
   */
  private libvlc_callback_t callback;

  /**
   * Native media instance for current media (if there is one).
   */
  private libvlc_media_t mediaInstance;
  
  /**
   * Mask of the native events that will cause notifications to be sent to
   * listeners.
   */
  private int eventMask = MediaPlayerEventType.ALL.value();
  
  /**
   * Standard options to be applied to all played media.
   */
  private String[] standardMediaOptions;
  
  /**
   * 
   */
  // FIXME use a Java structure (encapsulate this in an event listener?)
  private libvlc_media_stats_t libvlcMediaStats;

  /**
   * Flag whether or not to automatically replay media after the media has
   * finished playing.
   */
  private boolean repeat;
  
  /**
   * Flag whether or not to automatically play media sub-items if there are 
   * any.
   */
  private boolean playSubItems;

  /**
   * Index of the current sub-item, or -1.
   */
  private int subItemIndex;
  
  /**
   * Opaque reference to user/application-specific data associated with this 
   * media player.
   */
  private Object userData;
  
  /**
   * Rate at which to check the native media player to determine if video 
   * output is available yet or not.
   */
  private int videoOutputWaitPeriod = DEFAULT_VIDEO_OUTPUT_WAIT_PERIOD;
  
  /**
   * Maximum amount of time to wait when checking for video output availability.
   */
  private int videoOutputTimeout = DEFAULT_VIDEO_OUTPUT_TIMEOUT;
  
  /**
   * Set to true when the player has been released.
   */
  private AtomicBoolean released = new AtomicBoolean();
  
  /**
   * Create a new media player.
   * 
   * @param libvlc native library interface
   * @param instance libvlc instance
   */
  public DefaultMediaPlayer(LibVlc libvlc, libvlc_instance_t instance) {
    super(libvlc, instance);
    Logger.debug("DefaultMediaPlayer(libvlc={}, instance={})", libvlc, instance);
    createInstance();
  }
  
//  @Override
  public void addMediaPlayerEventListener(MediaPlayerEventListener listener) {
    Logger.debug("addMediaPlayerEventListener(listener={})", listener);
    eventListenerList.add(listener);
  }

//  @Override
  public void removeMediaPlayerEventListener(MediaPlayerEventListener listener) {
    Logger.debug("removeMediaPlayerEventListener(listener={})", listener);
    eventListenerList.remove(listener);
  }

//  @Override
  public void enableEvents(int eventMask) {
    Logger.debug("enableEvents(eventMask={})", eventMask);
    this.eventMask = eventMask;
  }

//  @Override
  public void addVideoOutputEventListener(VideoOutputEventListener listener) {
    Logger.debug("addVideoOutputEventListener(listener={})", listener);
    videoOutputEventListenerList.add(listener);
  }

//  @Override
  public void removeVideoOutputEventListener(VideoOutputEventListener listener) {
    Logger.debug("removeVideoOutputEventListener(listener={})", listener);
    videoOutputEventListenerList.remove(listener);
  }

  // === Media Controls =======================================================
  
//  @Override
  public void setStandardMediaOptions(String... options) {
    Logger.debug("setStandardMediaOptions(options={})", Arrays.toString(options));
    this.standardMediaOptions = options;
  }

//  @Override
  public void playMedia(String mrl, String... mediaOptions) {
    Logger.debug("playMedia(mrl={},mediaOptions={})", mrl, Arrays.toString(mediaOptions));
    // First 'prepare' the media...
    prepareMedia(mrl, mediaOptions);
    // ...then play it
    play();
  }

//  @Override
  public void prepareMedia(String mrl, String... mediaOptions) {
    Logger.debug("prepareMedia(mrl={},mediaOptions={})", mrl, Arrays.toString(mediaOptions));
    setMedia(mrl, mediaOptions);
  }

//  @Override
  public boolean startMedia(String mrl, String... mediaOptions) {
    Logger.debug("startMedia(mrl={}, mediaOptions)", mrl, Arrays.toString(mediaOptions));
    // First 'prepare' the media...
    prepareMedia(mrl, mediaOptions);
    // ...then play it and wait for it to start (or error)
    return new MediaPlayerLatch(this).play();
  }
  
//  @Override
  public void parseMedia() {
    Logger.debug("parseMedia()");
    if(mediaInstance != null) {
      libvlc.libvlc_media_parse(mediaInstance);
    }
    else {
      throw new RuntimeException("Attempt to parse media when there is no media");
    }
  }

//  @Override
  public void requestParseMedia() {
    Logger.debug("requestParseMedia()");
    if(mediaInstance != null) {
      libvlc.libvlc_media_parse_async(mediaInstance);
    }
    else {
      throw new RuntimeException("Attempt to parse media when there is no media");
    }
  }
  
//  @Override
  public MediaMeta getMediaMeta() {
    Logger.debug("getMediaMeta()");
    return getMediaMeta(mediaInstance);
  }
  
//  @Override
  public List<MediaMeta> getSubItemMediaMeta() {
    Logger.debug("getSubItemMediaMeta()");
    // If there is a current media...
    if(mediaInstance != null) {
      // Get the list of sub-items
      libvlc_media_list_t subItems = libvlc.libvlc_media_subitems(mediaInstance);
      if(subItems != null) {
        // Lock the sub-item list
        libvlc.libvlc_media_list_lock(subItems);
        // Count the items in the list
        int count = libvlc.libvlc_media_list_count(subItems);
        // Get each sub-item
        List<MediaMeta> result = new ArrayList<MediaMeta>(count);
        for(int i = 0; i < count; i++) {
          libvlc_media_t subItemMedia = libvlc.libvlc_media_list_item_at_index(subItems, i);
          if(subItemMedia != null) {
            // Get the MRL for the sub-item
            result.add(getMediaMeta(subItemMedia));
            // Release the sub-item instance
            libvlc.libvlc_media_release(subItemMedia);
          }
        }
        // Clean up
        libvlc.libvlc_media_list_unlock(subItems);
        libvlc.libvlc_media_list_release(subItems);
        // Return the list
        return result;
      }
      else {
        return null;
      }
    }
    else {
      return null;
    }
  }

  //  @Override
  public MediaMeta getMediaMeta(libvlc_media_t media) {
    Logger.debug("getMediaMeta(media={})", media);
    if(media != null) {
      MediaMeta mediaMeta = new MediaMeta();
      mediaMeta.setTitle(getMeta(MediaMetaType.TITLE, media));
      mediaMeta.setArtist(getMeta(MediaMetaType.ARTIST, media));
      mediaMeta.setGenre(getMeta(MediaMetaType.GENRE, media));
      mediaMeta.setCopyright(getMeta(MediaMetaType.COPYRIGHT, media));
      mediaMeta.setAlbum(getMeta(MediaMetaType.ALBUM, media));
      mediaMeta.setTrackNumber(getMeta(MediaMetaType.TRACKNUMBER, media));
      mediaMeta.setDescription(getMeta(MediaMetaType.DESCRIPTION, media));
      mediaMeta.setRating(getMeta(MediaMetaType.RATING, media));
      mediaMeta.setDate(getMeta(MediaMetaType.DATE, media));
      mediaMeta.setSetting(getMeta(MediaMetaType.SETTING, media));
      mediaMeta.setUrl(getMeta(MediaMetaType.URL, media));
      mediaMeta.setLanguage(getMeta(MediaMetaType.LANGUAGE, media));
      mediaMeta.setNowPlaying(getMeta(MediaMetaType.NOWPLAYING, media));
      mediaMeta.setPublisher(getMeta(MediaMetaType.PUBLISHER, media));
      mediaMeta.setEncodedBy(getMeta(MediaMetaType.ENCODEDBY, media));
      mediaMeta.setArtworkUrl(getMeta(MediaMetaType.ARTWORKURL, media));
      mediaMeta.setTrackId(getMeta(MediaMetaType.TRACKID, media));
      return mediaMeta;
    }
    else {
      return null;
    }
  }

//  @Override
  public void addMediaOptions(String... mediaOptions) {
    Logger.debug("addMediaOptions(mediaOptions={})", Arrays.toString(mediaOptions));
    if(mediaInstance != null) {
      for(String mediaOption : mediaOptions) {
        Logger.debug("mediaOption={}", mediaOption);
        libvlc.libvlc_media_add_option(mediaInstance, mediaOption);
      }
    }
    else {
      throw new RuntimeException("No media");
    }
  }
  
  // === Sub-Item Controls ====================================================
  
//  @Override
  public void setRepeat(boolean repeat) {
    Logger.debug("setRepeat(repeat={})", repeat);
    this.repeat = repeat;
  }

  //  @Override
  public void setPlaySubItems(boolean playSubItems) {
    Logger.debug("setPlaySubItems(playSubItems={})", playSubItems);
    this.playSubItems = playSubItems;
  }
  
//  @Override
  public int subItemCount() {
    Logger.debug("subItemCount()");
    // If there is a current media...
    if(mediaInstance != null) {
      // Get the list of sub-items
      libvlc_media_list_t subItems = libvlc.libvlc_media_subitems(mediaInstance);
      if(subItems != null) {
        // Lock the sub-item list
        libvlc.libvlc_media_list_lock(subItems);
        // Count the items in the list
        int count = libvlc.libvlc_media_list_count(subItems);
        // Clean up
        libvlc.libvlc_media_list_unlock(subItems);
        libvlc.libvlc_media_list_release(subItems);
        // Return the count
        return count;
      }
      else {
        return 0;
      }
    }
    else {
      return -1;
    }
  }
  
//  @Override
  public List<String> subItems() {
    Logger.debug("subItems()");
    // If there is a current media...
    if(mediaInstance != null) {
      // Get the list of sub-items
      libvlc_media_list_t subItems = libvlc.libvlc_media_subitems(mediaInstance);
      if(subItems != null) {
        // Lock the sub-item list
        libvlc.libvlc_media_list_lock(subItems);
        // Count the items in the list
        int count = libvlc.libvlc_media_list_count(subItems);
        // Get each sub-item
        List<String> result = new ArrayList<String>(count);
        for(int i = 0; i < count; i++) {
          libvlc_media_t subItemMedia = libvlc.libvlc_media_list_item_at_index(subItems, i);
          if(subItemMedia != null) {
            // Get the MRL for the sub-item
            String subItemMrl = libvlc.libvlc_media_get_mrl(subItemMedia);
            result.add(subItemMrl);
            // Release the sub-item instance
            libvlc.libvlc_media_release(subItemMedia);
          }
        }
        // Clean up
        libvlc.libvlc_media_list_unlock(subItems);
        libvlc.libvlc_media_list_release(subItems);
        // Return the list
        return result;
      }
      else {
        return null;
      }
    }
    else {
      return null;
    }
  }

  //  @Override
  public boolean playNextSubItem(String... mediaOptions) {
    Logger.debug("playNextSubItem(mediaOptions={})", Arrays.toString(mediaOptions));
    return playSubItem(subItemIndex+1, mediaOptions);
  }

//  @Override
  public boolean playSubItem(int index, String... mediaOptions) {
    Logger.debug("playSubItem(index={},mediaOptions={})", index, Arrays.toString(mediaOptions));
    // Assume a sub-item was not played
    boolean subItemPlayed = false;
    // If there is a current media...
    if(mediaInstance != null) {
      // Get the list of sub-items
      libvlc_media_list_t subItems = libvlc.libvlc_media_subitems(mediaInstance);
      Logger.trace("subItems={}", subItems);
      // If there are sub-items...
      if(subItems != null) {
        Logger.debug("Handling media sub-item...");
        // Lock the sub-item list
        libvlc.libvlc_media_list_lock(subItems);
        // Advance the current sub-item (initially it will be -1)
        int subItemCount = libvlc.libvlc_media_list_count(subItems);
        Logger.debug("subItemCount={}", subItemCount);
        subItemIndex = index;
        Logger.debug("subItemIndex={}", subItemIndex);
        // If the last sub-item already been played...
        if(subItemIndex >= subItemCount) {
          Logger.debug("End of sub-items reached");
          if(!repeat) {
            Logger.debug("Do not repeat sub-items");
            subItemIndex = -1;
          }
          else {
            Logger.debug("Repeating sub-items");
            subItemIndex = 0;
          }
        }
        if(subItemIndex != -1) {
          // Get the required sub item from the list
          libvlc_media_t subItem = libvlc.libvlc_media_list_item_at_index(subItems, subItemIndex);
          // If there is an item to play...
          if(subItem != null) {
            // Set the sub-item as the new media for the media player
            libvlc.libvlc_media_player_set_media(mediaPlayerInstance, subItem);
            // Set any standard media options
            if(standardMediaOptions != null) {
              for(String standardMediaOption : standardMediaOptions) {
                Logger.debug("standardMediaOption={}", standardMediaOption);
                libvlc.libvlc_media_add_option(subItem, standardMediaOption);
              }
            }
            // Set any media options
            if(mediaOptions != null) {
              for(String mediaOption : mediaOptions) {
                Logger.debug("mediaOption={}", mediaOption);
                libvlc.libvlc_media_add_option(subItem, mediaOption);
              }
            }
            // Play the media
            libvlc.libvlc_media_player_play(mediaPlayerInstance);
            // Release the sub-item
            libvlc.libvlc_media_release(subItem);
            // Record the fact a sub-item was played
            subItemPlayed = true;
          }
        }
        // Unlock and release the sub-item list
        libvlc.libvlc_media_list_unlock(subItems);
        libvlc.libvlc_media_list_release(subItems);
      }
    }
    Logger.debug("subItemPlayed={}", subItemPlayed);
    return subItemPlayed;
  }
  
  // === Status Controls ======================================================

  //  @Override
  public boolean isPlayable() {
    Logger.trace("isPlayable()");
    return libvlc.libvlc_media_player_will_play(mediaPlayerInstance) == 1;
  }
  
//  @Override
  public boolean isPlaying() {
    Logger.trace("isPlaying()");
    return libvlc.libvlc_media_player_is_playing(mediaPlayerInstance) == 1;
  }
  
//  @Override
  public boolean isSeekable() {
    Logger.trace("isSeekable()");
    return libvlc.libvlc_media_player_is_seekable(mediaPlayerInstance) == 1;
  }
  
//  @Override
  public boolean canPause() {
    Logger.trace("canPause()");
    return libvlc.libvlc_media_player_can_pause(mediaPlayerInstance) == 1;
  }
  
//  @Override
  public long getLength() {
    Logger.trace("getLength()");
    return libvlc.libvlc_media_player_get_length(mediaPlayerInstance);
  }

//  @Override
  public long getTime() {
    Logger.trace("getTime()");
    return libvlc.libvlc_media_player_get_time(mediaPlayerInstance);
  }

//  @Override
  public float getPosition() {
    Logger.trace("getPosition()");
    return libvlc.libvlc_media_player_get_position(mediaPlayerInstance);
  }
  
//  @Override
  public float getFps() {
    Logger.trace("getFps()");
    return libvlc.libvlc_media_player_get_fps(mediaPlayerInstance);
  }
  
//  @Override
  public float getRate() {
    Logger.trace("getRate()");
    return libvlc.libvlc_media_player_get_rate(mediaPlayerInstance);
  }

//  @Override
  public int getVideoOutputs() {
    Logger.trace("getVideoOutputs()");
    return libvlc.libvlc_media_player_has_vout(mediaPlayerInstance);
  }
  
//  @Override
  public Dimension getVideoDimension() {
    Logger.debug("getVideoDimension()");
    if(getVideoOutputs() > 0) {
      IntByReference px = new IntByReference();
      IntByReference py = new IntByReference();
      int result = libvlc.libvlc_video_get_size(mediaPlayerInstance, 0, px, py);
      if(result == 0) {
        return new Dimension(px.getValue(), py.getValue());
      }
      else {
        Logger.warn("Video size is not available");
        return null;
      }
    }
    else {
      Logger.warn("Can't get video dimension if no video output has been started");
      return null;
    }
  }

//  @Override
  public MediaDetails getMediaDetails() {
    Logger.debug("getMediaDetails()");
    // The media must be playing to get this meta data...
    if(isPlaying()) {
      MediaDetails mediaDetails = new MediaDetails();
      mediaDetails.setTitleCount(getTitleCount());
      mediaDetails.setVideoTrackCount(getVideoTrackCount());
      mediaDetails.setAudioTrackCount(getAudioTrackCount());
      mediaDetails.setSpuCount(getSpuCount());
      mediaDetails.setTitleDescriptions(getTitleDescriptions());
      mediaDetails.setVideoDescriptions(getVideoDescriptions());
      mediaDetails.setAudioDescriptions(getAudioDescriptions());
      mediaDetails.setSpuDescriptions(getSpuDescriptions());
      Map<Integer, List<String>> allChapterDescriptions = new TreeMap<Integer, List<String>>();
      for(int i = 0; i < getTitleCount(); i++) {
        allChapterDescriptions.put(i, getChapterDescriptions(i));
      }
      mediaDetails.setChapterDescriptions(allChapterDescriptions);
      return mediaDetails;
    }
    else {
      Logger.warn("Can't get media meta data if media is not playing");
      return null;
    }
  }
  
//  @Override
  public String getAspectRatio() {
    Logger.debug("getAspectRatio()");
    return getNativeString(libvlc.libvlc_video_get_aspect_ratio(mediaPlayerInstance));
  }
  
//  @Override
  public float getScale() {
    Logger.debug("getScale()");
    return libvlc.libvlc_video_get_scale(mediaPlayerInstance);
  }

//  @Override
  public String getCropGeometry() {
    Logger.debug("getCropGeometry()");
    return getNativeString(libvlc.libvlc_video_get_crop_geometry(mediaPlayerInstance));
  }

//  @Override
  public libvlc_media_stats_t getMediaStatistics() {
    Logger.debug("getMediaStatistics()");
    // Must first check that the media is playing otherwise a fatal JVM crash
    // will occur
    if(isPlaying()) {
      if(mediaInstance != null) {
        libvlc.libvlc_media_get_stats(mediaInstance, libvlcMediaStats);
      }
    }
    return libvlcMediaStats;
  }

  // FIXME do not return the native structure, should be a Java enum
//  @Override
  public libvlc_state_t getMediaState() {
    Logger.debug("getMediaState()");
    libvlc_state_t state = null;
    if(mediaInstance != null) {
      state = libvlc_state_t.state(libvlc.libvlc_media_get_state(mediaInstance));
    }
    return state;
  }

  // FIXME do not return the native structure, should be a Java enum
//  @Override
  public libvlc_state_t getMediaPlayerState() {
    Logger.debug("getMediaPlayerState()");
    return libvlc_state_t.state(libvlc.libvlc_media_player_get_state(mediaPlayerInstance));
  }
  
  // === Title/Track Controls =================================================
  
//  @Override
  public int getTitleCount() {
    Logger.debug("getTitleCount()");
    return libvlc.libvlc_media_player_get_title_count(mediaPlayerInstance);
  }

//  @Override
  public int getTitle() {
    Logger.debug("getTitle()");
    return libvlc.libvlc_media_player_get_title(mediaPlayerInstance);
  }
  
//  @Override
  public void setTitle(int title) {
    Logger.debug("setTitle(title={})", title);
    libvlc.libvlc_media_player_set_title(mediaPlayerInstance, title);
  }
  
//  @Override
  public int getVideoTrackCount() {
    Logger.debug("getVideoTrackCount()");
    return libvlc.libvlc_video_get_track_count(mediaPlayerInstance);
  }
  
//  @Override
  public int getVideoTrack() {
    Logger.debug("getVideoTrack()");
    return libvlc.libvlc_video_get_track(mediaPlayerInstance);
  }
  
//  @Override
  public void setVideoTrack(int track) {
    Logger.debug("setVideoTrack(track={})", track);
    libvlc.libvlc_video_set_track(mediaPlayerInstance, track);
  }
  
//  @Override
  public int getAudioTrackCount() {
    Logger.debug("getVideoTrackCount()");
    return libvlc.libvlc_audio_get_track_count(mediaPlayerInstance);
  }

//  @Override
  public int getAudioTrack() {
    Logger.debug("getAudioTrack()");
    return libvlc.libvlc_audio_get_track(mediaPlayerInstance);
  }
  
//  @Override
  public void setAudioTrack(int track) {
    Logger.debug("setAudioTrack(track={})", track);
    libvlc.libvlc_audio_set_track(mediaPlayerInstance, track);
  }
  
  // === Basic Playback Controls ==============================================
  
//  @Override
  public void play() {
    Logger.debug("play()");
    onBeforePlay();
    libvlc.libvlc_media_player_play(mediaPlayerInstance);
  }

//  @Override
  public boolean playAndWait() {
    return new MediaPlayerLatch(this).play();
  }

//  @Override
  public void stop() {
    Logger.debug("stop()");
    libvlc.libvlc_media_player_stop(mediaPlayerInstance);
  }

//  @Override
  public void setPause(boolean pause) {
    Logger.debug("setPause(pause={})", pause);
    libvlc.libvlc_media_player_set_pause(mediaPlayerInstance, pause ? 1 : 0);
  }
  
//  @Override
  public void pause() {
    Logger.debug("pause()");
    libvlc.libvlc_media_player_pause(mediaPlayerInstance);
  }

//  @Override
  public void nextFrame() {
    Logger.debug("nextFrame()");
    libvlc.libvlc_media_player_next_frame(mediaPlayerInstance);
  }
  
//  @Override
  public void skip(long delta) {
    Logger.debug("skip(delta={})", delta);
    long current = getTime();
    Logger.debug("current={}", current);
    if(current != -1) {
      setTime(current + delta);
    }
  }
  
//  @Override
  public void skip(float delta) {
    Logger.debug("skip(delta={})", delta);
    float current = getPosition();
    Logger.debug("current={}", current);
    if(current != -1) {
      setPosition(current + delta);
    }
  }
  
//  @Override
  public void setTime(long time) {
    Logger.debug("setTime(time={})", time);
    libvlc.libvlc_media_player_set_time(mediaPlayerInstance, time);
  }
  
//  @Override
  public void setPosition(float position) {
    Logger.debug("setPosition(position={})", position);
    libvlc.libvlc_media_player_set_position(mediaPlayerInstance, position);
  }
  
//  @Override
  public int setRate(float rate) {
    Logger.debug("setRate(rate={})", rate);
    return libvlc.libvlc_media_player_set_rate(mediaPlayerInstance, rate);
  }
  
//  @Override
  public void setAspectRatio(String aspectRatio) {
    Logger.debug("setAspectRatio(aspectRatio={})", aspectRatio);
    libvlc.libvlc_video_set_aspect_ratio(mediaPlayerInstance, aspectRatio);
  }
  
//  @Override
  public void setScale(float factor) {
    Logger.debug("setScale(factor={})", factor);
    libvlc.libvlc_video_set_scale(mediaPlayerInstance, factor);
  }
  
//  @Override
  public void setCropGeometry(String cropGeometry) {
    Logger.debug("setCropGeometry(cropGeometry={})", cropGeometry);
    libvlc.libvlc_video_set_crop_geometry(mediaPlayerInstance, cropGeometry);
  }
  
  // === Audio Controls =======================================================

//  @Override
  public boolean selectAudioOutputDevice(String outputDeviceId) {
    Logger.debug("selectAudioOutputDevice(outputDeviceId={})", outputDeviceId);
    return 0 != libvlc.libvlc_audio_output_set(mediaPlayerInstance, outputDeviceId);
  }
  
//  @Override
  public void setAudioOutputDeviceType(AudioOutputDeviceType deviceType) {
    Logger.debug("setAudioOutputDeviceType(deviceType={})");
    libvlc.libvlc_audio_output_set_device_type(mediaPlayerInstance, deviceType.intValue());
  }
  
//  @Override
  public AudioOutputDeviceType getAudioOutputDeviceType() {
    Logger.debug("audioOutputDeviceType()");
    return AudioOutputDeviceType.valueOf(libvlc.libvlc_audio_output_get_device_type(mediaPlayerInstance));
  }
  
//  @Override
  public void mute() {
    Logger.debug("mute()");
    libvlc.libvlc_audio_toggle_mute(mediaPlayerInstance);
  }
  
//  @Override
  public void mute(boolean mute) {
    Logger.debug("mute(mute={})", mute);
    libvlc.libvlc_audio_set_mute(mediaPlayerInstance, mute ? 1 : 0);
  }
  
//  @Override
  public boolean isMute() {
    Logger.debug("isMute()");
    return libvlc.libvlc_audio_get_mute(mediaPlayerInstance) != 0;
  }
  
//  @Override
  public int getVolume() {
    Logger.debug("getVolume()");
    return libvlc.libvlc_audio_get_volume(mediaPlayerInstance);
  }
  
//  @Override
  public void setVolume(int volume) {
    Logger.debug("setVolume(volume={})", volume);
    libvlc.libvlc_audio_set_volume(mediaPlayerInstance, volume);
  }

//  @Override
  public int getAudioChannel() {
    Logger.debug("getAudioChannel()");
    return libvlc.libvlc_audio_get_channel(mediaPlayerInstance);
  }
  
//  @Override
  public void setAudioChannel(int channel) {
    Logger.debug("setAudioChannel(channel={})", channel);
    libvlc.libvlc_audio_set_channel(mediaPlayerInstance, channel);
  }
  
//  @Override
  public long getAudioDelay() {
    Logger.debug("getAudioDelay()");
    return libvlc.libvlc_audio_get_delay(mediaPlayerInstance);
  }

//  @Override
  public void setAudioDelay(long delay) {
    Logger.debug("setAudioDelay(delay={})", delay);
    libvlc.libvlc_audio_set_delay(mediaPlayerInstance, delay);
  }
  
  // === Chapter Controls =====================================================

//  @Override
  public int getChapterCount() {
    Logger.trace("getChapterCount()");
    return libvlc.libvlc_media_player_get_chapter_count(mediaPlayerInstance);
  }
  
//  @Override
  public int getChapter() {
    Logger.trace("getChapter()");
    return libvlc.libvlc_media_player_get_chapter(mediaPlayerInstance);
  }
  
//  @Override
  public void setChapter(int chapterNumber) {
    Logger.debug("setChapter(chapterNumber={})", chapterNumber);
    libvlc.libvlc_media_player_set_chapter(mediaPlayerInstance, chapterNumber);
  }
  
//  @Override
  public void nextChapter() {
    Logger.debug("nextChapter()");
    libvlc.libvlc_media_player_next_chapter(mediaPlayerInstance);
  }
  
//  @Override
  public void previousChapter() {
    Logger.debug("previousChapter()");
    libvlc.libvlc_media_player_previous_chapter(mediaPlayerInstance);
  }

  // === DVD Menu Navigation Controls =========================================

//  @Override
  public void menuActivate() {
    Logger.debug("menuActivate()");
    libvlc.libvlc_media_player_navigate(mediaPlayerInstance, libvlc_navigate_mode_e.libvlc_navigate_activate.intValue());
  }
  
//  @Override
  public void menuUp() {
    Logger.debug("menuUp()");
    libvlc.libvlc_media_player_navigate(mediaPlayerInstance, libvlc_navigate_mode_e.libvlc_navigate_up.intValue());
  }
  
//  @Override
  public void menuDown() {
    Logger.debug("menuDown()");
    libvlc.libvlc_media_player_navigate(mediaPlayerInstance, libvlc_navigate_mode_e.libvlc_navigate_down.intValue());
  }
  
//  @Override
  public void menuLeft() {
    Logger.debug("menuLeft()");
    libvlc.libvlc_media_player_navigate(mediaPlayerInstance, libvlc_navigate_mode_e.libvlc_navigate_left.intValue());
  }

//  @Override
  public void menuRight() {
    Logger.debug("menuRight()");
    libvlc.libvlc_media_player_navigate(mediaPlayerInstance, libvlc_navigate_mode_e.libvlc_navigate_right.intValue());
  }
  
  // === Sub-Picture/Sub-Title Controls =======================================
  
//  @Override
  public int getSpuCount() {
    Logger.debug("getSpuCount()");
    return libvlc.libvlc_video_get_spu_count(mediaPlayerInstance);
  }
  
//  @Override
  public int getSpu() {
    Logger.debug("getSpu()");
    return libvlc.libvlc_video_get_spu(mediaPlayerInstance);
  }
  
//  @Override
  public void setSpu(int spu) {
    Logger.debug("setSpu(spu={})", spu);
    int spuCount = getSpuCount();
    Logger.debug("spuCount={}", spuCount);
    if(spuCount != 0 && spu <= spuCount) {
      libvlc.libvlc_video_set_spu(mediaPlayerInstance, spu);
    }
    else {
      Logger.debug("Ignored out of range spu number {} because spu count is {}", spu, spuCount);
    }
  }

//  @Override
  public void cycleSpu() {
    Logger.debug("cycleSpu()");
    int spu = getSpu();
    int spuCount = getSpuCount();
    if(spu >= spuCount) {
      spu = 0;
    }
    else {
      spu++;
    }
    setSpu(spu);
  }
  
  // === Description Controls =================================================
  
//  @Override
  public List<TrackDescription> getTitleDescriptions() {
    Logger.debug("getTitleDescriptions()");
    List<TrackDescription> trackDescriptionList = new ArrayList<TrackDescription>();
    libvlc_track_description_t trackDescriptions = libvlc.libvlc_video_get_title_description(mediaPlayerInstance);
    libvlc_track_description_t trackDescription = trackDescriptions;
    while(trackDescription != null) {
      trackDescriptionList.add(new TrackDescription(trackDescription.i_id, trackDescription.psz_name));
      trackDescription = trackDescription.p_next;      
    }   
    if(trackDescriptions != null) {
      libvlc.libvlc_track_description_release(trackDescriptions.getPointer());
    }
    return trackDescriptionList;
  }  
  
//  @Override
  public List<TrackDescription> getVideoDescriptions() {
    Logger.debug("getVideoDescriptions()");
    List<TrackDescription> trackDescriptionList = new ArrayList<TrackDescription>();
    libvlc_track_description_t trackDescriptions = libvlc.libvlc_video_get_track_description(mediaPlayerInstance);
    libvlc_track_description_t trackDescription = trackDescriptions;
    while(trackDescription != null) {
      trackDescriptionList.add(new TrackDescription(trackDescription.i_id, trackDescription.psz_name));
      trackDescription = trackDescription.p_next;      
    }   
    if(trackDescriptions != null) {
      libvlc.libvlc_track_description_release(trackDescriptions.getPointer());
    }
    return trackDescriptionList;
  }
  
//  @Override
  public List<TrackDescription> getAudioDescriptions() {
    Logger.debug("getAudioDescriptions()");
    List<TrackDescription> trackDescriptionList = new ArrayList<TrackDescription>();
    libvlc_track_description_t trackDescriptions = libvlc.libvlc_audio_get_track_description(mediaPlayerInstance);
    libvlc_track_description_t trackDescription = trackDescriptions;
    while(trackDescription != null) {
      trackDescriptionList.add(new TrackDescription(trackDescription.i_id, trackDescription.psz_name));
      trackDescription = trackDescription.p_next;      
    }   
    if(trackDescriptions != null) {
      libvlc.libvlc_track_description_release(trackDescriptions.getPointer());
    }
    return trackDescriptionList;
  }  
  
//  @Override
  public List<TrackDescription> getSpuDescriptions() {
    Logger.debug("getSpuDescriptions()");
    List<TrackDescription> trackDescriptionList = new ArrayList<TrackDescription>();
    libvlc_track_description_t trackDescriptions = libvlc.libvlc_video_get_spu_description(mediaPlayerInstance);
    libvlc_track_description_t trackDescription = trackDescriptions;
    while(trackDescription != null) {
      trackDescriptionList.add(new TrackDescription(trackDescription.i_id, trackDescription.psz_name));
      trackDescription = trackDescription.p_next;      
    }   
    if(trackDescriptions != null) {
      libvlc.libvlc_track_description_release(trackDescriptions.getPointer());
    }
    return trackDescriptionList;
  }  

//  @Override
  public List<String> getChapterDescriptions(int title) {
    Logger.debug("getChapterDescriptions(title={})", title);
    List<String> trackDescriptionList = new ArrayList<String>();
    libvlc_track_description_t trackDescriptions = libvlc.libvlc_video_get_chapter_description(mediaPlayerInstance, title);
    libvlc_track_description_t trackDescription = trackDescriptions;
    while(trackDescription != null) {
      trackDescriptionList.add(trackDescription.psz_name);
      trackDescription = trackDescription.p_next;      
    }
    if(trackDescriptions != null) {
      libvlc.libvlc_track_description_release(trackDescriptions.getPointer());
    }
    return trackDescriptionList;
  }  

//  @Override
  public List<TrackInfo> getTrackInfo() {
    Logger.debug("getTrackInfo()");
    if(mediaInstance != null) {
      PointerByReference tracks = new PointerByReference();
      int numberOfTracks = libvlc.libvlc_media_get_tracks_info(mediaInstance, tracks);
      Logger.trace("numberOfTracks={}", numberOfTracks);
      libvlc_media_track_info_t trackInfos = new libvlc_media_track_info_t(tracks.getValue());
      libvlc_media_track_info_t[] trackInfoArray = (libvlc_media_track_info_t[])trackInfos.toArray(numberOfTracks);
      List<TrackInfo> result = new ArrayList<TrackInfo>();
      for(libvlc_media_track_info_t trackInfo : trackInfoArray) {
        // i_type values are from vlc/vlc_es.h
        switch(trackInfo.i_type) {
          // UNKNOWN_ES
          case 0:
            result.add(new UnknownTrackInfo(trackInfo.i_codec, trackInfo.i_id, trackInfo.i_profile, trackInfo.i_level));
            break;
            
          // VIDEO_ES
          case 1:
            libvlc_media_track_info_video_t videoInfo = (libvlc_media_track_info_video_t)trackInfos.u.getTypedValue(libvlc_media_track_info_video_t.class);
            result.add(new VideoTrackInfo(trackInfo.i_codec, trackInfo.i_id, trackInfo.i_profile, trackInfo.i_level, videoInfo.i_width, videoInfo.i_height));
            break;
            
          // AUDIO_ES
          case 2:
            libvlc_media_track_info_audio_t audioInfo = (libvlc_media_track_info_audio_t)trackInfos.u.getTypedValue(libvlc_media_track_info_audio_t.class);
            result.add(new AudioTrackInfo(trackInfo.i_codec, trackInfo.i_id, trackInfo.i_profile, trackInfo.i_level, audioInfo.i_channels, audioInfo.i_rate));
            break;

          // SPU_ES
          case 3:
            result.add(new SpuTrackInfo(trackInfo.i_codec, trackInfo.i_id, trackInfo.i_profile, trackInfo.i_level));
            break;
            
          // NAV_ES
          case 4:
            // Unused
            break;
        }
      }
      libvlc.libvlc_free(tracks.getValue());
      return result;
    }
    else {
      return null;
    }
  }
  
  // === Snapshot Controls ====================================================

//  @Override
  public boolean saveSnapshot() {
    Logger.debug("saveSnapshot()");
    File snapshotDirectory = new File(System.getProperty("user.home"));
    File snapshotFile = new File(snapshotDirectory, "vlcj-snapshot-" + System.currentTimeMillis() + ".png");
    return saveSnapshot(snapshotFile);
  }
  
//  @Override
  public boolean saveSnapshot(File file) {
    Logger.debug("saveSnapshot(file={})", file);
    File snapshotDirectory = file.getParentFile();
    if(!snapshotDirectory.exists()) {
      snapshotDirectory.mkdirs();
    }
    if(snapshotDirectory.exists()) {
      boolean snapshotTaken = libvlc.libvlc_video_take_snapshot(mediaPlayerInstance, 0, file.getAbsolutePath(), 0, 0) == 0;
      Logger.debug("snapshotTaken={}", snapshotTaken);
      return snapshotTaken;
    }
    else {
      throw new RuntimeException("Directory does not exist and could not be created for '" + file.getAbsolutePath() + "'");
    }
  }

//  @Override
  public BufferedImage getSnapshot() {
    Logger.debug("getSnapshot()");
    try {
      File file = File.createTempFile("vlcj-snapshot-", ".png");
      Logger.debug("file={}", file.getAbsolutePath());
      if(saveSnapshot(file)) {
        BufferedImage snapshotImage = ImageIO.read(file);
        boolean deleted = file.delete();
        Logger.debug("deleted={}", deleted);
        return snapshotImage;
      }
      else {
        return null;
      }
    }
    catch(IOException e) {
      throw new RuntimeException("Failed to get snapshot image", e);
    }
  }
  
  // === Logo Controls ========================================================

//  @Override
  public void enableLogo(boolean enable) {
    Logger.debug("enableLogo(enable={})", enable);
    libvlc.libvlc_video_set_logo_int(mediaPlayerInstance, libvlc_video_logo_option_t.libvlc_logo_enable.intValue(), enable ? 1 : 0);
  }

//  @Override
  public void setLogoOpacity(int opacity) {
    Logger.debug("setLogoOpacity(opacity={})", opacity);
    libvlc.libvlc_video_set_logo_int(mediaPlayerInstance, libvlc_video_logo_option_t.libvlc_logo_opacity.intValue(), opacity);
  }

//  @Override
  public void setLogoOpacity(float opacity) {
    Logger.debug("setLogoOpacity(opacity={})", opacity);
    int opacityValue = Math.round(opacity * 255.0f);
    Logger.debug("opacityValue={}", opacityValue);
    libvlc.libvlc_video_set_logo_int(mediaPlayerInstance, libvlc_video_logo_option_t.libvlc_logo_opacity.intValue(), opacityValue);
  }
  
//  @Override
  public void setLogoLocation(int x, int y) {
    Logger.debug("setLogoLocation(x={},y={})", x ,y);
    libvlc.libvlc_video_set_logo_int(mediaPlayerInstance, libvlc_video_logo_option_t.libvlc_logo_x.intValue(), x);
    libvlc.libvlc_video_set_logo_int(mediaPlayerInstance, libvlc_video_logo_option_t.libvlc_logo_y.intValue(), y);
  }

//  @Override
  public void setLogoPosition(libvlc_logo_position_e position) {
    Logger.debug("setLogoPosition(position={})", position);
    libvlc.libvlc_video_set_logo_int(mediaPlayerInstance, libvlc_video_logo_option_t.libvlc_logo_position.intValue(), position.intValue());
  }
  
//  @Override
  public void setLogoFile(String logoFile) {
    Logger.debug("setLogoFile(logoFile={})", logoFile);
    libvlc.libvlc_video_set_logo_string(mediaPlayerInstance, libvlc_video_logo_option_t.libvlc_logo_file.intValue(), logoFile);
  }
  
  // === Marquee Controls =====================================================

//  @Override
  public void enableMarquee(boolean enable) {
    Logger.debug("enableMarquee(enable={})", enable);
    libvlc.libvlc_video_set_marquee_int(mediaPlayerInstance, libvlc_video_marquee_option_t.libvlc_marquee_Enable.intValue(), enable ? 1 : 0);
  }

//  @Override
  public void setMarqueeText(String text) {
    Logger.debug("setMarqueeText(text={})", text);
    libvlc.libvlc_video_set_marquee_string(mediaPlayerInstance, libvlc_video_marquee_option_t.libvlc_marquee_Text.intValue(), text);
  }

//  @Override
  public void setMarqueeColour(Color colour) {
    Logger.debug("setMarqueeColour(colour={})", colour);
    setMarqueeColour(colour.getRGB() & 0x00ffffff);
  }

//  @Override
  public void setMarqueeColour(int colour) {
    Logger.debug("setMarqueeColour(colour={})", colour);
    libvlc.libvlc_video_set_marquee_int(mediaPlayerInstance, libvlc_video_marquee_option_t.libvlc_marquee_Color.intValue(), colour);
  }
  
//  @Override
  public void setMarqueeOpacity(int opacity) {
    Logger.debug("setMarqueeOpacity(opacity={})", opacity);
    libvlc.libvlc_video_set_marquee_int(mediaPlayerInstance, libvlc_video_marquee_option_t.libvlc_marquee_Opacity.intValue(), opacity);
  }
  
//  @Override
  public void setMarqueeOpacity(float opacity) {
    Logger.debug("setMarqueeOpacity(opacity={})", opacity);
    int opacityValue = Math.round(opacity * 255.0f);
    Logger.debug("opacityValue={}", opacityValue);
    libvlc.libvlc_video_set_marquee_int(mediaPlayerInstance, libvlc_video_marquee_option_t.libvlc_marquee_Opacity.intValue(), opacityValue);
  }
  
//  @Override
  public void setMarqueeSize(int size) {
    Logger.debug("setMarqueeSize(size={})", size);
    libvlc.libvlc_video_set_marquee_int(mediaPlayerInstance, libvlc_video_marquee_option_t.libvlc_marquee_Size.intValue(), size);
  }
  
//  @Override
  public void setMarqueeTimeout(int timeout) {
    Logger.debug("setMarqueeTimeout(timeout={})", timeout);
    libvlc.libvlc_video_set_marquee_int(mediaPlayerInstance, libvlc_video_marquee_option_t.libvlc_marquee_Timeout.intValue(), timeout);
  }
  
//  @Override
  public void setMarqueeLocation(int x, int y) {
    Logger.debug("setMarqueeLocation(x={},y={})", x ,y);
    libvlc.libvlc_video_set_marquee_int(mediaPlayerInstance, libvlc_video_marquee_option_t.libvlc_marquee_X.intValue(), x);
    libvlc.libvlc_video_set_marquee_int(mediaPlayerInstance, libvlc_video_marquee_option_t.libvlc_marquee_Y.intValue(), y);
  }
  
  // === Filter Controls ======================================================
  
//  @Override
  public void setDeinterlace(DeinterlaceMode deinterlaceMode) {
    Logger.debug("setDeinterlace(deinterlaceMode={})", deinterlaceMode);
    libvlc.libvlc_video_set_deinterlace(mediaPlayerInstance, deinterlaceMode.mode());
  }
  
  // === Video Adjustment Controls ============================================
  
//  @Override
  public void setAdjustVideo(boolean adjustVideo) {
    Logger.debug("setAdjustVideo(adjustVideo={})", adjustVideo);
    libvlc.libvlc_video_set_adjust_int(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Enable.intValue(), adjustVideo ? 1 : 0);
  }
  
//  @Override
  public boolean isAdjustVideo() {
    Logger.debug("isAdjustVideo()");
    return libvlc.libvlc_video_get_adjust_int(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Enable.intValue()) == 1;
  }
  
//  @Override
  public float getContrast() {
    Logger.debug("getContrast()");
    return libvlc.libvlc_video_get_adjust_float(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Contrast.intValue());
  }
  
//  @Override
  public void setContrast(float contrast) {
    Logger.debug("setContrast(contrast={})", contrast);
    libvlc.libvlc_video_set_adjust_float(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Contrast.intValue(), contrast);
  }
  
//  @Override
  public float getBrightness() {
    Logger.debug("getBrightness()");
    return libvlc.libvlc_video_get_adjust_float(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Brightness.intValue());
  }
  
//  @Override
  public void setBrightness(float brightness) {
    Logger.debug("setBrightness(brightness={})", brightness);
    libvlc.libvlc_video_set_adjust_float(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Brightness.intValue(), brightness);
  }
  
//  @Override
  public int getHue() {
    Logger.debug("getHue()");
    return libvlc.libvlc_video_get_adjust_int(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Hue.intValue());
  }
  
//  @Override
  public void setHue(int hue) {
    Logger.debug("setHue(hue={})", hue);
    libvlc.libvlc_video_set_adjust_int(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Hue.intValue(), hue);
  }
  
//  @Override
  public float getSaturation() {
    Logger.debug("getSaturation()");
    return libvlc.libvlc_video_get_adjust_float(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Saturation.intValue());
  }
  
//  @Override
  public void setSaturation(float saturation) {
    Logger.debug("setSaturation(saturation={})", saturation);
    libvlc.libvlc_video_set_adjust_float(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Saturation.intValue(), saturation);
  }
  
//  @Override
  public float getGamma() {
    Logger.debug("getGamma()");
    return libvlc.libvlc_video_get_adjust_float(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Gamma.intValue());
  }
  
//  @Override
  public void setGamma(float gamma) {
    Logger.debug("setGamma(gamma={})", gamma);
    libvlc.libvlc_video_set_adjust_float(mediaPlayerInstance, libvlc_video_adjust_option_t.libvlc_adjust_Gamma.intValue(), gamma);
  }
  
  // === Implementation =======================================================

//  @Override
  public String mrl(libvlc_media_t mediaInstance) {
    Logger.debug("mrl(mediaInstance={})", mediaInstance);
    return libvlc.libvlc_media_get_mrl(mediaInstance);
  }

//  @Override
  public Object userData() {
    Logger.debug("userData()");
    return userData;
  }

//  @Override
  public void userData(Object userData) {
    Logger.debug("userData(userData={})", userData);
    this.userData = userData;
  }

//  @Override
  public final void release() {
    Logger.debug("release()");
    if(released.compareAndSet(false, true)) {
      destroyInstance();
      onAfterRelease();
    }
  }

//  @Override
  public final libvlc_media_player_t mediaPlayerInstance() {
    return mediaPlayerInstance;
  }

  /**
   * Override the default video output test wait period.
   * <p>
   * The native media player will be repeatedly polled according to this period
   * in order to check if video output has started playing or not.
   * <p> 
   * This is not part of the MediaPlayer API (it an an implementation detail
   * and so does not appear on the interface).
   * <p>
   * Most applications will not need to use this method, instead relying on the
   * sensible default wait period. 
   * 
   * @param videoOutputWaitPeriod wait period, in milliseconds, or zero
   * @param videoOutputTimeout maximum amount of time to wait for a video output to start
   */
  public void setVideoOutputWaitPeriod(int videoOutputWaitPeriod, int videoOutputTimeout) {
    Logger.debug("setVideoOutputWaitPeriod(videoOutputWaitPeriod={},videoOutputTimeout={})", videoOutputWaitPeriod, videoOutputTimeout);
    this.videoOutputWaitPeriod = videoOutputWaitPeriod;
    this.videoOutputTimeout = videoOutputTimeout;
  }
  
  /**
   * Allow sub-classes to do something just before the video is started.
   */
  protected void onBeforePlay() {
    // Base implementation does nothing
  }
  
  /**
   * Allow sub-classes to clean-up.
   */
  protected void onAfterRelease() {
    // Base implementation does nothing
  }

  /**
   * Create and prepare the native media player resources.
   */
  private void createInstance() {
    Logger.debug("createInstance()");
    
    mediaPlayerInstance = libvlc.libvlc_media_player_new(instance);
    Logger.debug("mediaPlayerInstance={}", mediaPlayerInstance);
    
    mediaPlayerEventManager = libvlc.libvlc_media_player_event_manager(mediaPlayerInstance);
    Logger.debug("mediaPlayerEventManager={}", mediaPlayerEventManager);
  
    registerEventListener();

    eventListenerList.add(new VideoOutputEventHandler());
    eventListenerList.add(new RepeatPlayEventHandler());
    eventListenerList.add(new SubItemEventHandler());
  }

  /**
   * Clean up the native media player resources.
   */
  private void destroyInstance() {
    Logger.debug("destroyInstance()");
    
    Logger.debug("Detach media events...");
    deregisterMediaEventListener();
    Logger.debug("Media events detached.");
    
    if(mediaInstance != null) {
      Logger.debug("Release media...");
      libvlc.libvlc_media_release(mediaInstance);
      Logger.debug("Media released.");
    }
    
    Logger.debug("Detach media player events...");
    deregisterEventListener();
    Logger.debug("Media player events detached.");

    eventListenerList.clear();
    
    videoOutputEventListenerList.clear();
    
    if(mediaPlayerInstance != null) {
      Logger.debug("Release media player...");
      libvlc.libvlc_media_player_release(mediaPlayerInstance);
      Logger.debug("Media player released.");
    }

    Logger.debug("Shut down listeners...");
    listenersService.shutdown();
    Logger.debug("Listeners shut down.");
    
    videoOutputService.shutdown();
  }

  /**
   * Register a call-back to receive native media player events.
   */
  private void registerEventListener() {
    Logger.debug("registerEventListener()");
    callback = new VlcVideoPlayerCallback();
    for(libvlc_event_e event : libvlc_event_e.values()) {
      if(event.intValue() >= libvlc_event_e.libvlc_MediaPlayerMediaChanged.intValue() && event.intValue() <= libvlc_event_e.libvlc_MediaPlayerLengthChanged.intValue()) {
        Logger.debug("event={}", event);
        int result = libvlc.libvlc_event_attach(mediaPlayerEventManager, event.intValue(), callback, null);
        Logger.debug("result={}", result);
      }
    }
  }

  /**
   * De-register the call-back used to receive native media player events.
   */
  private void deregisterEventListener() {
    Logger.debug("deregisterEventListener()");
    if(callback != null) {
      for(libvlc_event_e event : libvlc_event_e.values()) {
        if(event.intValue() >= libvlc_event_e.libvlc_MediaPlayerMediaChanged.intValue() && event.intValue() <= libvlc_event_e.libvlc_MediaPlayerLengthChanged.intValue()) {
          Logger.debug("event={}", event);
          libvlc.libvlc_event_detach(mediaPlayerEventManager, event.intValue(), callback, null);
        }
      }
      callback = null;
    }
  }

  /**
   * Register a call-back to receive media native events.
   */
  private void registerMediaEventListener() {
    Logger.debug("registerMediaEventListener()");
    // If there is a media, register a new listener...
    if(mediaInstance != null) {
      libvlc_event_manager_t mediaEventManager = libvlc.libvlc_media_event_manager(mediaInstance);
      for(libvlc_event_e event : libvlc_event_e.values()) {
        if(event.intValue() >= libvlc_event_e.libvlc_MediaMetaChanged.intValue() && event.intValue() <= libvlc_event_e.libvlc_MediaStateChanged.intValue()) {
          Logger.debug("event={}", event);
          int result = libvlc.libvlc_event_attach(mediaEventManager, event.intValue(), callback, null);
          Logger.debug("result={}", result);
        }
      }
    }
  }
  
  /**
   * De-register the call-back used to receive native media events.
   */
  private void deregisterMediaEventListener() {
    Logger.debug("deregisterMediaEventListener()");
    // If there is a media, deregister the listener...
    if(mediaInstance != null) {
      libvlc_event_manager_t mediaEventManager = libvlc.libvlc_media_event_manager(mediaInstance);
      for(libvlc_event_e event : libvlc_event_e.values()) {
        if(event.intValue() >= libvlc_event_e.libvlc_MediaMetaChanged.intValue() && event.intValue() <= libvlc_event_e.libvlc_MediaStateChanged.intValue()) {
          Logger.debug("event={}", event);
          libvlc.libvlc_event_detach(mediaEventManager, event.intValue(), callback, null);
        }
      }
      mediaEventManager = null;
    }
  }

  /**
   * 
   * 
   * @param media
   * @param mediaOptions
   */
  private boolean setMedia(String media, String... mediaOptions) {
    Logger.debug("setMedia(media={},mediaOptions={})" , media, Arrays.toString(mediaOptions));
    // If there is a current media, clean it up
    if(mediaInstance != null) {
      deregisterMediaEventListener();
      mediaInstance = null;
    }
    // Reset sub-items
    subItemIndex = -1;
    // Create new media...
    mediaInstance = libvlc.libvlc_media_new_path(instance, media);
    Logger.debug("mediaInstance={}", mediaInstance);
    if(mediaInstance != null) {
      // Set the standard media options (if any)...
      if(standardMediaOptions != null) {
        for(String standardMediaOption : standardMediaOptions) {
          Logger.debug("standardMediaOption={}", standardMediaOption);
          libvlc.libvlc_media_add_option(mediaInstance, standardMediaOption);
        }
      }
      // Set the particular media options (if any)...
      if(mediaOptions != null) {
        for(String mediaOption : mediaOptions) {
          Logger.debug("mediaOption={}", mediaOption);
          libvlc.libvlc_media_add_option(mediaInstance, mediaOption);
        }
      }
      // Attach a listener to the new media
      registerMediaEventListener();
      // Set the new media on the media player
      libvlc.libvlc_media_player_set_media(mediaPlayerInstance, mediaInstance);
    }
    // Prepare a new statistics object to re-use for the new media item
    libvlcMediaStats = new libvlc_media_stats_t();
    return mediaInstance != null;
  }

  /**
   * Get a local meta data value for a media instance.
   * 
   * @param metaType type of meta data
   * @param media media instance
   * @return meta data value
   */
  private String getMeta(MediaMetaType metaType, libvlc_media_t media) {
    Logger.trace("getMeta(metaType={},media={})", metaType, media);
    if(media != null) {
      return getNativeString(libvlc.libvlc_media_get_meta(media, metaType.intValue()));
    }
    else {
      throw new RuntimeException("Attempt to get media meta when there is no media");
    }
  }

  /**
   * A call-back to handle events from the native media player.
   * <p>
   * There are some important implementation details for this callback:
   * <ul>
   *   <li>First, the event notifications are off-loaded to a different thread
   *       so as to prevent application code re-entering libvlc in an event
   *       call-back which may lead to a deadlock in the native code;</li>
   *   <li>Second, the native event union structure refers to natively 
   *       allocated memory which will not be in the scope of the thread used 
   *       to actually dispatch the event notifications.</li> 
   * </ul>
   * Without copying the fields at this point from the native union structure,
   * the native memory referred to by the native event is likely to get 
   * deallocated and overwritten by the time the notification thread runs. This
   * would lead to unreliable data being sent with the notification, or even a 
   * fatal JVM crash.  
   */
  private final class VlcVideoPlayerCallback implements libvlc_callback_t {
//    @Override
    public void callback(libvlc_event_t event, Pointer userData) {
      Logger.trace("callback(event={},userData={})", event, userData);
      if(!eventListenerList.isEmpty()) {
        // Create a new media player event for the native event
        MediaPlayerEvent mediaPlayerEvent = eventFactory.newMediaPlayerEvent(event, eventMask);
        Logger.trace("mediaPlayerEvent={}", mediaPlayerEvent);
        if(mediaPlayerEvent != null) {
          listenersService.submit(new NotifyEventListenersRunnable(mediaPlayerEvent));
        }
      }
    }
  }

  /**
   * A runnable task used to fire event notifications.
   * <p>
   * Care must be taken not to re-enter the native library during an event
   * notification so the notifications are off-loaded to a separate thread.
   */
  private final class NotifyEventListenersRunnable implements Runnable {

    /**
     * Event to notify.
     */
    private final MediaPlayerEvent mediaPlayerEvent;

    /**
     * Create a runnable.
     * 
     * @param mediaPlayerEvent event to notify
     */
    private NotifyEventListenersRunnable(MediaPlayerEvent mediaPlayerEvent) {
      this.mediaPlayerEvent = mediaPlayerEvent;
    }

//    @Override
    public void run() {
      Logger.trace("run()");
      for(int i = eventListenerList.size() - 1; i >= 0; i--) {
        MediaPlayerEventListener listener = eventListenerList.get(i);
        try {
          mediaPlayerEvent.notify(listener);
        }
        catch(Throwable t) {
          Logger.warn("Event listener {} threw an exception", t, listener);
          // Continue with the next listener...
        }
      }
      Logger.trace("runnable exits");
    }
  }
  
  /**
   * Background task to wait for a video output to start.
   * <p>
   * These tasks are only created if there is at least one video output 
   * listener registered on the media player.
   */
  private class WaitForVideoOutputRunnable implements Runnable {

    @Override
    public void run() {
      Logger.debug("run()");
      // Wait for a video output to be started
      boolean videoOutput = new VideoOutputLatch(DefaultMediaPlayer.this, videoOutputWaitPeriod, videoOutputTimeout).waitForVideoOutput();
      // Notify listeners...
      for(int i = videoOutputEventListenerList.size()-1; i >= 0; i--) {
        try {
          videoOutputEventListenerList.get(i).videoOutputAvailable(DefaultMediaPlayer.this, videoOutput);
        }
        catch(Throwable t) {
          Logger.warn("Exception thrown by listener {}", videoOutputEventListenerList.get(i));
          // Notify the remaining listeners...
        }
      }
      Logger.trace("runnable exits");
    }
  }

  /**
   * Event listener implementation that handles waiting for video output.
   */
  private final class VideoOutputEventHandler extends MediaPlayerEventAdapter {

    @Override
    public void playing(MediaPlayer mediaPlayer) {
      Logger.debug("playing(mediaPlayer={})", mediaPlayer);
      // If there is at least one video output listener...
      if(!videoOutputEventListenerList.isEmpty()) {
        // Kick off an asynchronous task to wait for a video output
        videoOutputService.execute(new WaitForVideoOutputRunnable());
      }
    }
  }
  
  /**
   * Event listener implementation that handles auto-repeat.
   */
  private final class RepeatPlayEventHandler extends MediaPlayerEventAdapter {

    @Override
    public void finished(MediaPlayer mediaPlayer) {
      Logger.debug("finished(mediaPlayer={})", mediaPlayer);
      if(repeat && mediaInstance != null) {
        int subItemCount = subItemCount();
        Logger.debug("subitemCount={}", subItemCount);
        if(subItemCount == 0) {
          String mrl = libvlc.libvlc_media_get_mrl(mediaInstance);
          Logger.debug("auto repeat mrl={}", mrl);
          // It is not sufficient to simply call play(), the MRL must explicitly
          // be played again - this is the reason why the repeat play might not
          // be seamless
          mediaPlayer.playMedia(mrl);
        }
        else {
          Logger.debug("Sub-items handling repeat");
        }
      }
      else {
        Logger.debug("No repeat");
      }
    }
  }
  
  /**
   * Event listener implementation that handles media sub-items.
   * <p>
   * Some media types when you 'play' them do not actually play any media and
   * instead sub-items are created and attached to the current media 
   * descriptor.  
   * <p>
   * This event listener responds to the media player "finished" event by
   * getting the current media from the player and automatically playing the
   * first sub-item (if there is one).
   * <p>
   * If there is more than one sub-item, then they will simply be played in
   * order, and repeated depending on the value of the "repeat" property.
   */
  private final class SubItemEventHandler extends MediaPlayerEventAdapter {
    @Override
    public void finished(MediaPlayer mediaPlayer) {
      Logger.debug("finished(mediaPlayer={})", mediaPlayer);
      if(playSubItems) {
        playNextSubItem();
      }
    }
  }
}
